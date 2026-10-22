package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.OrganProcess;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.QiTransit;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.protocol.pb.BodyMessages;

/** 核心值与 protoc 生成消息之间的边界；反向转换始终重新执行核心构造校验。 */
public final class BodyProtobuf {
    private BodyProtobuf() {
        // 映射不持有身体状态，也不执行存储、时间推进或资源变更。
    }

    public static byte[] encodeSnapshot(BodySnapshot snapshot) {
        // 存档/宿主可使用独立快照格式；这不是客户端提交身体状态的命令。
        return ProtoWire.write(toMessage(snapshot));
    }

    public static BodySnapshot decodeSnapshot(byte[] bytes) {
        // 有界解析之后再构造核心快照，不能把 parseFrom 成功当成身体有效。
        return fromMessage(ProtoWire.read(bytes, BodyMessages.BodySnapshot.parser()));
    }

    public static byte[] encodeTemplate(BodyTemplate template) {
        // 模板只有定义，不夹带某具身体的余额或寿命。
        return ProtoWire.write(toMessage(template));
    }

    public static BodyTemplate decodeTemplate(byte[] bytes) {
        // 模板同样需要完整的端口及结构引用校验。
        return fromMessage(ProtoWire.read(bytes, BodyMessages.BodyTemplate.parser()));
    }

    public static BodyMessages.BodySnapshot toMessage(BodySnapshot value) {
        // 仅映射唯一明细账本，不序列化可重新派生的真元总量、HP 比例和剩余寿命。
        var builder = BodyMessages.BodySnapshot.newBuilder()
                .setSchemaVersion(value.schemaVersion()).setId(value.id().value())
                .setTemplate(toReference(value.template())).setStructure(toStructure(value.structure()))
                .setRealm(BodyMessages.Realm.valueOf(value.realm().name()))
                .addAllPartStates(value.partStates().stream().map(BodyProtobuf::toPartState).toList())
                .addAllTissueStates(value.tissueStates().stream().map(BodyProtobuf::toTissueState).toList())
                .addAllOrganStates(value.organStates().stream().map(BodyProtobuf::toOrganState).toList())
                .addAllMeridianStates(value.meridianStates().stream().map(BodyProtobuf::toMeridianState).toList())
                .addAllTransits(value.transits().stream().map(BodyProtobuf::toTransit).toList())
                .setVitality(toVitality(value.vitality())).setSettledAt(toTime(value.settledAt()))
                .setRevision(value.revision()).putAllAddonProgress(value.addonProgress())
                .addAllActions(value.actions().stream().map(ActionProtobuf::toActive).toList());
        if (value.openingTask() != null) {
            builder.setOpeningTask(OpeningProtobuf.toTask(value.openingTask()));
        }
        var message = builder.build();
        ProtoWire.validate(message);
        return message;
    }

    public static BodySnapshot fromMessage(BodyMessages.BodySnapshot value) {
        // 先拒绝缺失/未知字段，再让原有核心校验统一验证上限、引用和时序。
        ProtoWire.validate(value);
        return new BodySnapshot(value.getSchemaVersion(), new BodyId(value.getId()), fromReference(value.getTemplate()),
                fromStructure(value.getStructure()), Realm.valueOf(value.getRealm().name()),
                value.getPartStatesList().stream().map(BodyProtobuf::fromPartState).toList(),
                value.getTissueStatesList().stream().map(BodyProtobuf::fromTissueState).toList(),
                value.getOrganStatesList().stream().map(BodyProtobuf::fromOrganState).toList(),
                value.getMeridianStatesList().stream().map(BodyProtobuf::fromMeridianState).toList(),
                value.getTransitsList().stream().map(BodyProtobuf::fromTransit).toList(),
                fromVitality(value.getVitality()), fromTime(value.getSettledAt()), value.getRevision(),
                value.getActionsList().stream().map(ActionProtobuf::fromActive).toList(),
                value.hasOpeningTask() ? OpeningProtobuf.fromTask(value.getOpeningTask()) : null,
                value.getAddonProgressMap());
    }

    public static BodyMessages.BodyTemplate toMessage(BodyTemplate value) {
        // 出生模板独立交换，实例改造不会回写此结构。
        var message = BodyMessages.BodyTemplate.newBuilder().setReference(toReference(value.reference()))
                .setStructure(toStructure(value.structure())).build();
        ProtoWire.validate(message);
        return message;
    }

