package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejectionReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 时间推进内部的组织恢复算子；疲劳仍是耐力与精力唯一的消耗状态。 */
final class TissueRecovery {
    private static final double RECOVERED_CAPACITY_FRACTION = 0.000001;
    private static final double LOG_TWO = StrictMath.log(2);

    private TissueRecovery() {
        // 只通过统一时间推进使用，恢复不单独推进版本或提交身体。
    }

    static List<AnatomyState.Tissue> advance(BodyAdvancement body, BodyTimeAdvancement.Plan plan,
                                           long elapsedMillis) {
        // 离线冻结保留疲劳；占用、定义与部位索引由整次推进建立并校验。
        if (plan.mode() != BodyTimeAdvancement.Mode.ACTIVE || elapsedMillis == 0) {
            return body.tissueStates();
        }
        Set<Anatomy.TissueId> usedIds = body.occupiedTissues();
        Map<Anatomy.TissueId, Anatomy.TissueDefinition> definitions = body.tissueDefinitions();
        var updatedTissues = new ArrayList<AnatomyState.Tissue>();

        // 3. 单次遍历各组织，耗时由身体规模决定，与经过了多少秒无关。
        for (AnatomyState.Tissue tissue : body.tissueStates()) {
            Anatomy.TissueDefinition definition = definitions.get(tissue.id());
            double halfLifeSeconds = definition.recoveryHalfLifeSeconds();
            boolean tissueOrPartDisabled = tissue.hp() == 0 || body.partState(definition.partId()).hp() == 0;
            if (usedIds.contains(tissue.id()) || tissueOrPartDisabled
                    || halfLifeSeconds == 0 || tissue.fatigue() == 0) {
                updatedTissues.add(tissue);
                continue;
            }

            updatedTissues.add(recover(body, tissue, definition, elapsedMillis));
        }
        return List.copyOf(updatedTissues);
    }

    private static AnatomyState.Tissue recover(BodyAdvancement body, AnatomyState.Tissue tissue,
                                               Anatomy.TissueDefinition definition, long elapsedMillis) {
        // 恢复速度对曲线乘积积分；组织占用、失能与冻结由调用方排除。
        double effectiveSeconds = body.curves().recoverySeconds(tissue.id(), body.time.elapsedMillis(), elapsedMillis);
        double halfLives = effectiveSeconds / definition.recoveryHalfLifeSeconds();
        double remainingRatio = StrictMath.exp(-LOG_TWO * halfLives);
        double decayedFatigue = tissue.fatigue() * remainingRatio;
        double updatedFatigue;
        if (decayedFatigue / definition.fatigueCapacity() <= RECOVERED_CAPACITY_FRACTION) {
            // 百万分之一容量以下视为恢复完毕，避免无限接近零而永远没有满恢复状态。
            updatedFatigue = 0;
        } else {
            updatedFatigue = decayedFatigue;
        }
        if (updatedFatigue >= tissue.fatigue()) {
            // 极小恢复量被浮点吞掉时拒绝整段，交由宿主合并区间后再结算。
            throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
        }
        return new AnatomyState.Tissue(tissue.id(), tissue.hp(), updatedFatigue);
    }
}
