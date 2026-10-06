package dev.kizuna.meridian.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 不可变且构造即校验的身体数据；不是允许客户端直接覆盖权威状态的命令。 */
public record BodySnapshot(int schemaVersion, BodyId id, BodyTemplate.Reference template,
                           BodyStructure structure, Realm realm,
                           List<AnatomyState.Part> partStates,
                           List<AnatomyState.Tissue> tissueStates,
                           List<AnatomyState.Organ> organStates,
                           List<MeridianState> meridianStates,
                           List<QiTransit> transits,
                           VitalityState vitality, TimeCursor settledAt, long revision) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public BodySnapshot {
        // 先拒绝未知 schema，避免把不同字段语义的数据误当作当前快照。
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的身体数据版本：" + schemaVersion);
        }
        Objects.requireNonNull(id, "身体标识不能为空");
        Objects.requireNonNull(template, "身体模板引用不能为空");
        Objects.requireNonNull(structure, "身体实际构型不能为空");
        Objects.requireNonNull(realm, "境界不能为空");
        // 防御性复制使通过校验的状态不再受调用者原始列表影响。
        partStates = List.copyOf(partStates);
        tissueStates = List.copyOf(tissueStates);
        organStates = List.copyOf(organStates);
        meridianStates = List.copyOf(meridianStates);
        transits = List.copyOf(transits);
        Objects.requireNonNull(vitality, "生命状态不能为空");
        Objects.requireNonNull(settledAt, "结算时间游标不能为空");
        DataChecks.revision(revision);

        // 按 ID 一一匹配而非只比较数量，防止缺失状态被同数量的错误状态替代。
        var parts = match(structure.parts(), Anatomy.PartDefinition::id, partStates, AnatomyState.Part::id, "部位");
        var tissues = match(structure.tissues(), Anatomy.TissueDefinition::id, tissueStates, AnatomyState.Tissue::id, "组织");
        var organs = match(structure.organs(), Anatomy.OrganDefinition::id, organStates, AnatomyState.Organ::id, "器官");
        var meridians = match(structure.meridians(), Anatomy.MeridianDefinition::id, meridianStates, MeridianState::id, "经脉");
        // 使用角色当前实际构型的上限，改造后的身体不受出生模板数值覆盖。
        for (var state : partStates) {
            DataChecks.atMost(state.hp(), parts.get(state.id()).maxHp(), "部位 HP");
        }
        for (var state : tissueStates) {
            var definition = tissues.get(state.id());
            DataChecks.atMost(state.hp(), definition.maxHp(), "组织 HP");
            DataChecks.atMost(state.fatigue(), definition.fatigueCapacity(), "组织疲劳");
        }
        for (var state : organStates) {
            DataChecks.atMost(state.hp(), organs.get(state.id()).maxHp(), "器官 HP");
        }
        for (var state : meridianStates) {
            var definition = meridians.get(state.id());
            DataChecks.atMost(state.lumenQi().totalQi(), definition.lumenCapacity(), "内腔真元");
            DataChecks.atMost(state.wallQi().totalQi(), definition.wallCapacity(), "外壁真元");
            DataChecks.atMost(state.fatigue(), definition.fatigueCapacity(), "经脉疲劳");
        }

        // 在途记录必须独立且指向现存连接，已经到达的记录应先完成结算。
        var connections = DataChecks.index(structure.connections(), Anatomy.Connection::id, "连接");
        DataChecks.index(transits, QiTransit::id, "在途记录");
        for (var transit : transits) {
            if (!connections.containsKey(transit.connectionId())) {
                throw new IllegalArgumentException("在途记录引用不存在的连接：" + transit.id());
            }
            if (transit.arrivalMillis() <= settledAt.elapsedMillis()) {
                throw new IllegalArgumentException("已到达的运输必须先结算再形成快照：" + transit.id());
            }
        }
        // 即使各账户都是有限数，整体求和仍可能溢出，必须再次检查。
        DataChecks.nonNegative(meridianStates.stream().mapToDouble(state -> state.lumenQi().totalQi()).sum()
                + meridianStates.stream().mapToDouble(state -> state.wallQi().totalQi()).sum()
                + transits.stream().mapToDouble(t -> t.cargo().totalQi()).sum(), "身体真元总量");
    }

    /** 内腔总量也不代表任意目标都能立即使用，实际还受通路与操作规则约束。 */
    public double lumenQiTotal() {
        // 只汇总内腔；外壁储备与尚未到达的真元不能作为内腔余额。
        return meridianStates.stream().mapToDouble(state -> state.lumenQi().totalQi()).sum();
    }

    public double wallQiTotal() {
        // 外壁总量用于储备展示，不代表可以直接施招。
        return meridianStates.stream().mapToDouble(state -> state.wallQi().totalQi()).sum();
    }

    public double transitQiTotal() {
        // 在途是独立归属；运行层必须先扣源，避免两处同时记账。
        return transits.stream().mapToDouble(transit -> transit.cargo().totalQi()).sum();
    }

    /** 汇总始终从唯一明细派生，不接受额外的可写总余额。 */
    public double totalQi() {
        // 每次从明细派生总量，避免额外维护一份可漂移的余额。
        return lumenQiTotal() + wallQiTotal() + transitQiTotal();
    }

    public double partHpRatio() {
        // 仅部位参与全身生命比例，内部组织与器官不再次计入。
        return partStates.stream().mapToDouble(AnatomyState.Part::hp).sum()
                / structure.parts().stream().mapToDouble(Anatomy.PartDefinition::maxHp).sum();
    }

    private static <K, D, S> Map<K, D> match(List<D> definitions, Function<D, K> definitionId,
                                           List<S> states, Function<S, K> stateId, String label) {
        // 分别拒绝重复 ID，再比较集合以同时识别缺失状态和多余状态。
        var definitionIndex = DataChecks.index(definitions, definitionId, label);
        var stateIndex = DataChecks.index(states, stateId, label + "状态");
        if (!definitionIndex.keySet().equals(stateIndex.keySet())) {
            throw new IllegalArgumentException(label + "定义与状态必须按标识一一对应");
        }
        return definitionIndex;
    }
}
