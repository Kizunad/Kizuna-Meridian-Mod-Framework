package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.OrganIntake;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.CapabilityProtobuf;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.IntakeProtobuf;
import dev.kizuna.meridian.protocol.TimeProtobuf;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 组合纯算子产生身体、环境和回执候选；处理器与事务最终校验使用同一路径。 */
final class OrganIntakeExecution {
    private OrganIntakeExecution() {
        // 事务适配器负责发布，本类计算到任一步失败都没有外部副作用。
    }

    record Result(BodySnapshot body, EnvironmentQiSource source, OrganIntakeResult fact) { }

    static Result calculate(BodySnapshot body, IntakeQi request, TimeAdvancePlan plan, EnvironmentQiSource source) {
        // 1. 先校验原始时间事实和全部负载；本段实际做功的组织不同时享受休息恢复。
        IntakeProtobuf.validateRequest(request);
        BodyTimeAdvancement.Plan trustedPlan = TimeProtobuf.fromMessage(plan);
        List<TissueCapabilities.Exertion> loads = request.getLoadsList().stream()
                .map(CapabilityProtobuf::fromExertion).toList();
        Set<Anatomy.TissueId> workingIds = loads.stream()
                .map(TissueCapabilities.Exertion::tissueId).collect(Collectors.toSet());

        // 2. 摄入消费尚未结算的区间；无负载的休息组织随同时间推进恢复。
        var result = OrganIntake.apply(body, new Anatomy.OrganId(request.getOrganId()),
                BodyProtobuf.fromInventory(request.getQi()), trustedPlan, workingIds);
        BodySnapshot updatedBody = result.body();
        if (!loads.isEmpty()) {
            // 负载按区间起点能力校验；合并时只替换实际做功组织，保留其他组织刚完成的恢复。
            BodySnapshot exerted = TissueCapabilities.exertAll(body, loads);
            Map<Anatomy.TissueId, AnatomyState.Tissue> exertedStates = exerted.tissueStates().stream()
                    .collect(Collectors.toMap(tissue -> tissue.id(), tissue -> tissue));
            var combinedTissues = new ArrayList<>(updatedBody.tissueStates());
            for (int index = 0; index < combinedTissues.size(); index++) {
                Anatomy.TissueId tissueId = combinedTissues.get(index).id();
                if (workingIds.contains(tissueId)) {
                    // 时间推进已扣开脉费用；本次吐纳只叠加自己的增量，不能覆盖先前疲劳。
                    AnatomyState.Tissue initial = body.tissueStates().get(index);
                    AnatomyState.Tissue settled = combinedTissues.get(index);
                    double additionalFatigue = exertedStates.get(tissueId).fatigue() - initial.fatigue();
                    double combinedFatigue = settled.fatigue() + additionalFatigue;
                    double fatigueLimit = body.structure().tissues().stream().filter(item -> item.id().equals(tissueId))
                            .findFirst().orElseThrow().fatigueCapacity();
                    if (combinedFatigue > fatigueLimit) {
                        throw MeridianErrors.rejectIntake(FailureCode.TISSUE_LOAD_EXCEEDED);
                    }
                    combinedTissues.set(index, new AnatomyState.Tissue(tissueId, settled.hp(), combinedFatigue));
                }
            }
            updatedBody = new BodySnapshot(updatedBody.schemaVersion(), updatedBody.id(), updatedBody.template(),
                    updatedBody.structure(), updatedBody.realm(), updatedBody.partStates(), combinedTissues,
                    updatedBody.organStates(), updatedBody.meridianStates(), updatedBody.transits(), updatedBody.vitality(),
                    updatedBody.settledAt(), updatedBody.revision(), updatedBody.actions(), updatedBody.openingTask());
        }
        var offer = EnvironmentAbsorption.offer(source, body.id().value(), BodyProtobuf.toTime(body.settledAt()), plan.getTarget());
        var exchange = EnvironmentQiExchange.newBuilder().setExchangeId("intake-" + result.body().revision())
                .setOffer(offer).setExpectedBodyRevision(body.revision())
                .setStartedAt(BodyProtobuf.toTime(body.settledAt())).setEndedAt(plan.getTarget())
                .setAbsorbedQi(request.getQi()).setReleasedQi(BodyProtobuf.toInventory(result.releasedQi())).build();

        // 环境按毛额扣款并占用共享速率；损耗只在这一条来源入账，不再发给默认释放账户。
        EnvironmentQiSource updatedSource = EnvironmentAbsorption.apply(source, exchange);
        var receipt = EnvironmentQiReceipt.newBuilder().setExchange(exchange)
                .setBodyRevision(result.body().revision()).setSourceRevision(updatedSource.getRevision()).build();
        // 回执保存可信时间；本段做功组织从原请求的 loads 复算，动作来自原身体。
        var fact = OrganIntakeResult.newBuilder().setPlan(plan).setEnvironment(receipt).build();
        return new Result(updatedBody, updatedSource, fact);
    }
}
