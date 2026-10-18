package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 不可变且构造即校验的身体数据；不是允许客户端直接覆盖权威状态的命令。 */
public record BodySnapshot(int schemaVersion, BodyId id, BodyTemplate.Reference template,
                           BodyStructure structure, Realm realm,
                           List<AnatomyState.Part> partStates,
                           List<AnatomyState.Tissue> tissueStates,
                           List<AnatomyState.Organ> organStates,
                           List<MeridianState> meridianStates,
                           List<QiTransit> transits,
                           VitalityState vitality, TimeCursor settledAt, long revision,
                           List<BodyAction.Active> actions, OpeningTask openingTask) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public BodySnapshot(int schemaVersion, BodyId id, BodyTemplate.Reference template, BodyStructure structure,
                        Realm realm, List<AnatomyState.Part> partStates, List<AnatomyState.Tissue> tissueStates,
                        List<AnatomyState.Organ> organStates, List<MeridianState> meridianStates, List<QiTransit> transits,
                        VitalityState vitality, TimeCursor settledAt, long revision, List<BodyAction.Active> actions) {
        // 初次创建身体时没有开脉任务，状态变更须显式传递既有任务。
        this(schemaVersion, id, template, structure, realm, partStates, tissueStates, organStates,
                meridianStates, transits, vitality, settledAt, revision, actions, null);
    }

    public BodySnapshot(int schemaVersion, BodyId id, BodyTemplate.Reference template, BodyStructure structure,
                        Realm realm, List<AnatomyState.Part> partStates, List<AnatomyState.Tissue> tissueStates,
                        List<AnatomyState.Organ> organStates, List<MeridianState> meridianStates, List<QiTransit> transits,
                        VitalityState vitality, TimeCursor settledAt, long revision) {
        // 初始化没有活动动作的身体；已有身体的变更必须显式保留其动作实例。
        this(schemaVersion, id, template, structure, realm, partStates, tissueStates, organStates,
                meridianStates, transits, vitality, settledAt, revision, List.of());
    }

    public BodySnapshot {
        // 先拒绝未知 schema，避免把不同字段语义的数据误当作当前快照。
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw MeridianErrors.invalid(Data.unsupportedBodyVersion(schemaVersion));
        }
        MeridianErrors.required(id, Data.BODY_ID_REQUIRED);
        MeridianErrors.required(template, Data.BODY_TEMPLATE_REQUIRED);
        MeridianErrors.required(structure, Data.BODY_STRUCTURE_REQUIRED);
        MeridianErrors.required(realm, Data.REALM_REQUIRED);
        // 防御性复制使通过校验的状态不再受调用者原始列表影响。
        partStates = List.copyOf(partStates);
        tissueStates = List.copyOf(tissueStates);
        organStates = List.copyOf(organStates);
        meridianStates = List.copyOf(meridianStates);
        transits = List.copyOf(transits);
        actions = List.copyOf(actions);
        MeridianErrors.required(vitality, Data.VITALITY_REQUIRED);
        MeridianErrors.required(settledAt, Data.SETTLED_AT_REQUIRED);
        DataChecks.revision(revision);

        // 按 ID 一一匹配而非只比较数量，防止缺失状态被同数量的错误状态替代。
        var parts = match(structure.parts(), Anatomy.PartDefinition::id, partStates, AnatomyState.Part::id, "部位");
        var tissues = match(structure.tissues(), Anatomy.TissueDefinition::id, tissueStates, AnatomyState.Tissue::id, "组织");
        var organs = match(structure.organs(), Anatomy.OrganDefinition::id, organStates, AnatomyState.Organ::id, "器官");
        var meridians = match(structure.meridians(), Anatomy.MeridianDefinition::id, meridianStates, MeridianState::id, "经脉");
        // 多动作允许共用同一组织；同实例只记一次，死体或未来动作不能伪装成当前占用。
        DataChecks.index(actions, BodyAction.Active::instanceId, "动作实例");
        if (actions.size() > BodyActions.MAX_ACTIVE_ACTIONS || (vitality.dead() && !actions.isEmpty())) {
            throw MeridianErrors.invalid(Data.INVALID_ACTION_STATE);
        }
        for (BodyAction.Active action : actions) {
            if (action.startedMillis() > settledAt.elapsedMillis()
                    || !tissues.keySet().containsAll(action.definition().tissueIds())
                    || !organs.keySet().containsAll(action.definition().organIds())) {
                throw MeridianErrors.invalid(Data.INVALID_ACTION_STATE);
            }
        }
        // 使用角色当前实际构型的上限，改造后的身体不受出生模板数值覆盖。
        for (var state : partStates) {
            DataChecks.atMost(state.hp(), parts.get(state.id()).maxHp(), "部位 HP");
        }
        for (var state : tissueStates) {
            Anatomy.TissueDefinition definition = tissues.get(state.id());
            DataChecks.atMost(state.hp(), definition.maxHp(), "组织 HP");
            DataChecks.atMost(state.fatigue(), definition.fatigueCapacity(), "组织疲劳");
        }
        for (var state : organStates) {
            Anatomy.OrganDefinition definition = organs.get(state.id());
            double enduranceLimit = definition.endurance() == null ? 0 : definition.endurance().enduranceJoules();
            DataChecks.atMost(state.fatigueJoules(), enduranceLimit, "器官疲劳");
            DataChecks.atMost(state.hp(), definition.maxHp(), "器官 HP");
            DataChecks.atMost(state.occupiedQi(), definition.qiCapacity(), "器官真元");
            if (state.process() != null) {
                // 正在加工的输入仍归身体所有；状态与时间不符会导致重复完成或漏结算。
                OrganProcess process = state.process();
                if (!process.recipe().organKind().equals(definition.kind())) {
                    throw MeridianErrors.invalid(Data.PROCESS_KIND_MISMATCH);
                }
                // 已到期仍保留的任务必须是阻塞状态；未到期任务则不能预先标记阻塞。
                boolean completionReached = process.completionMillis() <= settledAt.elapsedMillis();
                if (process.blocked() != completionReached) {
                    throw MeridianErrors.invalid(Data.INVALID_PROCESS_TIME);
                }
            }
        }
        for (var state : meridianStates) {
            Anatomy.MeridianDefinition definition = meridians.get(state.id());
            double lumenLimit = openingTask == null ? definition.lumenCapacity() : openingTask.capacity(definition);
            DataChecks.atMost(state.lumenQi().totalQi(), lumenLimit, "内腔真元");
            DataChecks.atMost(state.wallQi().totalQi(), definition.wallCapacity(), "外壁真元");
            DataChecks.atMost(state.fatigue(), definition.fatigueCapacity(), "经脉疲劳");
        }

        // 临时超压必须能追溯到同一任务与动作，不能靠上传一张额度表提高容量。
        if (openingTask != null) {
            OpeningRuntime.validateTask(openingTask, structure, meridianStates, actions, settledAt, vitality);
        }

        // 运输中记录必须指向未来；已到达但受阻的货物保留在途归属，不回填源账户。
        var connections = DataChecks.index(structure.connections(), Anatomy.Connection::id, "连接");
        DataChecks.index(transits, QiTransit::id, "在途记录");
        for (var transit : transits) {
            if (!connections.containsKey(transit.connectionId())) {
                throw MeridianErrors.invalid(Data.transitConnectionMissing(transit.id()));
            }
            if (transit.status() == QiTransit.Status.MOVING && transit.arrivalMillis() <= settledAt.elapsedMillis()) {
                throw MeridianErrors.invalid(Data.transitAlreadyArrived(transit.id()));
            }
            if (transit.status() != QiTransit.Status.MOVING && transit.arrivalMillis() > settledAt.elapsedMillis()) {
                throw MeridianErrors.invalid(Data.TRANSIT_BLOCKED_BEFORE_ARRIVAL);
            }
        }

        // 即使各账户都是有限数，整体求和仍可能溢出，必须再次检查。
        double lumenQi = meridianStates.stream()
                .mapToDouble(state -> state.lumenQi().totalQi())
                .sum();
        double wallQi = meridianStates.stream()
                .mapToDouble(state -> state.wallQi().totalQi())
                .sum();
        double organQi = organStates.stream()
                .mapToDouble(AnatomyState.Organ::occupiedQi)
                .sum();
        double transitQi = transits.stream()
                .mapToDouble(transit -> transit.cargo().totalQi())
                .sum();
        DataChecks.nonNegative(lumenQi + wallQi + organQi + transitQi, "身体真元总量");
    }

    /** 内腔总量也不代表任意目标都能立即使用，实际还受通路与操作规则约束。 */
    public double lumenQiTotal() {
        // 只汇总内腔；外壁储备与尚未到达的真元不能作为内腔余额。
        return meridianStates.stream()
                .mapToDouble(state -> state.lumenQi().totalQi())
                .sum();
    }

    public double wallQiTotal() {
        // 外壁总量用于储备展示，不代表可以直接施招。
        return meridianStates.stream()
                .mapToDouble(state -> state.wallQi().totalQi())
                .sum();
    }

    public double transitQiTotal() {
        // 在途是独立归属；运行层必须先扣源，避免两处同时记账。
        return transits.stream()
                .mapToDouble(transit -> transit.cargo().totalQi())
                .sum();
    }

    /** 汇总始终从唯一明细派生，不接受额外的可写总余额。 */
    public double totalQi() {
        // 每次从明细派生总量，避免额外维护一份可漂移的余额。
        return lumenQiTotal() + wallQiTotal() + transitQiTotal() + organQiTotal();
    }

    public double organQiTotal() {
        // 器官缓存和加工中的原料各计一次，不把预计产物计入余额。
        return organStates.stream()
                .mapToDouble(AnatomyState.Organ::occupiedQi)
                .sum();
    }

    public double partHpRatio() {
        // 仅部位参与全身生命比例，内部组织与器官不再次计入。
        double currentPartHp = partStates.stream()
                .mapToDouble(AnatomyState.Part::hp)
                .sum();
        double maximumPartHp = structure.parts().stream()
                .mapToDouble(Anatomy.PartDefinition::maxHp)
                .sum();
        return currentPartHp / maximumPartHp;
    }

    private static <K, D, S> Map<K, D> match(List<D> definitions, Function<D, K> definitionId,
                                           List<S> states, Function<S, K> stateId, String label) {
        // 分别拒绝重复 ID，再比较集合以同时识别缺失状态和多余状态。
        var definitionIndex = DataChecks.index(definitions, definitionId, label);
        var stateIndex = DataChecks.index(states, stateId, label + "状态");
        if (!definitionIndex.keySet().equals(stateIndex.keySet())) {
            throw MeridianErrors.invalid(Data.stateMismatch(label));
        }
        return definitionIndex;
    }
}