    public static BodyTemplate fromMessage(BodyMessages.BodyTemplate value) {
        // 解码不会根据模板补全或重置任何角色状态。
        ProtoWire.validate(value);
        return new BodyTemplate(fromReference(value.getReference()), fromStructure(value.getStructure()));
    }

    private static BodyMessages.TemplateReference toReference(BodyTemplate.Reference value) {
        // 内容版本独立保留，不用线协议版本替代。
        return BodyMessages.TemplateReference.newBuilder().setId(value.id().value()).setVersion(value.version()).build();
    }

    private static BodyTemplate.Reference fromReference(BodyMessages.TemplateReference value) {
        // 复用内容 ID 与正整数版本规则。
        return new BodyTemplate.Reference(new DefinitionId(value.getId()), value.getVersion());
    }

    private static BodyMessages.BodyStructure toStructure(BodyStructure value) {
        // 部位、组织、器官、经脉分别序列化，以端口连接而不互相内嵌所有权。
        return BodyMessages.BodyStructure.newBuilder()
                .addAllParts(value.parts().stream().map(BodyProtobuf::toPart).toList())
                .addAllTissues(value.tissues().stream().map(BodyProtobuf::toTissue).toList())
                .addAllOrgans(value.organs().stream().map(BodyProtobuf::toOrgan).toList())
                .addAllMeridians(value.meridians().stream().map(BodyProtobuf::toMeridian).toList())
                .addAllConnections(value.connections().stream().map(BodyProtobuf::toConnection).toList()).build();
    }

    private static BodyStructure fromStructure(BodyMessages.BodyStructure value) {
        // 构型构造器检查重复模块、悬空引用和连接方向，合法循环仍可通过。
        return new BodyStructure(value.getPartsList().stream().map(BodyProtobuf::fromPart).toList(),
                value.getTissuesList().stream().map(BodyProtobuf::fromTissue).toList(),
                value.getOrgansList().stream().map(BodyProtobuf::fromOrgan).toList(),
                value.getMeridiansList().stream().map(BodyProtobuf::fromMeridian).toList(),
                value.getConnectionsList().stream().map(BodyProtobuf::fromConnection).toList());
    }

    private static BodyMessages.PartDefinition toPart(Anatomy.PartDefinition value) {
        // 定义只携带上限，当前 HP 在状态列表中单独保存。
        return BodyMessages.PartDefinition.newBuilder().setId(value.id().value())
                .setDisplayName(value.displayName()).setMaxHp(value.maxHp()).build();
    }

    private static Anatomy.PartDefinition fromPart(BodyMessages.PartDefinition value) {
        // 保留可配置部位，不引入人体固定数量或命名。
        return new Anatomy.PartDefinition(new Anatomy.PartId(value.getId()), value.getDisplayName(), value.getMaxHp());
    }

    private static BodyMessages.TissueDefinition toTissue(Anatomy.TissueDefinition value) {
        // 组织拥有自己的类型、承载参数和端口，不归经脉持有。
        return BodyMessages.TissueDefinition.newBuilder().setId(value.id().value()).setDisplayName(value.displayName())
                .setKind(value.kind().value()).setPartId(value.partId().value()).setMaxHp(value.maxHp())
                .setFatigueCapacity(value.fatigueCapacity())
                .setRecoveryHalfLifeSeconds(value.recoveryHalfLifeSeconds())
                .setCapacity(CapabilityProtobuf.toCapacity(value.capacity()))
                .addAllPorts(value.ports().stream().map(BodyProtobuf::toPort).toList()).build();
    }

    private static Anatomy.TissueDefinition fromTissue(BodyMessages.TissueDefinition value) {
        // 类型按命名空间恢复，肌肉和第三方组织走相同规则。
        return new Anatomy.TissueDefinition(new Anatomy.TissueId(value.getId()), value.getDisplayName(),
                new DefinitionId(value.getKind()), new Anatomy.PartId(value.getPartId()), value.getMaxHp(),
                value.getFatigueCapacity(), value.getPortsList().stream().map(BodyProtobuf::fromPort).toList(),
                CapabilityProtobuf.fromCapacity(value.getCapacity()), value.getRecoveryHalfLifeSeconds());
    }

