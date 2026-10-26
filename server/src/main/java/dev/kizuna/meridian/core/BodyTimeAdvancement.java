package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejectionReason;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 时间与自然寿命的纯计算；可信时间、调度和原子提交由宿主提供。 */
public final class BodyTimeAdvancement {
    private static final long MILLIS_PER_SECOND = 1_000;
    private BodyTimeAdvancement() {
        // 算子只处理调用时提供的区间，不读取系统时间，也不启动逐帧任务。
    }

    public enum Mode { ACTIVE, LIFESPAN_ONLY }

    public record Plan(TimeCursor target, double biologicalSecondsPerSimulationSecond, Mode mode) {
        public Plan {
            // 零倍率表示暂停老化；它不隐含暂停运输，运输是否冻结由模式明确决定。
            MeridianErrors.required(target, Data.SETTLED_AT_REQUIRED);
            DataChecks.nonNegative(biologicalSecondsPerSimulationSecond, "老化倍率");
            MeridianErrors.required(mode, Data.TIME_MODE_REQUIRED);
        }
    }

    public record Result(BodySnapshot body, boolean died, QiInventory releasedQi) {
        public Result {
            // 结果携带候选身体和待转入环境的明细，调用算子本身不会完成提交。
            MeridianErrors.required(body, Data.BODY_REQUIRED);
            MeridianErrors.required(releasedQi, Data.QI_REQUIRED);
        }
    }

    public static Result apply(BodySnapshot body, Plan plan) {
        // 普通时间推进按当前动作占用计算；摄入附带的区间做功另由重载补充。
        return apply(body, plan, Set.of());
    }

    public static Result apply(BodySnapshot body, Plan plan, Set<Anatomy.TissueId> intervalUsedTissues) {
        // 信任边界校验一次，内部候选复用曲线和组织索引，整次推进只生成一个快照。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        MeridianErrors.required(plan, Data.TIME_PLAN_REQUIRED);
        validateTime(body, plan);
        OpeningTask task = body.openingTask();
        if (plan.mode() == Mode.LIFESPAN_ONLY && (task == null || !task.active())) {
            return advanceFrozen(body, plan, intervalUsedTissues);
        }
        var current = new BodyAdvancement(body, intervalUsedTissues);
        if (plan.mode() == Mode.LIFESPAN_ONLY) {
            // 普通冻结已直接结算；活动开脉还须先解除占用、保存本次尝试的终止事实。
            OpeningRuntime.freeze(current, plan);
            return current.result();
        }
        TimeCursor target = plan.target();
        long targetMillis = target.elapsedMillis();
        CapabilityCurves.Context curves = current.curves();
        while (true) {
            long boundaryMillis = curves.nextBoundary(current.time.elapsedMillis(), targetMillis);
            Plan intervalPlan = plan;
            if (boundaryMillis < targetMillis) {
                var boundary = new TimeCursor(target.domain(), target.epoch(), boundaryMillis);
                intervalPlan = new Plan(boundary, plan.biologicalSecondsPerSimulationSecond(), plan.mode());
            }
            if (current.task != null && current.task.active()) {
                OpeningRuntime.advance(current, intervalPlan);
            } else {
                advanceInterval(current, intervalPlan);
            }
            if (current.vitality.dead() || current.time.elapsedMillis() == targetMillis) {
                return current.result();
            }
        }
    }

