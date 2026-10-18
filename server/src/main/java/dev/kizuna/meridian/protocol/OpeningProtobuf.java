package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.MeridianOpeningRule;
import dev.kizuna.meridian.core.OpeningTask;
import dev.kizuna.meridian.error.MeridianErrors.Opening;
import dev.kizuna.meridian.protocol.pb.OpeningMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages;

/** 开拓规则与意图的有界编解码，不执行身体状态变更。 */
public final class OpeningProtobuf {
    private OpeningProtobuf() {
        // 内容定义与玩家意图分别校验，客户端不能通过请求上传执行参数。
    }

    public static MeridianOpeningRule fromRule(BodyMessages.MeridianOpeningRule message) {
        // 通用边界先拒绝未知字段和超限输入，再校验局部身份及规则语义。
        ProtoWire.validate(message);

        return new MeridianOpeningRule(new DefinitionId(message.getId()), message.getVersion(), message.getDisplayName(),
                new Anatomy.MeridianId(message.getTargetMeridianId()),
                BodyProtobuf.fromInventory(message.getMinimumLumenQi()),
                new DefinitionId(message.getActionId()), message.getActionVersion(),
                message.getEffortsList().stream().map(OpeningProtobuf::fromEffort).toList(),
                message.getContinuousHold(),
                message.getLumenCapacityGain(), message.getGuidanceQiPerSecond(), message.getGuidanceBatchQi(),
                message.getHoldQiPerSecond(), message.getGuidanceBufferQi(),
                new DefinitionId(message.getPolicyId()), message.getPolicyConfig());
    }

    public static BodyMessages.MeridianOpeningRule toRule(MeridianOpeningRule rule) {
        // 映射内容参数，门槛明细只表达要求，不创建新账户。
        var builder = BodyMessages.MeridianOpeningRule.newBuilder()
                .setId(rule.id().value()).setVersion(rule.version()).setDisplayName(rule.displayName())
                .setTargetMeridianId(rule.targetMeridianId().value())
                .setMinimumLumenQi(BodyProtobuf.toInventory(rule.minimumLumenQi()))
                .setActionId(rule.actionId().value()).setActionVersion(rule.actionVersion())
                .setContinuousHold(rule.continuousHold())
                .setLumenCapacityGain(rule.lumenCapacityGain()).setGuidanceQiPerSecond(rule.guidanceQiPerSecond())
                .setGuidanceBatchQi(rule.guidanceBatchQi())
                .setHoldQiPerSecond(rule.holdQiPerSecond())
                .setGuidanceBufferQi(rule.guidanceBufferQi())
                .setPolicyId(rule.policyId().value())
                .setPolicyConfig(rule.policyConfig())
                .addAllEfforts(rule.efforts().stream().map(OpeningProtobuf::toEffort).toList());
        var message = builder.build();
        ProtoWire.validate(message);
        return message;
    }

    public static byte[] encodeRule(BodyMessages.MeridianOpeningRule rule) {
        // 独立内容资源与整包注册采用同一校验，防止入口之间宽严不一致。
        fromRule(rule);
        return ProtoWire.write(rule);
    }

    public static BodyMessages.MeridianOpeningRule decodeRule(byte[] payload) {
        // 有界解析后检查业务含义，生成对象本身不代表合法开拓规则。
        var rule = ProtoWire.read(payload, BodyMessages.MeridianOpeningRule.parser());
        fromRule(rule);
        return rule;
    }

    public static byte[] encodeRequest(OpeningMessages.MeridianOpeningRequest request) {
        // 发起者只能选择规则或目标；时间、储量与进度由权威执行入口读取。
        validateRequest(request);
        return ProtoWire.write(request);
    }

    public static OpeningMessages.MeridianOpeningRequest decodeRequest(byte[] payload) {
        // 缺少操作或身体版本会在通用边界被拒绝，0 版本仍需显式提供。
        var request = ProtoWire.read(payload, OpeningMessages.MeridianOpeningRequest.parser());
        validateRequest(request);
        return request;
    }

    public static void validateRequest(OpeningMessages.MeridianOpeningRequest request) {
        // 这里只验证格式；身份授权、知识、身体条件与事务去重属于执行入口。
        ProtoWire.validate(request);
        ProtoWire.require(request.getProtocolVersion() == MeridianProtocol.VERSION, Opening.PROTOCOL_VERSION);
        new BodyId(request.getBodyId());
        if (request.hasStart()) {
            new DefinitionId(request.getStart().getRuleId());
            ProtoWire.require(request.getStart().getRuleVersion() > 0, Opening.VERSION);
        } else if (request.hasPause()) {
            new Anatomy.MeridianId(request.getPause().getMeridianId());
        }
    }

