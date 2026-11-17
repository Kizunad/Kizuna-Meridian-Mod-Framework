package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.OrganProcess;
import dev.kizuna.meridian.core.OrganRecipe;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

import java.util.HashSet;

/** 组织能力与器官配方使用同一 protoc 契约，附属 JAR 不自定义平行载荷。 */
public final class CapabilityProtobuf {
    private CapabilityProtobuf() {
        // 映射不执行配方注册、学习授权或账户提交。
    }

    public static CapabilityModifier fromModifier(BodyMessages.CapabilityModifier modifier) {
        // 身份使用现有内容和局部组织规则，组织存在性与期限由完整快照检查。
        ProtoWire.validate(modifier);
        var points = modifier.getCurveList().stream()
                .map(point -> new CapabilityModifier.CurvePoint(point.getOffsetMillis(), point.getValue()))
                .toList();
        var curve = new CapabilityModifier.Curve(modifier.getStartMillis(), points);
        double tempoPeak = 1;
        if (modifier.hasTempoPeak()) {
            tempoPeak = modifier.getTempoPeak();
        }
        var tissuePeaks = new CapabilityModifier.TissuePeaks(modifier.getStrengthPeak(), modifier.getBurstPeak(),
                modifier.getEndurancePeak(), modifier.getRecoveryPeak(), tempoPeak);
        double intakePeak = 1;
        double throughputPeak = 1;
        if (modifier.hasIntakePeak()) {
            intakePeak = modifier.getIntakePeak();
        }
        if (modifier.hasThroughputPeak()) {
            throughputPeak = modifier.getThroughputPeak();
        }
        var organPeaks = new CapabilityModifier.OrganPeaks(intakePeak, throughputPeak);
        var peaks = new CapabilityModifier.Peaks(tissuePeaks, organPeaks);
        CapabilityModifier.Target target;
        if (modifier.hasOrganId()) {
            target = new CapabilityModifier.Target(null, new Anatomy.OrganId(modifier.getOrganId()));
        } else {
            target = new CapabilityModifier.Target(new Anatomy.TissueId(modifier.getTissueId()), null);
        }
        return new CapabilityModifier(new DefinitionId(modifier.getId()), new DefinitionId(modifier.getSource()),
                target, curve, peaks);
    }

    public static BodyMessages.CapabilityModifier toModifier(CapabilityModifier modifier) {
        // 存档保留来源、曲线和目标维度，不把派生能力写回基础值。
        var points = modifier.curve().points().stream().map(CapabilityProtobuf::toCurvePoint).toList();
        var result = BodyMessages.CapabilityModifier.newBuilder()
                .setId(modifier.id().value())
                .setSource(modifier.source().value())
                .setStartMillis(modifier.curve().startMillis())
                .addAllCurve(points)
                .setStrengthPeak(modifier.peaks().strength())
                .setBurstPeak(modifier.peaks().burst())
                .setEndurancePeak(modifier.peaks().endurance())
                .setRecoveryPeak(modifier.peaks().recovery())
                .setTempoPeak(modifier.peaks().tempo())
                .setIntakePeak(modifier.peaks().organ().intake())
                .setThroughputPeak(modifier.peaks().organ().throughput());
        if (modifier.target().organId() != null) {
            result.setOrganId(modifier.target().organId().value());
        } else {
            result.setTissueId(modifier.tissueId().value());
        }
        return result.build();
    }

    private static BodyMessages.CurvePoint toCurvePoint(CapabilityModifier.CurvePoint point) {
        // 曲线点单独构造，保留负值与相对毫秒，不转换为采样帧。
        return BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(point.offsetMillis())
                .setValue(point.value())
                .build();
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
        validateRoutes(recipe);
        return new OrganRecipe(new DefinitionId(recipe.getId()), recipe.getVersion(), new DefinitionId(recipe.getOrganKind()),
                BodyProtobuf.fromInventory(recipe.getInput()), BodyProtobuf.fromInventory(recipe.getOutput()),
                recipe.getDurationMillis(), Realm.valueOf(recipe.getMinimumRealm().name()));
    }

    private static void validateRoutes(BodyMessages.OrganRecipe recipe) {
        // 去处只能写给产物里真有的、不是无属性的那一样；一样只写一次；标签是内容 ID。
        var routed = new HashSet<String>();
        for (BodyMessages.OutputRoute route : recipe.getOutputRoutesList()) {
            new DefinitionId(route.getMeridianTag());
            boolean produced = recipe.getOutput().getEntriesList().stream()
                    .anyMatch(entry -> entry.getAttribute().equals(route.getAttribute()));
            ProtoWire.require(produced && !route.getAttribute().equals(Qi.QiAttribute.NONE.id().value())
                    && routed.add(route.getAttribute()), MeridianErrors.INVALID_OUTPUT_ROUTE);
        }
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
