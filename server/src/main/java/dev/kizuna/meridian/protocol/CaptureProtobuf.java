package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;

/** 权威接触摄入的消息校验；普通玩家输入不包含命中和采样量。 */
public final class CaptureProtobuf {
    private CaptureProtobuf() {
        // 这里只检查契约，实际浓度与可用空间在锁定账户后计算。
    }

    public static void validateRequest(CaptureQi request) {
        // 配方与采样体积明确，避免零剂量生成成功版本或非法浮点进入账户。
        ProtoWire.validate(request);
        new Anatomy.OrganId(request.getOrganId());
        new DefinitionId(request.getRecipeId());
        ProtoWire.require(request.getRecipeVersion() > 0 && request.getSampleVolumeBlocks() > 0,
                MeridianErrors.INVALID_QI_CAPTURE);
    }

    public static void validateResult(BodyCommand command, QiCaptureResult result) {
        // 回执绑定同一配方、身体版本和瞬时交换；事务还会从旧账户完整复算。
        ProtoWire.validate(result);
        validateRequest(command.getCaptureQi());
        var recipe = CapabilityProtobuf.fromRecipe(result.getRecipe());
        EnvironmentProtobuf.validateReceipt(result.getEnvironment());
        var exchange = result.getEnvironment().getExchange();
        var request = command.getCaptureQi();
        ProtoWire.require(recipe.id().value().equals(request.getRecipeId())
                && recipe.version() == request.getRecipeVersion() && recipe.input().entries().size() == 1
                && recipe.output().totalQi() > 0 && exchange.getContactCapture()
                && exchange.getOffer().getBodyId().equals(command.getBodyId())
                && exchange.getExpectedBodyRevision() == command.getExpectedRevision()
                && exchange.getExchangeId().equals("capture-" + (command.getExpectedRevision() + 1)),
                MeridianErrors.QI_CAPTURE_FACT);
    }
}
