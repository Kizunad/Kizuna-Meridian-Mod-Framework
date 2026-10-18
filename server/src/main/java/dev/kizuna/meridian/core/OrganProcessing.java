package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.CapabilityRejectionReason;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** 器官单批次加工；配方由可信目录选择，完成过程不再调用附属包。 */
public final class OrganProcessing {
    private OrganProcessing() {
        // 仅在启动、完成或恢复事件运行，没有后台轮询线程。
    }

    public static BodySnapshot start(BodySnapshot body, Anatomy.OrganId organId, OrganRecipe recipe) {
        // 1. 检查身体能否接受加工；时间由宿主先结算，本算子不自行读取时钟。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        MeridianErrors.required(recipe, Data.RECIPE_REQUIRED);
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.REVISION_EXHAUSTED);
        }
        // 2. 按实例 ID 找器官，再检查本体和所属部位能否工作。
        Anatomy.OrganDefinition organDefinition = body.structure().organs().stream()
                .filter(organ -> organ.id().equals(organId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.rejectCapability(CapabilityRejectionReason.MODULE_NOT_FOUND));
        var accounts = new QiAccounts(body);
        AnatomyState.Organ organState = accounts.organ(organId);
        if (!accounts.organAvailable(organDefinition)) {
            throw MeridianErrors.rejectCapability(CapabilityRejectionReason.MODULE_UNAVAILABLE);
        }
        if (organState.process() != null) {
            throw MeridianErrors.rejectCapability(CapabilityRejectionReason.ORGAN_BUSY);
        }
        // 3. 配方类型与境界都必须符合；已有任务不能因新请求被覆盖。
        boolean organKindMatches = recipe.organKind().equals(organDefinition.kind());
        boolean realmMeetsMinimum = body.realm().capacityRatio() >= recipe.minimumRealm().capacityRatio();
        if (!organKindMatches || !realmMeetsMinimum) {
            throw MeridianErrors.rejectCapability(CapabilityRejectionReason.RECIPE_INCOMPATIBLE);
        }
        long startMillis = body.settledAt().elapsedMillis();
        long remainingClockMillis = Long.MAX_VALUE - startMillis;
        if (recipe.durationMillis() > remainingClockMillis) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
        }

        // 4. 输入从缓存搬入唯一加工账户；预计输出尚不存在，不能同时计入余额。
        QiInventory remainingBuffer = QiAccounting.debit(organState.buffer(), recipe.input());
        long completionMillis = startMillis + recipe.durationMillis();
        var process = new OrganProcess(recipe, completionMillis, false);
        var processingOrgan = new AnatomyState.Organ(organId, organState.hp(), remainingBuffer, process, organState.fatigueJoules());
        accounts.replaceOrgan(processingOrgan);

        // 5. 一次启动只生成一份候选身体；实际发布仍由宿主事务决定。
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), accounts.organs(), body.meridianStates(), body.transits(),
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask());
    }

    static QiInventory completeDue(BodySnapshot body, QiAccounts accounts, long atMillis) {
        // 仅在事件边界检查到期任务；失能只阻塞，恢复后可在同毫秒继续结算。
        QiInventory releasedQi = QiInventory.empty();
        for (Anatomy.OrganDefinition organDefinition : body.structure().organs()) {
            AnatomyState.Organ organState = accounts.organ(organDefinition.id());
            OrganProcess process = organState.process();
            if (process == null || process.completionMillis() > atMillis) {
                continue;
            }
            if (!accounts.organAvailable(organDefinition)) {
                // 到期但失能时保留原料，只修改阻塞标记，不能当成已经完成。
                var blockedProcess = new OrganProcess(process.recipe(), process.completionMillis(), true);
                var blockedOrgan = new AnatomyState.Organ(organState.id(), organState.hp(), organState.buffer(), blockedProcess, organState.fatigueJoules());
                accounts.replaceOrgan(blockedOrgan);
                continue;
            }

            // 原料在此刻转换为产物和环境损耗，两者总量不得超过原料，不提前发放预计产物。
            OrganRecipe recipe = process.recipe();
            QiInventory outputBuffer = QiAccounting.credit(organState.buffer(), recipe.output());
            double inputQi = recipe.input().totalQi();
            double outputQi = recipe.output().totalQi();
            double conversionLossQi = inputQi - outputQi;
            if (conversionLossQi > 0) {
                var wasteQi = new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, conversionLossQi);
                var wasteInventory = new QiInventory(List.of(wasteQi));
                releasedQi = QiAccounting.credit(releasedQi, wasteInventory);
            }

            // 清除加工记录才算完成；下一笔请求不能再兑现同一份输入。
            var completedOrgan = new AnatomyState.Organ(organState.id(), organState.hp(), outputBuffer, null, organState.fatigueJoules());
            accounts.replaceOrgan(completedOrgan);
        }
        return releasedQi;
    }

    public static Optional<TimeCursor> nextCompletionBoundary(BodySnapshot body) {
        // 已阻塞的任务等状态变化再试，不安排不断唤醒的过去时刻。
        if (body.vitality().dead()) {
            return Optional.empty();
        }
        OptionalLong nextCompletionMillis = body.organStates().stream()
                .map(AnatomyState.Organ::process)
                .filter(process -> process != null && !process.blocked())
                .mapToLong(OrganProcess::completionMillis)
                .min();
        if (nextCompletionMillis.isEmpty()) {
            return Optional.empty();
        }

        TimeCursor settledAt = body.settledAt();
        var completionAt = new TimeCursor(settledAt.domain(), settledAt.epoch(), nextCompletionMillis.getAsLong());
        return Optional.of(completionAt);
    }
}
