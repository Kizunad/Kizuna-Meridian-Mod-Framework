package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejectionReason;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 时间推进内部的组织恢复算子；疲劳仍是耐力与精力唯一的消耗状态。 */
final class TissueRecovery {
    private TissueRecovery() {
        // 只通过统一时间推进使用，恢复不单独推进版本或提交身体。
    }

    static List<AnatomyState.Tissue> advance(BodySnapshot body, BodyTimeAdvancement.Plan plan,
                                           long elapsedMillis, Set<Anatomy.TissueId> intervalUsedTissues) {
        // 1. 当前全部动作和本次摄入做功共同决定占用，恢复名单不能由客户端声明。
        Map<Anatomy.TissueId, Anatomy.TissueDefinition> definitions = DataChecks.index(
                body.structure().tissues(), Anatomy.TissueDefinition::id, "组织");
        for (Anatomy.TissueId tissueId : intervalUsedTissues) {
            if (!definitions.containsKey(tissueId)) {
                throw MeridianErrors.invalid(Data.INVALID_ACTION_STATE);
            }
        }

        // 2. 离线冻结保留疲劳；有效恢复时间使用模拟秒，不随世界的老化倍率加速。
        if (plan.mode() != BodyTimeAdvancement.Mode.ACTIVE || elapsedMillis == 0) {
            return body.tissueStates();
        }
        double elapsedSeconds = elapsedMillis / 1000.0;
        var usedIds = new HashSet<>(BodyActions.usedTissues(body));
        usedIds.addAll(intervalUsedTissues);
        Map<Anatomy.PartId, AnatomyState.Part> parts = DataChecks.index(
                body.partStates(), AnatomyState.Part::id, "部位状态");
        var updatedTissues = new ArrayList<AnatomyState.Tissue>();

        // 3. 单次遍历各组织，耗时由身体规模决定，与经过了多少秒无关。
        for (AnatomyState.Tissue tissue : body.tissueStates()) {
            Anatomy.TissueDefinition definition = definitions.get(tissue.id());
            double halfLifeSeconds = definition.recoveryHalfLifeSeconds();
            boolean tissueOrPartDisabled = tissue.hp() == 0 || parts.get(definition.partId()).hp() == 0;
            if (usedIds.contains(tissue.id()) || tissueOrPartDisabled || halfLifeSeconds == 0 || tissue.fatigue() == 0) {
                updatedTissues.add(tissue);
                continue;
            }

            // 指数衰减使前期恢复较快，后期逐渐放缓；极长时间的指数下溢自然得到零。
            double halfLives = elapsedSeconds / halfLifeSeconds;
            double remainingRatio = StrictMath.exp(-StrictMath.log(2) * halfLives);
            double updatedFatigue = tissue.fatigue() * remainingRatio;
            if (updatedFatigue / definition.fatigueCapacity() <= 0.000001) {
                // 百万分之一容量以下视为恢复完毕，避免无限接近零而永远没有满恢复状态。
                updatedFatigue = 0;
            }
            if (updatedFatigue >= tissue.fatigue()) {
                // 极小恢复量被浮点吞掉时拒绝整段，交由宿主合并区间后再结算。
                throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
            }
            updatedTissues.add(new AnatomyState.Tissue(tissue.id(), tissue.hp(), updatedFatigue));
        }
        return List.copyOf(updatedTissues);
    }
}