    private static BodyMessages.OrganDefinition toOrgan(Anatomy.OrganDefinition value) {
        // 缓存容量和搬运吞吐属于器官本体，具体转换内容由独立配方声明。
        var builder = BodyMessages.OrganDefinition.newBuilder().setId(value.id().value()).setDisplayName(value.displayName())
                .setKind(value.kind().value()).setPartId(value.partId().value()).setMaxHp(value.maxHp())
                .setVolumeCapacity(value.volumeCapacity()).setThroughputPerSecond(value.throughputPerSecond())
                .addAllPorts(value.ports().stream().map(BodyProtobuf::toPort).toList());
        if (value.intake() != null) {
            builder.setIntake(BodyMessages.OrganIntakeCapability.newBuilder()
                    .setMaxQiPerSecond(value.intake().maxQiPerSecond()));
        }
        if (value.endurance() != null) {
            builder.setEndurance(BodyMessages.OrganEndurance.newBuilder()
                    .setEnduranceJoules(value.endurance().enduranceJoules())
                    .setMaximumWatts(value.endurance().maximumWatts())
                    .setRecoveryHalfLifeSeconds(value.endurance().recoveryHalfLifeSeconds()));
        }
        return builder.build();
    }

    private static Anatomy.OrganDefinition fromOrgan(BodyMessages.OrganDefinition value) {
        // 归属部位和端口的实际存在性由完整构型验证。
        return new Anatomy.OrganDefinition(new Anatomy.OrganId(value.getId()), value.getDisplayName(),
                new DefinitionId(value.getKind()), new Anatomy.PartId(value.getPartId()), value.getMaxHp(),
                value.getPortsList().stream().map(BodyProtobuf::fromPort).toList(),
                value.getVolumeCapacity(), value.getThroughputPerSecond(), value.hasIntake()
                        ? new Anatomy.OrganIntakeCapability(value.getIntake().getMaxQiPerSecond()) : null,
                value.hasEndurance() ? new Anatomy.OrganEndurance(value.getEndurance().getEnduranceJoules(),
                        value.getEndurance().getMaximumWatts(), value.getEndurance().getRecoveryHalfLifeSeconds()) : null);
    }

    private static BodyMessages.MeridianDefinition toMeridian(Anatomy.MeridianDefinition value) {
        // 只传稳定体积；零外壁体积也必须显式写出。
        return BodyMessages.MeridianDefinition.newBuilder().setId(value.id().value()).setDisplayName(value.displayName())
                .addAllPartIds(value.partIds().stream().map(Anatomy.PartId::value).toList())
                .setLumenVolume(value.lumenVolume()).setWallVolume(value.wallVolume())
                .setThroughputPerSecond(value.throughputPerSecond()).setResilience(value.resilience())
                .setFatigueCapacity(value.fatigueCapacity())
                .addAllPorts(value.ports().stream().map(BodyProtobuf::toPort).toList()).build();
    }

    private static Anatomy.MeridianDefinition fromMeridian(BodyMessages.MeridianDefinition value) {
        // 核心继续检查外壁参照与正数承载参数，协议层不维护另一套公式。
        return new Anatomy.MeridianDefinition(new Anatomy.MeridianId(value.getId()), value.getDisplayName(),
                value.getPartIdsList().stream().map(Anatomy.PartId::new).toList(), value.getLumenVolume(),
                value.getWallVolume(),
                value.getThroughputPerSecond(), value.getResilience(), value.getFatigueCapacity(),
                value.getPortsList().stream().map(BodyProtobuf::fromPort).toList());
    }

    private static BodyMessages.Port toPort(Anatomy.Port value) {
        // 枚举按明确名称映射；线上数值由 proto 固定，不能使用 Java ordinal。
        return BodyMessages.Port.newBuilder().setId(value.id().value())
                .setDirection(BodyMessages.PortDirection.valueOf(value.direction().name())).build();
    }

    private static Anatomy.Port fromPort(BodyMessages.Port value) {
        // 未知枚举已在边界拒绝，不把缺失方向当成输入端口。
        return new Anatomy.Port(new Anatomy.PortId(value.getId()), Anatomy.PortDirection.valueOf(value.getDirection().name()));
    }

    private static BodyMessages.Connection toConnection(Anatomy.Connection value) {
        // 双向通路保持两条显式有向边，不在通信时合并。
        var message = BodyMessages.Connection.newBuilder().setId(value.id().value()).setFrom(value.from().value())
                .setTo(value.to().value()).setResistance(value.resistance()).setDistance(value.distance());
        if (value.passiveQiFlow() != null) {
            Anatomy.PassiveQiFlow flow = value.passiveQiFlow();
            message.setPassiveQiFlow(BodyMessages.PassiveQiFlow.newBuilder()
                    .setThroughputPerSecond(flow.throughputPerSecond())
                    .setMaximumBatchQi(flow.maximumBatchQi()).setMinimumBatchQi(flow.minimumBatchQi()));
        }
        return message.build();
    }

