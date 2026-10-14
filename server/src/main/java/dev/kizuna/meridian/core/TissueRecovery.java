package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejectionReason;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 时间推进内部的组织恢复算子；疲劳仍是耐力与精力唯一的消耗状态。 */
final class TissueRecovery {
    private TissueRecovery() {
        // 只通过统一时间推进使用，恢复不单独推进版本或提交身体。
    }

    static List<AnatomyState.Tissue> advance(BodySnapshot body, BodyTimeAdvancement.Plan plan, long elapsedMillis) {
        // 1. 验证可信计划引用的实际组织；冻结或零时长也不能藏入无效身份。
        if (plan.restingTissueIds().isEmpty()) {
            return body.tissueStates();
        }
        Map<Anatomy.TissueId, Anatomy.TissueDefinition> definitions = DataChecks.index(
                body.structure().tissues(), Anatomy.TissueDefinition::id, "组织");
        for (Anatomy.TissueId tissueId : plan.restingTissueIds()) {
            if (!definitions.containsKey(tissueId)) {
                throw MeridianErrors.invalid(Data.RESTING_TISSUE_NOT_FOUND);
            }
        }

        // 2. 离线冻结保留疲劳；有效恢复时间使用模拟秒，不随世界的老化倍率加速。
        if (plan.mode() != BodyTimeAdvancement.Mode.ACTIVE || elapsedMillis == 0) {
            return body.tissueStates();
        }
        double elapsedSeconds = elapsedMillis / 1000.0;
        var restingIds = new HashSet<>(plan.restingTissueIds());
        Map<Anatomy.PartId, AnatomyState.Part> parts = DataChecks.index(
                body.partStates(), AnatomyState.Part::id, "部位状态");
        var updatedTissues = new ArrayList<AnatomyState.Tissue>();

        // 3. 单次遍历各组织，耗时由身体规模决定，与经过了多少秒无关。
        for (AnatomyState.Tissue tissue : body.tissueStates()) {
            Anatomy.TissueDefinition definition = definitions.get(tissue.id());
            double recoveryPerSecond = definition.restRecoveryPerSecond();
            boolean tissueOrPartDisabled = tissue.hp() == 0 || parts.get(definition.partId()).hp() == 0;
            if (!restingIds.contains(tissue.id()) || tissueOrPartDisabled || recoveryPerSecond == 0 || tissue.fatigue() == 0) {
                updatedTissues.add(tissue);
                continue;
            }

            // 先判断是否恢复完毕，避免长时间或高速率相乘溢出；恢复不修复 HP。
            double secondsToRecover = tissue.fatigue() / recoveryPerSecond;
            double updatedFatigue = 0;
            if (elapsedSeconds < secondsToRecover) {
                double recoveredFatigue = recoveryPerSecond * elapsedSeconds;
                updatedFatigue = Math.max(0, tissue.fatigue() - recoveredFatigue);
                if (updatedFatigue >= tissue.fatigue()) {
                    // 极小恢复量被浮点吞掉时拒绝整段，交由宿主合并区间，防止只移游标而漏记恢复。
                    throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
                }
            }
            updatedTissues.add(new AnatomyState.Tissue(tissue.id(), tissue.hp(), updatedFatigue));
        }
        return List.copyOf(updatedTissues);
    }
}