    public static OpeningTask fromTask(BodyMessages.OpeningTask message) {
        // 恢复任务只读取固定事实；身体关联和临时超压上限由 BodySnapshot 进一步检查。
        return new OpeningTask(fromRule(message.getRule()), message.getActionInstanceId(),
                message.getParticipantsList().stream().map(OpeningProtobuf::fromLimit).toList(),
                message.getRequiredMillis(), message.getTargetStableCapacity(), message.getStartedMillis(),
                message.getHeldMillis(), OpeningTask.Status.valueOf(message.getStatus().name().substring("OPENING_".length())),
                message.getIntakeSamplesList().stream().map(sample -> new OpeningTask.IntakeSample(
                        new Anatomy.OrganId(sample.getOrganId()), sample.getAtMillis(), sample.getQi())).toList(),
                message.getObservedOrganIdsList().stream().map(Anatomy.OrganId::new).toList(), message.getReceiptWindowMillis());
    }

    public static BodyMessages.OpeningTask toTask(OpeningTask task) {
        // 摄入证据保存相同模拟时钟上的时间点，不附带另一份可花费的真元。
        return BodyMessages.OpeningTask.newBuilder().setRule(toRule(task.rule()))
                .setActionInstanceId(task.actionInstanceId())
                .addAllParticipants(task.participants().stream().map(OpeningProtobuf::toLimit).toList())
                .setRequiredMillis(task.requiredMillis()).setTargetStableCapacity(task.targetStableCapacity())
                .setStartedMillis(task.startedMillis()).setHeldMillis(task.heldMillis())
                .addAllObservedOrganIds(task.observedOrganIds().stream().map(Anatomy.OrganId::value).toList())
                .setReceiptWindowMillis(task.receiptWindowMillis())
                .setStatus(BodyMessages.OpeningTaskStatus.valueOf("OPENING_" + task.status().name()))
                .addAllIntakeSamples(task.intakeSamples().stream().map(sample -> BodyMessages.OpeningIntakeSample.newBuilder()
                        .setOrganId(sample.organId().value()).setAtMillis(sample.atMillis()).setQi(sample.qi()).build()).toList())
                .build();
    }

    public static OpeningPolicyMessages.OpeningPolicyRequest decodePolicyRequest(byte[] payload) {
        // 策略只接收可信调用方的完整快照；读取请求不递归执行规则或推进身体。
        var request = ProtoWire.read(payload, OpeningPolicyMessages.OpeningPolicyRequest.parser());
        ProtoWire.require(request.getProtocolVersion() == MeridianProtocol.VERSION && request.hasBody()
                && request.hasRule() && request.getIntervalMillis() >= 0
                && request.getPhase() != OpeningPolicyMessages.OpeningPolicyPhase.OPENING_POLICY_PHASE_UNSPECIFIED,
                Opening.POLICY_INVALID);
        return request;
    }

    public static OpeningPolicyMessages.OpeningPolicyDecision decodePolicyDecision(byte[] payload) {
        // 附属不能返回无限量、非法延迟或损坏消息，让权威事件循环失去边界。
        var decision = ProtoWire.read(payload, OpeningPolicyMessages.OpeningPolicyDecision.parser());
        ProtoWire.require(decision.getProtocolVersion() == MeridianProtocol.VERSION
                && (!decision.hasNextCheckDelayMillis() || decision.getNextCheckDelayMillis() > 0)
                && decision.getReceiptWindowMillis() >= 0, Opening.POLICY_INVALID);
        return decision;
    }

    private static MeridianOpeningRule.PressureLimit fromLimit(BodyMessages.OpeningPressureLimit value) {
        // 额度明细绑定经脉实例。
        return new MeridianOpeningRule.PressureLimit(new Anatomy.MeridianId(value.getMeridianId()), value.getQi());
    }

    private static BodyMessages.OpeningPressureLimit toLimit(MeridianOpeningRule.PressureLimit value) {
        // 临时额度与真实余额分开编码。
        return BodyMessages.OpeningPressureLimit.newBuilder().setMeridianId(value.meridianId().value()).setQi(value.qi()).build();
    }

    private static MeridianOpeningRule.Effort fromEffort(BodyMessages.OpeningEffort value) {
        // oneof 保留器官与组织的身份空间。
        Object id = value.hasOrganId() ? new Anatomy.OrganId(value.getOrganId()) : new Anatomy.TissueId(value.getTissueId());
        return new MeridianOpeningRule.Effort(id, value.getWatts());
    }

    private static BodyMessages.OpeningEffort toEffort(MeridianOpeningRule.Effort value) {
        // 功率只出现一次，每个独立预算使用自己的模块 ID。
        var builder = BodyMessages.OpeningEffort.newBuilder().setWatts(value.watts());
        if (value.moduleId() instanceof Anatomy.OrganId organ) {
            builder.setOrganId(organ.value());
        } else {
            builder.setTissueId(((Anatomy.TissueId) value.moduleId()).value());
        }
        return builder.build();
    }

}
