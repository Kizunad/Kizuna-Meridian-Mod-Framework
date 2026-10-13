package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

/** 摄入意图与已采用事实的校验；可信来源选择仍属于宿主。 */
public final class IntakeProtobuf {
    private IntakeProtobuf() {
        // 只核验消息关系，不在解码中执行器官或环境算子。
    }

    public static void validateRequest(MeridianMessages.IntakeQi request) {
        // 剂量必须明确且为正，空账户不能消费时间或生成成功回执。
        ProtoWire.validate(request);
        new Anatomy.OrganId(request.getOrganId());
        ProtoWire.require(!BodyProtobuf.fromInventory(request.getQi()).entries().isEmpty(), Environment.INTAKE_POSITIVE);
        ProtoWire.require(request.getLoadsCount() <= 32, dev.kizuna.meridian.error.MeridianErrors.INVALID_TISSUE_LOADS);
        request.getLoadsList().forEach(CapabilityProtobuf::fromExertion);
    }

    public static void validateResult(MeridianMessages.BodyCommand command, MeridianMessages.OrganIntakeResult result) {
        // 回执必须描述同一剂量、同一身体版本和同一可信时间，不能偷换环境转账。
        ProtoWire.validate(result);
        validateRequest(command.getIntakeQi());
        var plan = TimeProtobuf.fromMessage(result.getPlan());
        EnvironmentProtobuf.validateReceipt(result.getEnvironment());
        var exchange = result.getEnvironment().getExchange();
        ProtoWire.require(plan.mode() == BodyTimeAdvancement.Mode.ACTIVE
                && result.getPlan().getTarget().equals(exchange.getEndedAt())
                && command.getBodyId().equals(exchange.getOffer().getBodyId())
                && command.getExpectedRevision() == exchange.getExpectedBodyRevision()
                && command.getIntakeQi().getQi().equals(exchange.getAbsorbedQi())
                && exchange.getExchangeId().equals("intake-" + (command.getExpectedRevision() + 1)), Environment.INTAKE_FACT);
    }
}
