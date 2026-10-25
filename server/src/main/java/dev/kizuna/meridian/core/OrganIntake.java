package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

import java.util.Set;

/** 一个稳定活动区间的通用摄入；产生候选身体，实际环境扣账由事务执行。 */
public final class OrganIntake {
    private OrganIntake() {
        // 摄入按显式区间结算，不创建后台线程或逐帧更新。
    }

    public static BodyTimeAdvancement.Result apply(BodySnapshot body, Anatomy.OrganId organId,
                                                   QiInventory incoming, BodyTimeAdvancement.Plan plan) {
        // 无附带组织代价的摄入仍遵循身体当前动作占用。
        return apply(body, organId, incoming, plan, Set.of());
    }

    public static BodyTimeAdvancement.Result apply(BodySnapshot body, Anatomy.OrganId organId,
                                                   QiInventory incoming, BodyTimeAdvancement.Plan plan,
                                                   Set<Anatomy.TissueId> intervalUsedTissues) {
        // 先确认有效活动区间；成功会消费这段身体时间，换请求 ID 不能重复吸收。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        MeridianErrors.required(incoming, Data.QI_REQUIRED);
        MeridianErrors.required(plan, Data.TIME_PLAN_REQUIRED);
        if (incoming.entries().isEmpty()) {
            throw MeridianErrors.invalid(Environment.INTAKE_POSITIVE);
        }
        QiAccounting.validateTransferable(incoming);
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectIntake(FailureCode.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectIntake(FailureCode.REVISION_EXHAUSTED);
        }
        if (plan.mode() != BodyTimeAdvancement.Mode.ACTIVE) {
            throw MeridianErrors.rejectIntake(FailureCode.TRANSPORT_FROZEN);
        }
        TimeCursor start = body.settledAt();
        TimeCursor end = plan.target();
        if (!start.domain().equals(end.domain()) || !start.epoch().equals(end.epoch())) {
            throw MeridianErrors.rejectIntake(FailureCode.CLOCK_MISMATCH);
        }
        if (end.elapsedMillis() <= start.elapsedMillis()) {
            throw MeridianErrors.rejectIntake(FailureCode.TIME_NOT_ADVANCED);
        }

        // 寿尽必须单独处理；加工改变器官可用缓存，仍由宿主按完成边界拆段。
        // 已预约的运输与自然扩散可以在区间内结算，不应切碎器官有效摄入时长。
        var deathBoundary = BodyTimeAdvancement.nextLifespanBoundary(body, plan.biologicalSecondsPerSimulationSecond());
        var completionBoundary = OrganProcessing.nextCompletionBoundary(body);
        boolean diesDuringIntake = deathBoundary.isPresent()
                && deathBoundary.get().elapsedMillis() <= end.elapsedMillis();
        boolean crossesProcessing = completionBoundary.isPresent()
                && completionBoundary.get().elapsedMillis() < end.elapsedMillis();
        if (diesDuringIntake || crossesProcessing) {
            throw MeridianErrors.rejectIntake(FailureCode.PENDING_TIME_EVENT);
        }

        // 能力来自实际构型；无经脉生物也能摄入，器官失能或所属部位失能则整笔拒绝。
        Anatomy.OrganDefinition definition = body.structure().organs().stream()
                .filter(organ -> organ.id().equals(organId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.rejectIntake(FailureCode.MODULE_NOT_FOUND));
        if (definition.intake() == null) {
            throw MeridianErrors.rejectIntake(FailureCode.INTAKE_UNSUPPORTED);
        }
        var accounts = new QiAccounts(body);
        if (!accounts.organAvailable(definition)) {
            throw MeridianErrors.rejectIntake(FailureCode.MODULE_UNAVAILABLE);
        }
        double durationSeconds = (end.elapsedMillis() - start.elapsedMillis()) / 1000.0;
        double intakeLimit = definition.intake().maxQiPerSecond() * durationSeconds;
        if (!Double.isFinite(intakeLimit)) {
            throw MeridianErrors.rejectIntake(FailureCode.NUMERIC_LIMIT);
        }
        if (incoming.totalQi() > intakeLimit) {
            throw MeridianErrors.rejectIntake(FailureCode.THROUGHPUT_EXCEEDED);
        }

        // 在途货物已预约目标空间，加工原料也占容量；摄入不能抢占这两部分空间。
        requireIntakeSpace(body, accounts, definition, incoming.volume());

        // 先结算既有事件，再归入本段摄入，避免未来收入改变起点处阻塞任务的结果。
        BodyTimeAdvancement.Result advanced = BodyTimeAdvancement.apply(body, plan, intervalUsedTissues);
        BodySnapshot settled = advanced.body();
        var settledAccounts = new QiAccounts(settled);
        // 末端加工可能改变占容；重新核验实际库存与未到货预约后再入账，失败仍整笔回滚。
        requireIntakeSpace(settled, settledAccounts, definition, incoming.volume());
        AnatomyState.Organ settledOrgan = settledAccounts.organ(organId);
        QiInventory buffer = QiAccounting.credit(settledOrgan.buffer(), incoming);
        settledAccounts.replaceOrgan(new AnatomyState.Organ(organId, settledOrgan.hp(), buffer, settledOrgan.process(), settledOrgan.fatigueJoules()));
        var supplied = new BodySnapshot(settled.schemaVersion(), settled.id(), settled.template(), settled.structure(), settled.realm(),
                settled.partStates(), settled.tissueStates(), settledAccounts.organs(), settled.meridianStates(), settled.transits(),
                settled.vitality(), settled.settledAt(), settled.revision(), settled.actions(), settled.openingTask(), settled.addonProgress());
        supplied = OpeningRuntime.recordIntake(supplied, organId, incoming.totalQi());
        return new BodyTimeAdvancement.Result(supplied, false, advanced.releasedQi());
    }

    private static void requireIntakeSpace(BodySnapshot body, QiAccounts accounts,
                                           Anatomy.OrganDefinition definition, double incomingVolume) {
        // 预约是从在途派生的容量额度，到货后由缓存接替占用，摄入不能挤占任一部分。
        double reservedVolume = 0;
        for (QiTransit transit : body.transits()) {
            Anatomy.Connection route = accounts.connection(transit.connectionId());
            QiAccounts.Endpoint target = accounts.endpoint(route.to());
            if (target != null && target.id().equals(definition.id())) {
                reservedVolume += transit.cargo().volume();
            }
        }
        double freeCapacity = definition.volumeCapacity() - accounts.organ(definition.id()).occupiedVolume() - reservedVolume;
        // 相同批次的体积由二进制浮点数相除得到，边界处允许一个极小舍入尾差。
        if (incomingVolume > freeCapacity + 1e-9) {
            throw MeridianErrors.rejectIntake(FailureCode.CAPACITY_EXCEEDED);
        }
    }
}
