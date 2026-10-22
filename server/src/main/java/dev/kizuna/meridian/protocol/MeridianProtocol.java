package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Protocol;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

/** 宿主无关的 Protobuf 请求/响应边界；不注册网络通道，也不授予读取权限。 */
public final class MeridianProtocol {
    public static final int VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    public static final int MAX_RECURSION_DEPTH = 32;
    public static final int MAX_LIST_ENTRIES = 4096;
    public static final int MAX_FIELD_VALUES = 65536;
    public static final int MAX_TEXT_BYTES = 1024;

    private MeridianProtocol() {
        // 静态编解码不持有连接或会话，身份与权限由宿主绑定。
    }

    public static MeridianMessages.RequestEnvelope decodeRequest(byte[] bytes) {
        // 请求解析入口只接受请求类型，响应快照不能被当成客户端写入指令。
        var request = ProtoWire.read(bytes, MeridianMessages.RequestEnvelope.parser());
        validateRequest(request);
        return request;
    }

    public static byte[] encodeRequest(MeridianMessages.RequestEnvelope request) {
        // 本地构造的生成消息也必须满足相同的版本与意图校验。
        validateRequest(request);
        return ProtoWire.write(request);
    }

    public static MeridianMessages.ResponseEnvelope decodeResponse(byte[] bytes) {
        // 响应携带的数据需要经过核心校验，不能只验证外层信封。
        var response = ProtoWire.read(bytes, MeridianMessages.ResponseEnvelope.parser());
        validateResponse(response);
        return response;
    }

    public static byte[] encodeResponse(MeridianMessages.ResponseEnvelope response) {
        // 发送前验证快照或失败类别，禁止发送未经校验的半份身体。
        validateResponse(response);
        return ProtoWire.write(response);
    }

    public static byte[] encodeReceipt(MeridianMessages.CommandReceipt receipt) {
        // 回执供宿主持久化；不得把这条内部编解码入口注册成客户端命令。
        validateReceipt(receipt);
        return ProtoWire.write(receipt);
    }

    public static MeridianMessages.CommandReceipt decodeReceipt(byte[] bytes) {
        // 恢复去重记录时重新校验命令与提交版本的关联，不能只检查 Protobuf 格式。
        var receipt = ProtoWire.read(bytes, MeridianMessages.CommandReceipt.parser());
        validateReceipt(receipt);
        return receipt;
    }

