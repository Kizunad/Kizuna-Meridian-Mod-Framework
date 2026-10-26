package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一次时间事务内的候选与派生索引；不向宿主暴露，失败时整体丢弃。 */
final class BodyAdvancement implements BodyView {
    final BodySnapshot original;
    private CapabilityCurves.Context curves;
    private Map<Anatomy.TissueId, Anatomy.TissueDefinition> tissueDefinitions;
    private Map<Anatomy.PartId, Anatomy.PartDefinition> partDefinitions;
    private Map<Anatomy.PartId, AnatomyState.Part> parts;
    private Map<Anatomy.TissueId, Integer> tissuePositions;
    final Set<Anatomy.TissueId> intervalUsedTissues;
    BodyStructure structure;
    List<AnatomyState.Tissue> tissues;
    List<AnatomyState.Organ> organs;
    List<MeridianState> meridians;
    List<QiTransit> transits;
    VitalityState vitality;
    TimeCursor time;
    List<BodyAction.Active> actions;
    OpeningTask task;
    QiInventory released = QiInventory.empty();
    OpeningEfforts.Budget effortBudget;
    private Set<Anatomy.TissueId> occupiedTissues;
    private List<BodyAction.Active> indexedActions;
    private OpeningPolicies.Context policy;

    BodyAdvancement(BodySnapshot body, Set<Anatomy.TissueId> usedTissues) {
        // 原快照已经通过信任边界校验；静态定义和曲线索引在整次推进内只建立一次。
        original = body;
        intervalUsedTissues = Set.copyOf(usedTissues);
        if (!intervalUsedTissues.isEmpty() && !tissueDefinitions().keySet().containsAll(intervalUsedTissues)) {
            throw MeridianErrors.invalid(MeridianErrors.Data.INVALID_ACTION_STATE);
        }
        structure = body.structure();
        tissues = body.tissueStates();
        organs = body.organStates();
        meridians = body.meridianStates();
        transits = body.transits();
        vitality = body.vitality();
        time = body.settledAt();
        actions = body.actions();
        task = body.openingTask();
    }

    Set<Anatomy.TissueId> occupiedTissues() {
        // 开脉终止后占用会改变；只有动作列表变化时才重新建立并集。
        if (indexedActions != actions) {
            if (actions.isEmpty()) {
                occupiedTissues = intervalUsedTissues;
                indexedActions = actions;
                return occupiedTissues;
            }
            var occupied = new HashSet<>(intervalUsedTissues);
            for (BodyAction.Active action : actions) {
                occupied.addAll(action.definition().tissueIds());
            }
            occupiedTissues = Set.copyOf(occupied);
            indexedActions = actions;
        }
        return occupiedTissues;
    }

    OpeningPolicies.Context policy() {
        // 普通恢复推进不建立 Protobuf 投影；策略上下文只在第一次实际调用时创建。
        if (policy == null) {
            policy = new OpeningPolicies.Context(original);
        }
        return policy;
    }

    CapabilityCurves.Context curves() {
        // 冻结推进不查询能力或恢复，不为失效时间删除建立曲线索引。
        if (curves == null) {
            curves = CapabilityCurves.context(original.modifiers());
        }
        return curves;
    }

    Map<Anatomy.TissueId, Anatomy.TissueDefinition> tissueDefinitions() {
        // 一次推进的构型只会扩展经脉，组织定义索引可以持续复用。
        if (tissueDefinitions == null) {
            tissueDefinitions = DataChecks.index(original.structure().tissues(), Anatomy.TissueDefinition::id, "组织");
        }
        return tissueDefinitions;
    }

    AnatomyState.Part partState(Anatomy.PartId id) {
        // 时间结算不改变部位 HP；只有恢复和能力查询需要建立这个索引。
        if (parts == null) {
            parts = DataChecks.index(original.partStates(), AnatomyState.Part::id, "部位状态");
        }
        return parts.get(id);
    }

