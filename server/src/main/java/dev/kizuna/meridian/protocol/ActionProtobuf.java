package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodyAction;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

/** 动作内容、实例和启动意图复用同一 Protobuf 边界。 */
public final class ActionProtobuf {
    private ActionProtobuf() {
        // 只映射受校验值，不从客户端消息登记动作定义。
    }

    public static BodyAction.Definition fromDefinition(BodyMessages.ActionDefinition message) {
        // 名称与版本来自内容目录，局部模块身份转换时同时检查格式。
        ProtoWire.validate(message);
        return new BodyAction.Definition(new DefinitionId(message.getId()), message.getVersion(), message.getDisplayName(),
                message.getTissueIdsList().stream().map(Anatomy.TissueId::new).toList(),
                message.getOrganIdsList().stream().map(Anatomy.OrganId::new).toList());
    }

    public static BodyMessages.ActionDefinition toDefinition(BodyAction.Definition definition) {
        // 固定完整占用定义，存档恢复不依赖当前安装目录或语言实现。
        var message = BodyMessages.ActionDefinition.newBuilder().setId(definition.id().value())
                .setVersion(definition.version()).setDisplayName(definition.displayName())
                .addAllTissueIds(definition.tissueIds().stream().map(Anatomy.TissueId::value).toList())
                .addAllOrganIds(definition.organIds().stream().map(Anatomy.OrganId::value).toList()).build();
        ProtoWire.validate(message);
        return message;
    }

    public static BodyAction.Active fromActive(BodyMessages.ActiveAction message) {
        // 开始时刻与身体游标的关系由完整快照校验。
        ProtoWire.validate(message);
        return new BodyAction.Active(message.getInstanceId(), fromDefinition(message.getDefinition()), message.getStartedMillis());
    }

    public static BodyMessages.ActiveAction toActive(BodyAction.Active action) {
        // 实例身份独立于内容身份，允许同一定义存在多个占用来源。
        return BodyMessages.ActiveAction.newBuilder().setInstanceId(action.instanceId())
                .setDefinition(toDefinition(action.definition())).setStartedMillis(action.startedMillis()).build();
    }

    public static void validateStart(MeridianMessages.StartAction request) {
        // 启动只能引用已安装的精确修订，不能上传组织名单替换内容定义。
        ProtoWire.validate(request);
        validateInstanceId(request.getInstanceId());
        new DefinitionId(request.getActionId());
        ProtoWire.require(request.getActionVersion() > 0, MeridianErrors.Data.ACTION_VERSION);
    }

    public static void validateInstanceId(String instanceId) {
        // 请求与身体局部身份使用相同格式，但不把动作实例构造成其他模块类型。
        ProtoWire.require(instanceId != null && instanceId.matches("[a-z0-9_.-]+"), MeridianErrors.Data.LOCAL_ID_FORMAT);
    }
}