    public static void validateReceipt(MeridianMessages.CommandReceipt receipt) {
        // 原请求与结果必须描述同一具身体和同一次变更，防止存储串号。
        ProtoWire.validate(receipt);
        validateCommand(receipt.getCommand());
        validateCommitted(receipt.getCommitted());
        ProtoWire.require(receipt.getCommand().getBodyId().equals(receipt.getCommitted().getBodyId())
                        && receipt.getCommand().getExpectedRevision() == receipt.getCommitted().getPreviousRevision(),
                Protocol.RECEIPT_MISMATCH);
        ProtoWire.require(receipt.hasTimeAdvance() == receipt.getCommand().hasAdvanceTime(),
                Protocol.TIME_RECEIPT_MISMATCH);
        if (receipt.hasTimeAdvance()) {
            // 成功回执保存已采用的时间和死亡释放，重试不能改用新的宿主时间。
            TimeProtobuf.validateResult(receipt.getTimeAdvance());
        }
        // 发起加工必须保存当时解析的完整定义；其他命令不能夹带可兑现的配方事实。
        ProtoWire.require(receipt.hasOrganRecipe() == receipt.getCommand().hasStartOrganRecipe(),
                Protocol.RECIPE_RECEIPT_MISMATCH);
        if (receipt.hasOrganRecipe()) {
            var recipe = CapabilityProtobuf.fromRecipe(receipt.getOrganRecipe());
            var requested = receipt.getCommand().getStartOrganRecipe();
            ProtoWire.require(recipe.id().value().equals(requested.getRecipeId()) && recipe.version() == requested.getRecipeVersion(),
                    Protocol.RECIPE_RECEIPT_MISMATCH);
        }
        // 摄入时间与环境回执属于同一次提交，其他命令不能夹带环境扣账事实。
        ProtoWire.require(receipt.hasOrganIntake() == receipt.getCommand().hasIntakeQi(),
                MeridianErrors.Environment.INTAKE_FACT);
        if (receipt.hasOrganIntake()) {
            IntakeProtobuf.validateResult(receipt.getCommand(), receipt.getOrganIntake());
        }
        ProtoWire.require(receipt.hasQiCapture() == receipt.getCommand().hasCaptureQi(), MeridianErrors.QI_CAPTURE_FACT);
        if (receipt.hasQiCapture()) {
            CaptureProtobuf.validateResult(receipt.getCommand(), receipt.getQiCapture());
        }
        // 健康操作只有可信宿主能发起；保存固定结果后才可在不重调 Addon 的情况下恢复。
        ProtoWire.require(receipt.hasHealth() == HealthProtobuf.isHealthCommand(receipt.getCommand()), MeridianErrors.Health.RECEIPT);
        if (receipt.hasHealth()) {
            HealthProtobuf.validateResult(receipt.getCommand(), receipt.getHealth());
        }
        // 启动动作固定所用定义；结束动作只需实例 ID，不携带另一份占用事实。
        ProtoWire.require(receipt.hasActionDefinition() == receipt.getCommand().hasStartAction(),
                MeridianErrors.Data.ACTION_RECEIPT_MISMATCH);
        if (receipt.hasActionDefinition()) {
            var definition = ActionProtobuf.fromDefinition(receipt.getActionDefinition());
            var requested = receipt.getCommand().getStartAction();
            ProtoWire.require(definition.id().value().equals(requested.getActionId())
                            && definition.version() == requested.getActionVersion(), MeridianErrors.Data.ACTION_RECEIPT_MISMATCH);
        }
        // 开脉开始/暂停固定规则、动作和释放，不能夹带在其他类型回执中。
        boolean openingCommand = receipt.getCommand().hasStartMeridianOpening() || receipt.getCommand().hasPauseMeridianOpening();
        ProtoWire.require(receipt.hasOpening() == openingCommand, MeridianErrors.Opening.RECEIPT);
        if (openingCommand) {
            var fact = receipt.getOpening();
            var rule = OpeningProtobuf.fromRule(fact.getRule());
            dev.kizuna.meridian.core.OpeningRuntime.requireAction(rule, ActionProtobuf.fromDefinition(fact.getAction()), null);
            BodyProtobuf.fromInventory(fact.getReleasedQi());
            if (receipt.getCommand().hasStartMeridianOpening()) {
                var requested = receipt.getCommand().getStartMeridianOpening();
                ProtoWire.require(rule.id().value().equals(requested.getRuleId()) && rule.version() == requested.getRuleVersion()
                        && fact.getReleasedQi().getEntriesCount() == 0, MeridianErrors.Opening.RECEIPT);
            } else {
                ProtoWire.require(rule.targetMeridianId().value().equals(receipt.getCommand().getPauseMeridianOpening().getMeridianId()),
                        MeridianErrors.Opening.RECEIPT);
            }
        }
    }

    private static void validateRequest(MeridianMessages.RequestEnvelope request) {
        // 只开放已经有执行语义的意图，不提供任意快照覆盖或通用载荷逃生口。
        ProtoWire.validate(request);
        ProtoWire.require(request.getProtocolVersion() == VERSION, Protocol.REQUEST_VERSION);
        switch (request.getRequestCase()) {
            case GET_BODY_SNAPSHOT -> new BodyId(request.getGetBodySnapshot().getBodyId());
            case GET_TISSUE_CAPABILITY -> {
                // 查询定位实例组织，不通过客户端提供的类型寻找替代模块。
                new BodyId(request.getGetTissueCapability().getBodyId());
                new Anatomy.TissueId(request.getGetTissueCapability().getTissueId());
            }
            case BODY_COMMAND -> validateCommand(request.getBodyCommand());
            case REQUEST_NOT_SET -> throw MeridianErrors.invalid(Protocol.MISSING_REQUEST);
        }
    }