    private static Result advanceFrozen(BodySnapshot body, Plan plan, Set<Anatomy.TissueId> intervalUsedTissues) {
        // 冻结只结算一个区间，直接复用寿命和资源算子，避免为日常输入建立活动候选。
        Set<Anatomy.TissueId> usedIds = Set.copyOf(intervalUsedTissues);
        if (!usedIds.isEmpty()) {
            var definitions = DataChecks.index(body.structure().tissues(), Anatomy.TissueDefinition::id, "组织");
            if (!definitions.keySet().containsAll(usedIds)) {
                throw MeridianErrors.invalid(Data.INVALID_ACTION_STATE);
            }
        }
        Interval interval = interval(body, plan);
        double ageSeconds = ageAfterInterval(body.vitality(), plan, interval);
        IntervalResources resources = advanceResources(body, plan, interval);
        requireProgress(body, interval, resources);
        boolean died = interval.outcome() == LifeOutcome.DIED;
        VitalityState previous = body.vitality();
        var vitality = new VitalityState(ageSeconds, previous.lifespanLimitSeconds(),
                previous.spentLifespanSeconds(), died);
        List<BodyAction.Active> actions = body.actions();
        OpeningTask task = body.openingTask();
        if (died) {
            actions = List.of();
            task = null;
        }
        List<CapabilityModifier> modifiers = BodyAdvancement.remainingModifiers(body, interval.settledAt(), vitality);
        var snapshot = new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(),
                body.realm(), body.partStates(), body.tissueStates(), resources.organs(), resources.meridians(),
                resources.transits(), vitality, interval.settledAt(), body.revision() + 1,
                actions, task, body.addonProgress(), modifiers);
        return new Result(snapshot, died, resources.released());
    }

    private static void validateTime(BodyView body, Plan plan) {
        // 1. 校验时间身份与身体状态；不同世代不能直接折算经历时长。
        TimeCursor start = body.settledAt();
        TimeCursor target = plan.target();
        if (!start.domain().equals(target.domain()) || !start.epoch().equals(target.epoch())) {
            throw MeridianErrors.rejectTime(TimeRejectionReason.CLOCK_MISMATCH);
        }
        VitalityState vitality = body.vitality();
        if (vitality.dead()) {
            throw MeridianErrors.rejectTime(TimeRejectionReason.BODY_DEAD);
        }
        if (target.elapsedMillis() < start.elapsedMillis()) {
            throw MeridianErrors.rejectTime(TimeRejectionReason.TIME_NOT_ADVANCED);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectTime(TimeRejectionReason.REVISION_EXHAUSTED);
        }
    }

    static void advanceInterval(BodyAdvancement body, Plan plan) {
        // 寿尽先裁剪区间；所有局部候选算完后再一起更新游标和资源，异常时整次事务丢弃。
        VitalityState vitality = body.vitality;
        Interval interval = interval(body, plan);
        long elapsedMillis = interval.elapsedMillis();
        boolean died = interval.outcome() == LifeOutcome.DIED;
        // 恢复和年龄共用已经裁到寿尽的区间；没有逐秒循环，也不会把死亡后的时间用于恢复。
        List<AnatomyState.Tissue> tissues = TissueRecovery.advance(body, plan, elapsedMillis);
        double updatedAgeSeconds = ageAfterInterval(vitality, plan, interval);
        IntervalResources resources = advanceResources(body, plan, interval);

        requireProgress(body, interval, resources);

        body.tissues = tissues;
        body.meridians = resources.meridians();
        body.organs = resources.organs();
        body.transits = resources.transits();
        body.released = QiAccounting.credit(body.released, resources.released());
        body.vitality = new VitalityState(updatedAgeSeconds, vitality.lifespanLimitSeconds(),
                vitality.spentLifespanSeconds(), died);
        body.time = interval.settledAt();
        if (died) {
            body.actions = List.of();
            body.task = null;
        }
    }

    private enum LifeOutcome { ALIVE, DIED }

    private record Interval(TimeCursor settledAt, long elapsedMillis, LifeOutcome outcome) { }

    private static Interval interval(BodyView body, Plan plan) {
        // 直接定位寿命阈值，长时间离线也只算到死亡边界。
        Optional<TimeCursor> deathAt = nextLifespanBoundary(body, plan.biologicalSecondsPerSimulationSecond());
        TimeCursor target = plan.target();
        if (deathAt.isPresent() && deathAt.get().elapsedMillis() <= target.elapsedMillis()) {
            TimeCursor settledAt = deathAt.get();
            long elapsedMillis = settledAt.elapsedMillis() - body.settledAt().elapsedMillis();
            return new Interval(settledAt, elapsedMillis, LifeOutcome.DIED);
        }
        return new Interval(target, target.elapsedMillis() - body.settledAt().elapsedMillis(), LifeOutcome.ALIVE);
    }

    private static void requireProgress(BodyView body, Interval interval, IntervalResources resources) {
        // 同毫秒可以重试阻塞事件；没有实际变化时不生成空提交，寿尽清账则始终有效。
        if (interval.outcome() == LifeOutcome.ALIVE && interval.elapsedMillis() == 0) {
            boolean resourcesUnchanged = resources.meridians().equals(body.meridianStates())
                    && resources.transits().equals(body.transits()) && resources.organs().equals(body.organStates());
            if (resourcesUnchanged) {
                throw MeridianErrors.rejectTime(TimeRejectionReason.TIME_NOT_ADVANCED);
            }
        }
    }

    private static double ageAfterInterval(VitalityState vitality, Plan plan, Interval interval) {
        // 保留逐事件段的年龄舍入；到达寿尽时只消耗剩余寿命。
        if (interval.outcome() == LifeOutcome.DIED) {
            return ageAtDeath(vitality);
        }
        long elapsedMillis = interval.elapsedMillis();
        double elapsedSeconds = elapsedMillis / (double) MILLIS_PER_SECOND;
        double ageIncreaseSeconds = elapsedSeconds * plan.biologicalSecondsPerSimulationSecond();
        double updatedAgeSeconds = vitality.ageSeconds() + ageIncreaseSeconds;
        if (!Double.isFinite(updatedAgeSeconds)) {
            throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
        }

        boolean shouldAgeIncrease = elapsedMillis > 0 && plan.biologicalSecondsPerSimulationSecond() > 0;
        if (shouldAgeIncrease) {
            // 不可见增量不能只推进游标，否则这段寿命将永久漏算；由宿主合并更长区间再试。
            boolean increaseLostToPrecision = ageIncreaseSeconds == 0 || updatedAgeSeconds <= vitality.ageSeconds();
            double remainingLifespanSeconds = vitality.lifespanLimitSeconds() - updatedAgeSeconds
                    - vitality.spentLifespanSeconds();
            if (increaseLostToPrecision || remainingLifespanSeconds <= 0) {
                throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
            }
        }
        return updatedAgeSeconds;
    }

    private static double ageAtDeath(VitalityState vitality) {
        // 相减舍入可能留下极小正余量，向上一个可表示值确保死亡与零余额一致。
        double ageAtLifespanLimit = vitality.lifespanLimitSeconds() - vitality.spentLifespanSeconds();
        double terminalAgeSeconds = StrictMath.max(vitality.ageSeconds(), ageAtLifespanLimit);
        double remainingLifespanSeconds = vitality.lifespanLimitSeconds() - terminalAgeSeconds
                - vitality.spentLifespanSeconds();
        if (remainingLifespanSeconds > 0) {
            return StrictMath.nextUp(terminalAgeSeconds);
        }
        return terminalAgeSeconds;
    }

    private record IntervalResources(List<MeridianState> meridians, List<AnatomyState.Organ> organs,
                                     List<QiTransit> transits, QiInventory released) { }

    private static IntervalResources advanceResources(BodyView body, Plan plan, Interval interval) {
        // 正常运行按资源事件先后结算，死亡优先于同毫秒到达和加工完成。
        boolean died = interval.outcome() == LifeOutcome.DIED;
        long elapsedMillis = interval.elapsedMillis();
        List<MeridianState> meridians = body.meridianStates();
        List<AnatomyState.Organ> organs = body.organStates();
        List<QiTransit> transits = body.transits();
        if (plan.mode() == Mode.LIFESPAN_ONLY && !died) {
            return postponeResources(meridians, organs, transits, elapsedMillis);
        }
        QiInventory releasedQi = QiInventory.empty();
        if (plan.mode() == Mode.ACTIVE) {
            // 合并到达与加工事件；同毫秒死亡优先，尚未转换的货物随后直接释放。
            BodyResourceEvents.Boundary boundary = BodyResourceEvents.Boundary.INCLUDED;
            if (died) {
                boundary = BodyResourceEvents.Boundary.EXCLUDED;
            }
            BodyResourceEvents.Result resourceResult = BodyResourceEvents.advance(body,
                    interval.settledAt().elapsedMillis(), boundary);
            meridians = resourceResult.meridians();
            organs = resourceResult.organs();
            transits = resourceResult.transits();
            releasedQi = resourceResult.released();
            organs = OpeningEfforts.recover(body, organs, elapsedMillis);
        }

        // 4. 死亡释放全部余额；仅推进寿命的模式则把尚未完成的资源事件整体后移。
        if (died) {
            // 寿尽与伤害死亡共用唯一清账规则，已完成加工的损耗另计一次。
            BodyDeath.Released death = BodyDeath.release(meridians, organs, transits);
            releasedQi = QiInventory.combine(List.of(releasedQi, death.qi()));
            meridians = death.meridians();
            transits = List.of();
            organs = death.organs();
        }
        return new IntervalResources(meridians, organs, transits, releasedQi);
    }

    private static IntervalResources postponeResources(List<MeridianState> meridians,
            List<AnatomyState.Organ> organs, List<QiTransit> transits, long elapsedMillis) {
        // 冻结保留剩余时长，已经阻塞的历史到达和完成事实不平移。
        var postponedTransits = new ArrayList<QiTransit>();
        for (var transit : transits) {
            if (transit.status() != QiTransit.Status.MOVING) {
                postponedTransits.add(transit);
                continue;
            }
            if (transit.arrivalMillis() > Long.MAX_VALUE - elapsedMillis) {
                throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
            }
            long postponedArrivalMillis = transit.arrivalMillis() + elapsedMillis;
            postponedTransits.add(new QiTransit(transit.id(), transit.connectionId(), transit.cargo(),
                    postponedArrivalMillis, transit.throughputPerSecond(), transit.status(), transit.circuit()));
        }
        List<QiTransit> updatedTransits = List.copyOf(postponedTransits);
        List<AnatomyState.Organ> updatedOrgans = postponeOrgans(organs, elapsedMillis);
        return new IntervalResources(meridians, updatedOrgans, updatedTransits, QiInventory.empty());
    }

    private static List<AnatomyState.Organ> postponeOrgans(List<AnatomyState.Organ> organs, long elapsedMillis) {
        // 加工冻结遵守与运输相同的时钟溢出约束。
        var postponedOrgans = new ArrayList<AnatomyState.Organ>();
        for (var organ : organs) {
            OrganProcess process = organ.process();
            if (process == null || process.blocked()) {
                postponedOrgans.add(organ);
                continue;
            }
            if (process.completionMillis() > Long.MAX_VALUE - elapsedMillis) {
                throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
            }
            long postponedCompletionMillis = process.completionMillis() + elapsedMillis;
            var postponedProcess = new OrganProcess(process.recipe(), process.input(),
                    postponedCompletionMillis, process.blocked());
            var postponedOrgan = new AnatomyState.Organ(organ.id(), organ.hp(), organ.buffer(),
                    postponedProcess, organ.fatigueJoules());
            postponedOrgans.add(postponedOrgan);
        }
        return List.copyOf(postponedOrgans);
    }

    public static Optional<TimeCursor> nextLifespanBoundary(BodySnapshot body, double agingRate) {
        // 宿主可用此边界安排唤醒；死亡、支出或倍率改变后重新计算，不需每帧扫描所有身体。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        return nextLifespanBoundary((BodyView) body, agingRate);
    }

    static Optional<TimeCursor> nextLifespanBoundary(BodyView body, double agingRate) {
        // 内部候选直接使用当前寿命和游标，避免为边界查询构造快照。
        DataChecks.nonNegative(agingRate, "老化倍率");
        if (body.vitality().dead()) {
            return Optional.empty();
        }
        double remainingLifespanSeconds = body.vitality().remainingSeconds();
        if (remainingLifespanSeconds == 0) {
            return Optional.of(body.settledAt());
        }
        if (agingRate == 0) {
            return Optional.empty();
        }

        // 向上取整到首个可表示的模拟毫秒；十进制运算避免 double 转 long 吞掉大时钟的毫秒。
        BigDecimal delayMillis = BigDecimal.valueOf(remainingLifespanSeconds)
                .multiply(BigDecimal.valueOf(MILLIS_PER_SECOND))
                .divide(BigDecimal.valueOf(agingRate), 0, RoundingMode.CEILING);
        long remainingClockMillis = Long.MAX_VALUE - body.settledAt().elapsedMillis();
        if (delayMillis.compareTo(BigDecimal.valueOf(remainingClockMillis)) > 0) {
            // 当前时钟范围内不存在可调度的寿命边界，不能溢出成负时间。
            return Optional.empty();
        }
        TimeCursor start = body.settledAt();
        long deathMillis = start.elapsedMillis() + delayMillis.longValueExact();
        return Optional.of(new TimeCursor(start.domain(), start.epoch(), deathMillis));
    }

    public static Optional<TimeCursor> nextEventBoundary(BodySnapshot body, double agingRate, Mode mode) {
        // 冻结运输时仍安排寿尽与修正曲线边界，期限始终使用身体时钟。
        MeridianErrors.required(mode, Data.TIME_MODE_REQUIRED);
        Optional<TimeCursor> stateBoundary = earliestBoundary(nextLifespanBoundary(body, agingRate),
                CapabilityModifier.nextBoundary(body));
        if (mode != Mode.ACTIVE) {
            return stateBoundary;
        }

        // 先选最早的资源事件，再与寿尽比较；同毫秒始终优先寿尽。
        Optional<TimeCursor> arrivalBoundary = QiTransport.nextArrivalBoundary(body);
        Optional<TimeCursor> completionBoundary = OrganProcessing.nextCompletionBoundary(body);
        Optional<TimeCursor> resourceBoundary = arrivalBoundary;
        if (completionBoundary.isPresent()) {
            long completionMillis = completionBoundary.get().elapsedMillis();
            if (arrivalBoundary.isEmpty() || completionMillis < arrivalBoundary.get().elapsedMillis()) {
                resourceBoundary = completionBoundary;
            }
        }
        Optional<TimeCursor> diffusionBoundary = PassiveQiDiffusion.nextBoundary(body);
        if (diffusionBoundary.isPresent()) {
            // 资源变化后可同毫秒启动首批；最小批次阈值保证平衡状态不会不停自唤醒。
            resourceBoundary = diffusionBoundary;
        }
        Optional<TimeCursor> openingBoundary = OpeningRuntime.nextBoundary(body);
        if (openingBoundary.isPresent() && (resourceBoundary.isEmpty()
                || openingBoundary.get().elapsedMillis() < resourceBoundary.get().elapsedMillis())) {
            resourceBoundary = openingBoundary;
        }
        return earliestBoundary(stateBoundary, resourceBoundary);
    }

    private static Optional<TimeCursor> earliestBoundary(Optional<TimeCursor> first, Optional<TimeCursor> second) {
        // 相同毫秒保留第一个事件；寿尽的清账优先级仍由区间结算决定。
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty() || first.get().elapsedMillis() <= second.get().elapsedMillis()) {
            return first;
        }
        return second;
    }
}
