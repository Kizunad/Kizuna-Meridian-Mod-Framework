package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejectionReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 组织训练负荷的巩固算子；训练成长只改变有效耐力，不改变力量或爆发功率。 */
final class TissueTraining {
    private static final double MILLIS_PER_SECOND = 1_000;
    private static final double LOG_TWO = StrictMath.log(2);

    private TissueTraining() {
        // 训练成长只随统一时间推进结算，不单独修改身体版本。
    }

    static List<AnatomyState.Tissue> advance(BodyAdvancement body, BodyTimeAdvancement.Plan plan,
                                              long elapsedMillis, List<AnatomyState.Tissue> source) {
        // 待巩固负荷只在活动时钟中、组织没有被动作占用时结算。
        if (plan.mode() != BodyTimeAdvancement.Mode.ACTIVE || elapsedMillis == 0) {
            return source;
        }
        Set<Anatomy.TissueId> usedIds = body.occupiedTissues();
        Map<Anatomy.TissueId, Anatomy.TissueDefinition> definitions = body.tissueDefinitions();
        var updatedTissues = new ArrayList<AnatomyState.Tissue>(source.size());
        for (AnatomyState.Tissue tissue : source) {
            Anatomy.TissueDefinition definition = definitions.get(tissue.id());
            Anatomy.TissueTraining training = definition.training();
            boolean disabled = tissue.hp() == 0 || body.partState(definition.partId()).hp() == 0;
            if (training == null || usedIds.contains(tissue.id()) || disabled
                    || tissue.pendingTrainingLoad() == 0) {
                updatedTissues.add(tissue);
                continue;
            }
            updatedTissues.add(consolidate(tissue, training, elapsedMillis));
        }
        return List.copyOf(updatedTissues);
    }

    private static AnatomyState.Tissue consolidate(AnatomyState.Tissue tissue, Anatomy.TissueTraining training,
                                                    long elapsedMillis) {
        // 半衰期先拆出已巩固部分，再按剩余成长空间减速，最后封顶并清理负荷。
        double elapsedSeconds = elapsedMillis / MILLIS_PER_SECOND;
        double halfLives = elapsedSeconds / training.consolidationHalfLifeSeconds();
        double remainingRatio = StrictMath.exp(-LOG_TWO * halfLives);
        double remainingLoad = tissue.pendingTrainingLoad() * remainingRatio;
        double consolidatedLoad = tissue.pendingTrainingLoad() - remainingLoad;
        double growthRoom = training.maximumGrowth() - tissue.trainingGrowth();
        double roomRatio = growthRoom / training.maximumGrowth();
        double growthIncrease = consolidatedLoad / training.workJoulesPerGrowth() * roomRatio;
        double updatedGrowth = StrictMath.min(training.maximumGrowth(), tissue.trainingGrowth() + growthIncrease);
        if (!Double.isFinite(remainingLoad) || !Double.isFinite(updatedGrowth)
                || updatedGrowth < tissue.trainingGrowth()) {
            throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
        }
        double updatedPendingLoad = remainingLoad;
        if (updatedGrowth >= training.maximumGrowth()) {
            updatedPendingLoad = 0;
        }
        return new AnatomyState.Tissue(tissue.id(), tissue.hp(), tissue.fatigue(), updatedGrowth, updatedPendingLoad);
    }
}