    private static void validateResponse(MeridianMessages.ResponseEnvelope response) {
        // request_id 仅用于响应关联；有权读取哪个身体必须由宿主在生成响应前检查。
        ProtoWire.validate(response);
        ProtoWire.require(response.getProtocolVersion() == VERSION, Protocol.RESPONSE_VERSION);
        switch (response.getResultCase()) {
            case BODY_SNAPSHOT -> BodyProtobuf.fromMessage(response.getBodySnapshot());
            case COMMAND_COMMITTED -> validateCommitted(response.getCommandCommitted());
            case TISSUE_CAPABILITY -> {
                // 派生读数仍需合法身份、版本和非负能力值。
                new BodyId(response.getTissueCapability().getBodyId());
                new Anatomy.TissueId(response.getTissueCapability().getTissueId());
                CapabilityProtobuf.fromCapacity(response.getTissueCapability().getAvailable());
            }
            case FAILURE -> {
                // 失败码存在性及未知枚举已由公共边界校验，载荷不含私有策略详情。
            }
            case RESULT_NOT_SET -> throw MeridianErrors.invalid(Protocol.MISSING_RESULT);
        }
    }

    private static void validateCommand(MeridianMessages.BodyCommand command) {
        // 存在性、有限数和非负 revision 已由公共遍历校验，额外约束实际操作的剂量。
        new BodyId(command.getBodyId());
        switch (command.getOperationCase()) {
            case EXECUTE_ACTIVITY -> dev.kizuna.meridian.server.BodyActivityExecution.validate(command.getExecuteActivity());
            case SPEND_LIFESPAN -> ProtoWire.require(command.getSpendLifespan().getSeconds() > 0,
                    Protocol.LIFESPAN_POSITIVE);
            case ADVANCE_TIME -> {
                // 请求没有时间参数，实际推进事实只能由宿主时间源在事务内提供。
            }
            case TRANSPORT_QI -> TransportProtobuf.fromMessage(command.getTransportQi());
            case START_ORGAN_RECIPE -> {
                // 请求不包含定义；未知但格式正确的 ID 由宿主目录返回业务拒绝。
                var request = command.getStartOrganRecipe();
                new Anatomy.OrganId(request.getOrganId());
                new DefinitionId(request.getRecipeId());
                ProtoWire.require(request.getRecipeVersion() > 0, Protocol.RECIPE_RECEIPT_MISMATCH);
            }
            case EXERT_TISSUE -> CapabilityProtobuf.fromExertion(command.getExertTissue());
            case EXERT_TISSUES -> {
                var loads = command.getExertTissues().getLoadsList();
                ProtoWire.require(!loads.isEmpty() && loads.size() <= 32, MeridianErrors.INVALID_TISSUE_LOADS);
                loads.forEach(CapabilityProtobuf::fromExertion);
            }
            case INTAKE_QI -> IntakeProtobuf.validateRequest(command.getIntakeQi());
            case CAPTURE_QI -> CaptureProtobuf.validateRequest(command.getCaptureQi());
            case START_ACTION -> ActionProtobuf.validateStart(command.getStartAction());
            case STOP_ACTION -> ActionProtobuf.validateInstanceId(command.getStopAction().getInstanceId());
            case START_MERIDIAN_OPENING -> {
                // 开脉请求不接收自报库存、时钟或成功进度。
                new DefinitionId(command.getStartMeridianOpening().getRuleId());
                ProtoWire.require(command.getStartMeridianOpening().getRuleVersion() > 0, MeridianErrors.Opening.VERSION);
            }
            case PAUSE_MERIDIAN_OPENING -> new Anatomy.MeridianId(command.getPauseMeridianOpening().getMeridianId());
            case DEBUG_QI -> dev.kizuna.meridian.core.QiDebugging.validate(command.getDebugQi());
            case DAMAGE_BODY, HEAL_BODY, RESTORE_FATIGUE, CONFIRM_BODY_DEATH, SET_PART_HEALTH_LIMITS -> HealthProtobuf.validateCommand(command);
            case SET_ADDON_PROGRESS -> dev.kizuna.meridian.core.AddonProgress.validateEntry(
                    command.getSetAddonProgress().getId(), command.getSetAddonProgress().getProgress());
            case OPERATION_NOT_SET -> throw MeridianErrors.invalid(Protocol.MISSING_COMMAND);
        }
    }

    private static void validateCommitted(MeridianMessages.CommandCommitted committed) {
        // 显式检查溢出边界；不能让最大版本绕回零，造成旧请求重新可用。
        new BodyId(committed.getBodyId());
        ProtoWire.require(committed.getPreviousRevision() < Long.MAX_VALUE
                        && committed.getRevision() == committed.getPreviousRevision() + 1,
                Protocol.COMMIT_REVISION);
    }
}
