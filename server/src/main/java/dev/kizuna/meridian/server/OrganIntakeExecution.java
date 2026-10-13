package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.OrganIntake;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.CapabilityProtobuf;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.IntakeProtobuf;
import dev.kizuna.meridian.protocol.TimeProtobuf;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;

/** 组合纯算子产生身体、环境和回执候选；处理器与事务最终校验使用同一路径。 */
final class OrganIntakeExecution {
    private OrganIntakeExecution() {
        // 事务适配器负责发布，本类计算到任一步失败都没有外部副作用。
    }

    record Result(BodySnapshot body, EnvironmentQiSource source, OrganIntakeResult fact) { }

    static Result calculate(BodySnapshot body, IntakeQi request, TimeAdvancePlan plan, EnvironmentQiSource source) {
        // 身体算子消费尚未结算的区间，并处理区间末端既有加工的损耗。
        IntakeProtobuf.validateRequest(request);
        var result = OrganIntake.apply(body, new Anatomy.OrganId(request.getOrganId()),
                BodyProtobuf.fromInventory(request.getQi()), TimeProtobuf.fromMessage(plan));
        BodySnapshot updatedBody = result.body();
        if (request.getLoadsCount() > 0) {
            // 在同一原身体上验算全部代价，合并到摄入候选；事务仍只递增一次版本。
            var loads = request.getLoadsList().stream().map(CapabilityProtobuf::fromExertion).toList();
            BodySnapshot exerted = TissueCapabilities.exertAll(body, loads);
            updatedBody = new BodySnapshot(updatedBody.schemaVersion(), updatedBody.id(), updatedBody.template(),
                    updatedBody.structure(), updatedBody.realm(), updatedBody.partStates(), exerted.tissueStates(),
                    updatedBody.organStates(), updatedBody.meridianStates(), updatedBody.transits(), updatedBody.vitality(),
                    updatedBody.settledAt(), updatedBody.revision());
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
        var fact = OrganIntakeResult.newBuilder().setPlan(plan).setEnvironment(receipt).build();
        return new Result(updatedBody, updatedSource, fact);
    }
}