    private static Anatomy.Connection fromConnection(BodyMessages.Connection value) {
        // 被动配置随实际构型保存，恢复时不会由模板重新赋予或撤销扩散权限。
        Anatomy.PassiveQiFlow passiveFlow = null;
        if (value.hasPassiveQiFlow()) {
            BodyMessages.PassiveQiFlow flow = value.getPassiveQiFlow();
            passiveFlow = new Anatomy.PassiveQiFlow(flow.getThroughputPerSecond(),
                    flow.getMaximumBatchQi(), flow.getMinimumBatchQi());
        }
        return new Anatomy.Connection(new Anatomy.ConnectionId(value.getId()), new Anatomy.PortId(value.getFrom()),
                new Anatomy.PortId(value.getTo()), value.getResistance(), value.getDistance(), passiveFlow);
    }

    private static BodyMessages.PartState toPartState(AnatomyState.Part value) {
        // 零 HP 也是明确状态，不允许被编码省略为未提供。
        return BodyMessages.PartState.newBuilder().setId(value.id().value()).setHp(value.hp()).build();
    }

    private static AnatomyState.Part fromPartState(BodyMessages.PartState value) {
        // HP 上限与部位身份匹配在完整快照内验证。
        return new AnatomyState.Part(new Anatomy.PartId(value.getId()), value.getHp());
    }

    private static BodyMessages.TissueState toTissueState(AnatomyState.Tissue value) {
        // 疲劳与伤势分别持有，不能由一个数值推算另一个。
        return BodyMessages.TissueState.newBuilder().setId(value.id().value()).setHp(value.hp())
                .setFatigue(value.fatigue()).build();
    }

    private static AnatomyState.Tissue fromTissueState(BodyMessages.TissueState value) {
        // 状态绑定组织实例，不绑定组织类型，避免同种肌肉共享伤势。
        return new AnatomyState.Tissue(new Anatomy.TissueId(value.getId()), value.getHp(), value.getFatigue());
    }

    private static BodyMessages.OrganState toOrganState(AnatomyState.Organ value) {
        // 空闲状态不带加工记录；启动后的配方事实和原料账户必须一起存档。
        var builder = BodyMessages.OrganState.newBuilder().setId(value.id().value()).setHp(value.hp())
                .setBuffer(toInventory(value.buffer())).setFatigueJoules(value.fatigueJoules());
        if (value.process() != null) {
            builder.setProcess(CapabilityProtobuf.toProcess(value.process()));
        }
        return builder.build();
    }

    private static AnatomyState.Organ fromOrganState(BodyMessages.OrganState value) {
        // 不在解码时触发器官失能或死亡副作用。
        var organId = new Anatomy.OrganId(value.getId());
        QiInventory buffer = fromInventory(value.getBuffer());

        // 无加工记录就是空闲器官，不凭默认配方补造运行中的任务。
        OrganProcess process = null;
        if (value.hasProcess()) {
            process = CapabilityProtobuf.fromProcess(value.getProcess());
        }
        return new AnatomyState.Organ(organId, value.getHp(), buffer, process, value.getFatigueJoules());
    }

    public static BodyMessages.QiInventory toInventory(QiInventory value) {
        // 保留所有混相和自定义属性明细，空账户仍显式编码消息存在性。
        return BodyMessages.QiInventory.newBuilder()
                .addAllEntries(value.entries().stream().map(BodyProtobuf::toQi).toList()).build();
    }

    public static QiInventory fromInventory(BodyMessages.QiInventory value) {
        // 账户构造器拒绝重复同类明细、零数量和求和溢出。
        return new QiInventory(value.getEntriesList().stream().map(BodyProtobuf::fromQi).toList());
    }

    private static BodyMessages.Qi toQi(Qi value) {
        // 属性始终使用命名空间 ID，避免扩展属性被压成固定枚举。
        return BodyMessages.Qi.newBuilder().setDensity(value.density())
                .setAttribute(value.attribute().id().value()).setAmount(value.amount()).build();
    }

