package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.OrganProcess;
import dev.kizuna.meridian.core.OrganRecipe;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

/** 组织能力与器官配方使用同一 protoc 契约，附属 JAR 不自定义平行载荷。 */
public final class CapabilityProtobuf {
    private CapabilityProtobuf() {
        // 映射不执行配方注册、学习授权或账户提交。
    }

    public static byte[] encodeRecipe(BodyMessages.OrganRecipe recipe) {
        // 宿主安装定义前检查业务约束，不能把任意 Protobuf 消息当成合法配方。
        fromRecipe(recipe);
        return ProtoWire.write(recipe);
    }

    public static BodyMessages.OrganRecipe decodeRecipe(byte[] payload) {
        // 字节边界有大小与深度限制，反向映射再验证输入输出守恒。
        var recipe = ProtoWire.read(payload, BodyMessages.OrganRecipe.parser());
        fromRecipe(recipe);
        return recipe;
    }

    public static OrganRecipe fromRecipe(BodyMessages.OrganRecipe recipe) {
        // 相态、属性、器官类型和最低境界均保留类型化含义，不接受任意脚本。
        ProtoWire.validate(recipe);
        return new OrganRecipe(new DefinitionId(recipe.getId()), recipe.getVersion(), new DefinitionId(recipe.getOrganKind()),
                BodyProtobuf.fromInventory(recipe.getInput()), BodyProtobuf.fromInventory(recipe.getOutput()),
                recipe.getDurationMillis(), Realm.valueOf(recipe.getMinimumRealm().name()));
    }

    public static BodyMessages.OrganRecipe toRecipe(OrganRecipe recipe) {
        // 已启动任务使用同一配方格式保存，以便缺包时仍按固定事实完成。
        var message = BodyMessages.OrganRecipe.newBuilder().setId(recipe.id().value()).setVersion(recipe.version())
                .setOrganKind(recipe.organKind().value()).setInput(BodyProtobuf.toInventory(recipe.input()))
                .setOutput(BodyProtobuf.toInventory(recipe.output())).setDurationMillis(recipe.durationMillis())
                .setMinimumRealm(BodyMessages.Realm.valueOf(recipe.minimumRealm().name())).build();
        ProtoWire.validate(message);
        return message;
    }

    static BodyMessages.OrganProcess toProcess(OrganProcess process) {
        // blocked 必须显式存在，不能把缺失状态默认解释成运行中。
        return BodyMessages.OrganProcess.newBuilder().setRecipe(toRecipe(process.recipe()))
                .setInput(BodyProtobuf.toInventory(process.input()))
                .setCompletionMillis(process.completionMillis()).setBlocked(process.blocked()).build();
    }

    static OrganProcess fromProcess(BodyMessages.OrganProcess process) {
        // 与身体游标的关联校验交由完整快照构造器执行。
        return new OrganProcess(fromRecipe(process.getRecipe()), BodyProtobuf.fromInventory(process.getInput()),
                process.getCompletionMillis(), process.getBlocked());
    }

    public static BodyMessages.TissueCapacity toCapacity(Anatomy.TissueCapacity capacity) {
        // 零能力对被动组织合法，三个数值都显式写入，不默认补值。
        return BodyMessages.TissueCapacity.newBuilder().setStrengthNewtons(capacity.strengthNewtons())
                .setBurstWatts(capacity.burstWatts()).setEnduranceJoules(capacity.enduranceJoules()).build();
    }

    public static Anatomy.TissueCapacity fromCapacity(BodyMessages.TissueCapacity capacity) {
        // 有限非负值在通信层和核心值构造中均验证。
        ProtoWire.validate(capacity);
        return new Anatomy.TissueCapacity(capacity.getStrengthNewtons(), capacity.getBurstWatts(), capacity.getEnduranceJoules());
    }

    public static TissueCapabilities.Exertion fromExertion(MeridianMessages.ExertTissue request) {
        // 单次做功只声明负载，不允许调用方设置最终疲劳或有效能力。
        ProtoWire.validate(request);
        return new TissueCapabilities.Exertion(new Anatomy.TissueId(request.getTissueId()), request.getForceNewtons(),
                request.getPowerWatts(), request.getDurationSeconds());
    }

    public static MeridianMessages.TissueCapability capability(BodySnapshot body, Anatomy.TissueId id) {
        // 附带身体版本，调用者可以把能力读数与随后的变更意图关联。
        Anatomy.TissueCapacity availableCapacity = TissueCapabilities.available(body, id);
        return MeridianMessages.TissueCapability.newBuilder().setBodyId(body.id().value()).setTissueId(id.value())
                .setRevision(body.revision()).setAvailable(toCapacity(availableCapacity)).build();
    }
}