    int tissuePosition(Anatomy.TissueId id) {
        // 只有持续负载需要按 ID 修改单个组织；空闲恢复按原列表顺序遍历。
        if (tissuePositions == null) {
            var positions = new java.util.HashMap<Anatomy.TissueId, Integer>();
            for (int index = 0; index < tissues.size(); index++) {
                positions.put(tissues.get(index).id(), index);
            }
            tissuePositions = positions;
        }
        return tissuePositions.get(id);
    }

    Anatomy.PartDefinition partDefinition(Anatomy.PartId id) {
        // 基础 HP 比例只用于能力预算，冻结和普通恢复不建立这个额外索引。
        if (partDefinitions == null) {
            partDefinitions = DataChecks.index(structure.parts(), Anatomy.PartDefinition::id, "部位定义");
        }
        return partDefinitions.get(id);
    }

    List<CapabilityModifier> remainingModifiers() {
        // 活动候选和单段冻结结算共用同一到期规则。
        return remainingModifiers(original, time, vitality);
    }

    static List<CapabilityModifier> remainingModifiers(BodySnapshot original, TimeCursor time,
                                                       VitalityState vitality) {
        // 到期删除与死亡清空只在输出边界应用，内部曲线评估仍按同一时刻自动失效。
        if (vitality.dead()) {
            return List.of();
        }
        if (original.modifiers().isEmpty()) {
            return original.modifiers();
        }
        long nowMillis = time.elapsedMillis();
        return original.modifiers().stream()
                .filter(modifier -> modifier.curve().endMillis() > nowMillis)
                .toList();
    }

    BodyTimeAdvancement.Result result() {
        // 整次推进只在这里构造并全量校验快照，版本严格增加一次。
        var snapshot = new BodySnapshot(original.schemaVersion(), original.id(), original.template(), structure,
                original.realm(), original.partStates(), tissues, organs, meridians, transits,
                vitality, time, original.revision() + 1, actions, task, original.addonProgress(), remainingModifiers());
        return new BodyTimeAdvancement.Result(snapshot, vitality.dead(), released);
    }

    boolean unchanged() {
        // 同毫秒只允许有实际状态变化的事务，比较候选即可，无需临时构造完整快照。
        return time.equals(original.settledAt()) && vitality.equals(original.vitality())
                && structure.equals(original.structure()) && tissues.equals(original.tissueStates())
                && organs.equals(original.organStates()) && meridians.equals(original.meridianStates())
                && transits.equals(original.transits()) && actions.equals(original.actions())
                && java.util.Objects.equals(task, original.openingTask());
    }

    @Override
    public BodyStructure structure() {
        // 完成开脉可能拓展经脉，后续资源算子须读取当前构型。
        return structure;
    }

    @Override
    public List<AnatomyState.Part> partStates() {
        // 时间结算不会改变部位 HP。
        return original.partStates();
    }

    @Override
    public List<AnatomyState.Tissue> tissueStates() {
        // 疲劳按每个事件段依次更新，保留既有舍入顺序。
        return tissues;
    }

    @Override
    public List<AnatomyState.Organ> organStates() {
        // 加工和疲劳恢复共用本次候选状态。
        return organs;
    }

    @Override
    public List<MeridianState> meridianStates() {
        // 资源账户和开脉展示读取同一候选。
        return meridians;
    }

    @Override
    public List<QiTransit> transits() {
        // 已发运的批次继续参与后续事件结算。
        return transits;
    }

    @Override
    public VitalityState vitality() {
        // 寿尽判断使用上一事件段的结算结果。
        return vitality;
    }

    @Override
    public TimeCursor settledAt() {
        // 游标只在区间计算成功后前移。
        return time;
    }

    @Override
    public long revision() {
        // 内部事件没有独立版本，批次身份继续使用事务起始版本。
        return original.revision();
    }

    @Override
    public List<BodyAction.Active> actions() {
        // 任务完成或终止会解除占用。
        return actions;
    }

    @Override
    public OpeningTask openingTask() {
        // 持续预算与资源调度共享当前任务进度。
        return task;
    }
}
