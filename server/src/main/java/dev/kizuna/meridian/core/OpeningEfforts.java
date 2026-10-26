package dev.kizuna.meridian.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 开脉连续负载的预算和扣费，器官疲劳与组织精力分别结算。 */
final class OpeningEfforts {
    private static final double MILLIS_PER_SECOND = 1_000;
    private static final double HALF = 0.5;
    private static final int BUDGET_ITERATIONS = 64;
    private static final double LOG_TWO = StrictMath.log(2);
    private static final double RECOVERED_CAPACITY_FRACTION = 0.000001;

    record Budget(List<AnatomyState.Tissue> tissues, List<AnatomyState.Organ> organs, long nowMillis,
                  MeridianOpeningRule rule, double seconds) { }
    private OpeningEfforts() {
        // 时间执行器在耗尽边界截断区间，因此不会透支后再补偿。
    }

    static double availableSeconds(BodySnapshot body) {
        // 外部调度建立一次只读预算上下文，连续推进则复用现有上下文。
        return availableSeconds(new BodyAdvancement(body, Set.of()));
    }

    static double availableSeconds(BodyAdvancement body) {
        // 持续功率也受疲劳限制，达到无法维持所需功率的时刻即停止。
        Budget cached = body.effortBudget;
        MeridianOpeningRule rule = body.task.rule();
        long nowMillis = body.time.elapsedMillis();
        if (cached != null && cached.tissues() == body.tissues && cached.organs() == body.organs
                && cached.nowMillis() == nowMillis && cached.rule() == rule) {
            return cached.seconds();
        }
        double availableSeconds = Double.POSITIVE_INFINITY;
        for (MeridianOpeningRule.Effort effort : body.openingTask().rule().efforts()) {
            double availableWorkJoules;
            if (effort.moduleId() instanceof Anatomy.TissueId id) {
                double tissueSeconds = tissueAvailableSeconds(body, id, effort.watts());
                availableSeconds = StrictMath.min(availableSeconds, tissueSeconds);
                continue;
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
                availableWorkJoules = StrictMath.max(0, remainingWorkJoules - powerReserveJoules);
            }
            availableSeconds = StrictMath.min(availableSeconds, availableWorkJoules / effort.watts());
        }
        body.effortBudget = new Budget(body.tissues, body.organs, nowMillis, rule, availableSeconds);
        return availableSeconds;
    }

    private static double tissueAvailableSeconds(BodyAdvancement body, Anatomy.TissueId tissueId, double watts) {
        // 先用当前能力求预算上界；最近曲线边界之后斜率或阶跃会改变，须到下一段重新计算。
        Anatomy.TissueCapacity current = TissueCapabilities.available(body, tissueId);
        double currentSeconds = workReserve(current, watts) / watts;
        long nowMillis = body.time.elapsedMillis();
        CapabilityCurves.Context curves = body.curves();
        boolean hasModifiers = curves.hasModifiers(tissueId, nowMillis);
        if (!hasModifiers || currentSeconds == 0) {
            return currentSeconds;
        }
        long boundaryMillis = curves.nextBoundary(nowMillis, Long.MAX_VALUE);
        double boundarySeconds = (boundaryMillis - nowMillis) / MILLIS_PER_SECOND;
        double maximumSeconds = StrictMath.min(currentSeconds, boundarySeconds);
        // 上界已经可维持时直接采用，避免在短曲线段内重复求解同一个预算。
        if (canSustain(body, tissueId, watts, maximumSeconds)) {
            return maximumSeconds;
        }
        // low 是已确认可维持的时长，high 是已确认无法维持的搜索上界。
        // 区间延长只会降低区间最低能力、增加做功需求，因此可维持性可以用二分求解。
        double low = 0;
        double high = maximumSeconds;
        // 64 次折半的理论误差为初始宽度的 2^-64，在默认曲线时长上限内远小于一毫秒。
        for (int iteration = 0; iteration < BUDGET_ITERATIONS; iteration++) {
            double durationSeconds = (low + high) * HALF;
            if (durationSeconds == 0) {
                break;
            }
            if (canSustain(body, tissueId, watts, durationSeconds)) {
                low = durationSeconds;
            } else {
                high = durationSeconds;
            }
        }
        // 浮点舍入使区间无法继续缩小时，仍取已验证可行的下界。
        return low;
    }

