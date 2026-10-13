package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

/** 一个稳定活动区间的通用摄入；产生候选身体，实际环境扣账由事务执行。 */
public final class OrganIntake {
    private OrganIntake() {
        // 摄入按显式区间结算，不创建后台线程或逐帧更新。
    }

    public static BodyTimeAdvancement.Result apply(BodySnapshot body, Anatomy.OrganId organId,
                                                   QiInventory incoming, BodyTimeAdvancement.Plan plan) {
        // 先确认有效活动区间；成功会消费这段身体时间，换请求 ID 不能重复吸收。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        MeridianErrors.required(incoming, Data.QI_REQUIRED);
        MeridianErrors.required(plan, Data.TIME_PLAN_REQUIRED);
        if (incoming.entries().isEmpty()) {
            throw MeridianErrors.invalid(Environment.INTAKE_POSITIVE);
        }
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

        // 区间内部的事件先由宿主拆段；末端到达和加工可随本次一起结算，寿尽必须单独处理。
        var deathBoundary = BodyTimeAdvancement.nextLifespanBoundary(body, plan.biologicalSecondsPerSimulationSecond());
        var eventBoundary = BodyTimeAdvancement.nextEventBoundary(body,
                plan.biologicalSecondsPerSimulationSecond(), plan.mode());
        boolean diesDuringIntake = deathBoundary.isPresent()
                && deathBoundary.get().elapsedMillis() <= end.elapsedMillis();
        boolean crossesEvent = eventBoundary.isPresent()
                && eventBoundary.get().elapsedMillis() < end.elapsedMillis();
        if (diesDuringIntake || crossesEvent) {
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
        var connections = DataChecks.index(body.structure().connections(), Anatomy.Connection::id, "连接");
        double reservedQi = 0;
        for (QiTransit transit : body.transits()) {
            Anatomy.Connection route = connections.get(transit.connectionId());
            QiAccounts.Endpoint target = accounts.endpoint(route.to());
            if (target != null && target.id().equals(organId)) {
                reservedQi += transit.cargo().totalQi();
            }
        }
        AnatomyState.Organ organ = accounts.organ(organId);
        double freeCapacity = definition.qiCapacity() - organ.occupiedQi() - reservedQi;
        if (incoming.totalQi() > freeCapacity) {
            throw MeridianErrors.rejectIntake(FailureCode.CAPACITY_EXCEEDED);
        }

        // 先结算既有事件，再归入本段摄入，避免未来收入改变起点处阻塞任务的结果。
        BodyTimeAdvancement.Result advanced = BodyTimeAdvancement.apply(body, plan);
        BodySnapshot settled = advanced.body();
        var settledAccounts = new QiAccounts(settled);
        AnatomyState.Organ settledOrgan = settledAccounts.organ(organId);
        QiInventory buffer = QiAccounting.credit(settledOrgan.buffer(), incoming);
        settledAccounts.replaceOrgan(new AnatomyState.Organ(organId, settledOrgan.hp(), buffer, settledOrgan.process()));
        var supplied = new BodySnapshot(settled.schemaVersion(), settled.id(), settled.template(), settled.structure(), settled.realm(),
                settled.partStates(), settled.tissueStates(), settledAccounts.organs(), settled.meridianStates(), settled.transits(),
                settled.vitality(), settled.settledAt(), settled.revision());
        return new BodyTimeAdvancement.Result(supplied, false, advanced.releasedQi());
    }
}
