package dev.kizuna.meridian.core;

import java.util.ArrayList;
import java.util.List;

/** 开脉连续负载的预算和扣费，器官疲劳与组织精力分别结算。 */
final class OpeningEfforts {
    private OpeningEfforts() {
        // 时间执行器在耗尽边界截断区间，因此不会透支后再补偿。
    }

    static double availableSeconds(BodySnapshot body) {
        // 持续功率也受疲劳限制，达到无法维持所需功率的时刻即停止。
        double availableSeconds = Double.POSITIVE_INFINITY;
        for (MeridianOpeningRule.Effort effort : body.openingTask().rule().efforts()) {
            double availableWorkJoules;
            if (effort.moduleId() instanceof Anatomy.TissueId id) {
                Anatomy.TissueDefinition definition = body.structure().tissues().stream()
                        .filter(tissue -> tissue.id().equals(id)).findFirst().orElseThrow();
                Anatomy.TissueCapacity capacity = TissueCapabilities.available(body, id);
                if (capacity.burstWatts() <= effort.watts()) {
                    return 0;
                }
                double powerReserveJoules = effort.watts() * definition.capacity().enduranceJoules()
                        / definition.capacity().burstWatts();
                availableWorkJoules = Math.max(0, capacity.enduranceJoules() - powerReserveJoules);
            } else {
                Anatomy.OrganId id = (Anatomy.OrganId) effort.moduleId();
                Anatomy.OrganDefinition definition = body.structure().organs().stream()
                        .filter(organ -> organ.id().equals(id)).findFirst().orElseThrow();
                AnatomyState.Organ state = body.organStates().stream()
                        .filter(organ -> organ.id().equals(id)).findFirst().orElseThrow();
                double healthFraction = organHealth(body, definition, state);
                double enduranceJoules = definition.endurance().enduranceJoules();
                double powerReserveJoules = effort.watts() * enduranceJoules / definition.endurance().maximumWatts();
                double remainingWorkJoules = (enduranceJoules - state.fatigueJoules()) * healthFraction;
                availableWorkJoules = Math.max(0, remainingWorkJoules - powerReserveJoules);
            }
            availableSeconds = Math.min(availableSeconds, availableWorkJoules / effort.watts());
        }
        return availableSeconds;
    }

    static BodySnapshot charge(BodySnapshot body, long millis) {
        // 费用按这一段实际活动模拟时间计入，与调用次数无关。
        if (millis == 0) {
            return body;
        }
        var tissues = new ArrayList<>(body.tissueStates());
        var organs = new ArrayList<>(body.organStates());
        double durationSeconds = millis / 1000.0;
        for (MeridianOpeningRule.Effort effort : body.openingTask().rule().efforts()) {
            double workJoules = effort.watts() * durationSeconds;
            if (effort.moduleId() instanceof Anatomy.TissueId id) {
                BodySnapshot charged = TissueCapabilities.exert(body,
                        new TissueCapabilities.Exertion(id, 0, effort.watts(), durationSeconds));
                for (int index = 0; index < tissues.size(); index++) {
                    if (tissues.get(index).id().equals(id)) {
                        tissues.set(index, charged.tissueStates().get(index));
                    }
                }
            } else {
                Anatomy.OrganId id = (Anatomy.OrganId) effort.moduleId();
                Anatomy.OrganDefinition definition = body.structure().organs().stream()
                        .filter(organ -> organ.id().equals(id)).findFirst().orElseThrow();
                for (int index = 0; index < organs.size(); index++) {
                    AnatomyState.Organ state = organs.get(index);
                    if (state.id().equals(id)) {
                        double healthFraction = organHealth(body, definition, state);
                        double fatigueJoules = state.fatigueJoules() + workJoules / healthFraction;
                        organs.set(index, new AnatomyState.Organ(id, state.hp(), state.buffer(), state.process(), fatigueJoules));
                    }
                }
            }
        }
        return OpeningRuntime.copy(body, body.structure(), tissues, organs, body.meridianStates(), body.transits(),
                body.actions(), body.openingTask(), body.revision());
    }

    static List<AnatomyState.Organ> recover(BodySnapshot body, List<AnatomyState.Organ> organs, long millis) {
        // 空闲器官按半衰期一次求解，正在被任何动作占用的器官不能同时恢复。
        List<Anatomy.OrganId> occupiedOrgans = body.actions().stream()
                .flatMap(action -> action.definition().organIds().stream()).toList();
        var recovered = new ArrayList<AnatomyState.Organ>();
        for (AnatomyState.Organ state : organs) {
            Anatomy.OrganDefinition definition = body.structure().organs().stream()
                    .filter(organ -> organ.id().equals(state.id())).findFirst().orElseThrow();
            Anatomy.OrganEndurance endurance = definition.endurance();
            if (endurance == null || endurance.recoveryHalfLifeSeconds() == 0 || occupiedOrgans.contains(state.id())
                    || state.fatigueJoules() == 0 || organHealth(body, definition, state) == 0) {
                recovered.add(state);
                continue;
            }
            double durationSeconds = millis / 1000.0;
            double remainingFraction = StrictMath.exp(-StrictMath.log(2) * durationSeconds / endurance.recoveryHalfLifeSeconds());
            double fatigueJoules = state.fatigueJoules() * remainingFraction;
            if (fatigueJoules / endurance.enduranceJoules() <= 0.000001) {
                fatigueJoules = 0;
            }
            recovered.add(new AnatomyState.Organ(state.id(), state.hp(), state.buffer(), state.process(), fatigueJoules));
        }
        return List.copyOf(recovered);
    }

    private static double organHealth(BodySnapshot body, Anatomy.OrganDefinition organ, AnatomyState.Organ state) {
        // 与组织相同，器官和所属部位取较低完好率。
        double partHp = body.partStates().stream().filter(part -> part.id().equals(organ.partId()))
                .findFirst().orElseThrow().hp();
        double maximumHp = body.structure().parts().stream().filter(part -> part.id().equals(organ.partId()))
                .findFirst().orElseThrow().maxHp();
        return Math.min(1, Math.min(state.hp() / organ.maxHp(), partHp / maximumHp));
    }
}