    private static boolean canSustain(BodyAdvancement body, Anatomy.TissueId tissueId, double watts,
                                       double durationSeconds) {
        // 区间最低能力须同时覆盖实际做功和维持目标功率的耐力预留。
        var load = new TissueCapabilities.Exertion(tissueId, 0, watts, durationSeconds);
        Anatomy.TissueCapacity capacity = TissueCapabilities.availableDuring(body, load);
        return watts * durationSeconds <= workReserve(capacity, watts);
    }

    private static double workReserve(Anatomy.TissueCapacity capacity, double watts) {
        // 功率随疲劳增加而下降，须保留 watts / burstWatts 比例的有效耐力，才能在做功后维持目标功率。
        if (capacity.burstWatts() <= watts) {
            return 0;
        }
        double powerReserveJoules = watts * capacity.enduranceJoules() / capacity.burstWatts();
        return StrictMath.max(0, capacity.enduranceJoules() - powerReserveJoules);
    }

    static void charge(BodyAdvancement body, long millis) {
        // 费用按这一段实际活动模拟时间计入，与调用次数无关。
        if (millis == 0) {
            return;
        }
        var tissues = new ArrayList<>(body.tissueStates());
        var organs = new ArrayList<>(body.organStates());
        double durationSeconds = millis / MILLIS_PER_SECOND;
        for (MeridianOpeningRule.Effort effort : body.openingTask().rule().efforts()) {
            double workJoules = effort.watts() * durationSeconds;
            if (effort.moduleId() instanceof Anatomy.TissueId id) {
                var exertion = new TissueCapabilities.Exertion(id, 0, effort.watts(), durationSeconds);
                AnatomyState.Tissue charged = TissueCapabilities.exert(body, exertion);
                tissues.set(body.tissuePosition(id), charged);
            } else {
                Anatomy.OrganId id = (Anatomy.OrganId) effort.moduleId();
                chargeOrgan(body, organs, id, workJoules);
            }
        }
        body.tissues = List.copyOf(tissues);
        body.organs = List.copyOf(organs);
    }

    private static void chargeOrgan(BodyView body, List<AnatomyState.Organ> organs, Anatomy.OrganId id,
                                     double workJoules) {
        // 器官的独立耐力按完好率折算，保留缓存和加工事实。
        Anatomy.OrganDefinition definition = body.structure().organs().stream()
                .filter(organ -> organ.id().equals(id)).findFirst().orElseThrow();
        for (int index = 0; index < organs.size(); index++) {
            AnatomyState.Organ state = organs.get(index);
            if (state.id().equals(id)) {
                double healthFraction = organHealth(body, definition, state);
                double fatigueJoules = state.fatigueJoules() + workJoules / healthFraction;
                var charged = new AnatomyState.Organ(id, state.hp(), state.buffer(), state.process(), fatigueJoules);
                organs.set(index, charged);
            }
        }
    }

    static List<AnatomyState.Organ> recover(BodyView body, List<AnatomyState.Organ> organs, long millis) {
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
            double durationSeconds = millis / MILLIS_PER_SECOND;
            // 先乘后除固定舍入位置，避免拆段后疲劳出现额外误差。
            double exponent = -LOG_TWO * durationSeconds / endurance.recoveryHalfLifeSeconds();
            double remainingFraction = StrictMath.exp(exponent);
            double decayedFatigue = state.fatigueJoules() * remainingFraction;
            double fatigueJoules = decayedFatigue;
            if (decayedFatigue / endurance.enduranceJoules() <= RECOVERED_CAPACITY_FRACTION) {
                fatigueJoules = 0;
            }
            var recoveredOrgan = new AnatomyState.Organ(state.id(), state.hp(), state.buffer(),
                    state.process(), fatigueJoules);
            recovered.add(recoveredOrgan);
        }
        return List.copyOf(recovered);
    }

    private static double organHealth(BodyView body, Anatomy.OrganDefinition organ, AnatomyState.Organ state) {
        // 与组织相同，器官和所属部位取较低完好率。
        double partHp = body.partStates().stream().filter(part -> part.id().equals(organ.partId()))
                .findFirst().orElseThrow().hp();
        double maximumHp = body.structure().parts().stream().filter(part -> part.id().equals(organ.partId()))
                .findFirst().orElseThrow().maxHp();
        return StrictMath.min(1, StrictMath.min(state.hp() / organ.maxHp(), partHp / maximumHp));
    }
}