    private static Qi fromQi(BodyMessages.Qi value) {
        // 按实际密度恢复账本；相态由密度派生，不在协议中重复保存。
        return new Qi(Qi.QiAttribute.of(value.getAttribute()), value.getAmount(), value.getDensity());
    }

    private static BodyMessages.MeridianState toMeridianState(MeridianState value) {
        // 账户、损伤和开拓事实分别编码，恢复时不从储量反推意念控制能力。
        return BodyMessages.MeridianState.newBuilder().setId(value.id().value())
                .setLumenQi(toInventory(value.lumenQi())).setWallQi(toInventory(value.wallQi()))
                .setFatigue(value.fatigue())
                .setCondition(BodyMessages.MeridianCondition.valueOf(value.condition().name()))
                .setOpening(toOpening(value.opening())).build();
    }

    private static MeridianState fromMeridianState(BodyMessages.MeridianState value) {
        // 结构上限在完整快照内校验，不允许两个账户互借额度。
        return new MeridianState(new Anatomy.MeridianId(value.getId()), fromInventory(value.getLumenQi()),
                fromInventory(value.getWallQi()), value.getFatigue(), MeridianState.Condition.valueOf(value.getCondition().name()),
                fromOpening(value.getOpening()));
    }

    private static BodyMessages.MeridianOpening toOpening(MeridianOpening value) {
        // 零进度也显式写出，使接收方能够区分未开拓与缺失数据。
        return BodyMessages.MeridianOpening.newBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.valueOf(value.stage().name()))
                .setProgress(value.progress()).build();
    }

    private static MeridianOpening fromOpening(BodyMessages.MeridianOpening value) {
        // 核心构造器再次验证阶段与进度的对应关系，不能只依赖消息解析成功。
        return new MeridianOpening(MeridianOpening.Stage.valueOf(value.getStage().name()), value.getProgress());
    }

    public static BodyMessages.QiTransit toTransit(QiTransit value) {
        // 在途货物单独持有，不重复附加到源或目标经脉账户。
        var message = BodyMessages.QiTransit.newBuilder().setId(value.id()).setConnectionId(value.connectionId().value())
                .setCargo(toInventory(value.cargo())).setArrivalMillis(value.arrivalMillis())
                .setThroughputPerSecond(value.throughputPerSecond())
                .setStatus(BodyMessages.TransitStatus.valueOf(value.status().name()));
        if (value.circuit() != null) {
            message.setCircuit(value.circuit());
        }
        return message.build();
    }

    public static QiTransit fromTransit(BodyMessages.QiTransit value) {
        // 到达时间沿用所属快照时钟，恢复不能把它解释为系统时间戳。
        return new QiTransit(value.getId(), new Anatomy.ConnectionId(value.getConnectionId()),
                fromInventory(value.getCargo()), value.getArrivalMillis(), value.getThroughputPerSecond(),
                QiTransit.Status.valueOf(value.getStatus().name()), value.hasCircuit() ? value.getCircuit() : null);
    }

    private static BodyMessages.VitalityState toVitality(VitalityState value) {
        // 年龄、寿命上限与额外支出分开存储，零值及存活状态均显式写出。
        return BodyMessages.VitalityState.newBuilder().setAgeSeconds(value.ageSeconds())
                .setLifespanLimitSeconds(value.lifespanLimitSeconds()).setSpentLifespanSeconds(value.spentLifespanSeconds())
                .setDead(value.dead()).build();
    }

    private static VitalityState fromVitality(BodyMessages.VitalityState value) {
        // 反序列化只恢复既有事实，不推进年龄也不重复燃烧寿元。
        return new VitalityState(value.getAgeSeconds(), value.getLifespanLimitSeconds(), value.getSpentLifespanSeconds(), value.getDead());
    }

    public static BodyMessages.TimeCursor toTime(TimeCursor value) {
        // 时钟域和世代必须随位置交换，避免重连后错误比较不同时间基准。
        return BodyMessages.TimeCursor.newBuilder().setDomain(value.domain().value()).setEpoch(value.epoch())
                .setElapsedMillis(value.elapsedMillis()).build();
    }

    public static TimeCursor fromTime(BodyMessages.TimeCursor value) {
        // 使用 int64 精确保留时间；不经过 JSON 数字或浮点转换。
        return new TimeCursor(new DefinitionId(value.getDomain()), value.getEpoch(), value.getElapsedMillis());
    }
}
