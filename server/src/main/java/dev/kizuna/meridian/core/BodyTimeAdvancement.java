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
        // 1. 校验时间身份与身体状态；不同世代不能直接折算经历时长。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        MeridianErrors.required(plan, Data.TIME_PLAN_REQUIRED);
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

        // 2. 直接定位寿命阈值，长时间离线也只算一次；结算区间截止于死亡时刻。
        Optional<TimeCursor> deathAt = nextLifespanBoundary(body, plan.biologicalSecondsPerSimulationSecond());
        boolean died = deathAt.isPresent() && deathAt.get().elapsedMillis() <= target.elapsedMillis();
        TimeCursor settledAt = target;
        if (died) {
            settledAt = deathAt.get();
        }
        long elapsedMillis = settledAt.elapsedMillis() - start.elapsedMillis();
        // 恢复和年龄共用已经裁到寿尽的区间；没有逐秒循环，也不会把死亡后的时间用于恢复。
        List<AnatomyState.Tissue> tissues = TissueRecovery.advance(body, plan, elapsedMillis, intervalUsedTissues);

        double updatedAgeSeconds;
        if (died) {
            // 达到阈值时只消耗剩余寿命；额外支出保持原值，不把死亡后的离线时间计作年龄。
            double ageAtLifespanLimit = vitality.lifespanLimitSeconds() - vitality.spentLifespanSeconds();
            updatedAgeSeconds = Math.max(vitality.ageSeconds(), ageAtLifespanLimit);
            // 两次浮点相减可能留下极小正余量，向上一个可表示值确保死亡与零余额一致。
            double remainingLifespanSeconds = vitality.lifespanLimitSeconds() - updatedAgeSeconds
                    - vitality.spentLifespanSeconds();
            if (remainingLifespanSeconds > 0) {
                updatedAgeSeconds = Math.nextUp(updatedAgeSeconds);
            }
        } else {
            double elapsedSeconds = elapsedMillis / 1000.0;
            double ageIncreaseSeconds = elapsedSeconds * plan.biologicalSecondsPerSimulationSecond();
            updatedAgeSeconds = vitality.ageSeconds() + ageIncreaseSeconds;
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
                    // 尚未到寿尽事件却算出零余额，同样属于不能提交的数值边界。
                    throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
                }
            }
        }

        // 3. 正常运行时按事件推进资源；这里只生成候选状态，不向宿主提交。
        List<MeridianState> meridians = body.meridianStates();
        List<AnatomyState.Organ> organs = body.organStates();
        List<QiTransit> transits = body.transits();
        QiInventory releasedQi = QiInventory.empty();
        if (plan.mode() == Mode.ACTIVE) {
            // 合并到达与加工事件；同毫秒死亡优先，尚未转换的货物随后直接释放。
            BodyResourceEvents.Result resourceResult = BodyResourceEvents.advance(body, settledAt.elapsedMillis(), !died);
            meridians = resourceResult.meridians();
            organs = resourceResult.organs();
            transits = resourceResult.transits();
            releasedQi = resourceResult.released();
        }

        // 4. 死亡释放全部余额；仅推进寿命的模式则把尚未完成的资源事件整体后移。
        if (died) {
            // 所有账户来源各取一次；已完成加工的损耗和死亡释放共同进入环境。
            var inventoriesToRelease = new ArrayList<QiInventory>();
            inventoriesToRelease.add(releasedQi);
            // 清账只释放真元，保留死亡身体原有的开拓与损伤事实。
            var clearedMeridians = new ArrayList<MeridianState>();
            for (var meridian : meridians) {
                inventoriesToRelease.add(meridian.lumenQi());
                inventoriesToRelease.add(meridian.wallQi());
                clearedMeridians.add(new MeridianState(meridian.id(), QiInventory.empty(), QiInventory.empty(),
                        meridian.fatigue(), meridian.condition(), meridian.opening()));
            }
            for (var transit : transits) {
                inventoriesToRelease.add(transit.cargo());
            }
            var clearedOrgans = new ArrayList<AnatomyState.Organ>();
            for (var organ : organs) {
                // 未完成加工只释放输入，不同时发放预计输出。
                inventoriesToRelease.add(organ.buffer());
                if (organ.process() != null) {
                    inventoriesToRelease.add(organ.process().recipe().input());
                }
                clearedOrgans.add(new AnatomyState.Organ(organ.id(), organ.hp(), QiInventory.empty(), null));
            }
            releasedQi = QiInventory.combine(inventoriesToRelease);
            meridians = List.copyOf(clearedMeridians);
            transits = List.of();
            organs = List.copyOf(clearedOrgans);
        } else if (plan.mode() == Mode.LIFESPAN_ONLY) {
            // 冻结运输意味着剩余路程时间不减少；到达时刻随游标一起平移，不会在恢复时瞬间到货。
            var postponedTransits = new ArrayList<QiTransit>();
            for (var transit : transits) {
                if (transit.status() != QiTransit.Status.MOVING) {
                    // 阻塞记录的到达时间是历史事实，只冻结仍在移动的批次。
                    postponedTransits.add(transit);
                    continue;
                }
                if (transit.arrivalMillis() > Long.MAX_VALUE - elapsedMillis) {
                    throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
                }
                long postponedArrivalMillis = transit.arrivalMillis() + elapsedMillis;
                postponedTransits.add(new QiTransit(transit.id(), transit.connectionId(), transit.cargo(),
                        postponedArrivalMillis, transit.throughputPerSecond(), transit.status()));
            }
            transits = List.copyOf(postponedTransits);
            var postponedOrgans = new ArrayList<AnatomyState.Organ>();
            for (var organ : organs) {
                // 加工冻结与运输相同；已阻塞的到期时刻是历史事实，不平移。
                OrganProcess process = organ.process();
                if (process == null || process.blocked()) {
                    postponedOrgans.add(organ);
                    continue;
                }
                if (process.completionMillis() > Long.MAX_VALUE - elapsedMillis) {
                    throw MeridianErrors.rejectTime(TimeRejectionReason.NUMERIC_LIMIT);
                }
                long postponedCompletionMillis = process.completionMillis() + elapsedMillis;
                var postponedProcess = new OrganProcess(process.recipe(), postponedCompletionMillis, false);
                var postponedOrgan = new AnatomyState.Organ(organ.id(), organ.hp(), organ.buffer(), postponedProcess);
                postponedOrgans.add(postponedOrgan);
            }
            organs = List.copyOf(postponedOrgans);
        }

        if (!died && elapsedMillis == 0) {
            // 状态变化可在同毫秒重新尝试阻塞到达；若仍无变化，不创建空提交和新回执。
            boolean resourcesUnchanged = meridians.equals(body.meridianStates())
                    && transits.equals(body.transits())
                    && organs.equals(body.organStates());
            if (resourcesUnchanged) {
                throw MeridianErrors.rejectTime(TimeRejectionReason.TIME_NOT_ADVANCED);
            }
        }

        // 5. 疲劳恢复与寿命、资源事件一起提交，一次区间结算只递增一次版本。
        var updatedVitality = new VitalityState(updatedAgeSeconds, vitality.lifespanLimitSeconds(),
                vitality.spentLifespanSeconds(), died);
        var updatedBody = new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), tissues, organs, meridians, transits,
                updatedVitality, settledAt, body.revision() + 1, died ? List.of() : body.actions());
        return new Result(updatedBody, died, releasedQi);
    }

    public static Optional<TimeCursor> nextLifespanBoundary(BodySnapshot body, double agingRate) {
        // 宿主可用此边界安排唤醒；死亡、支出或倍率改变后重新计算，不需每帧扫描所有身体。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
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
                .multiply(BigDecimal.valueOf(1000))
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
        // 宿主只为最早的寿尽、运输到达或加工完成唤醒；冻结模式仅安排寿尽。
        MeridianErrors.required(mode, Data.TIME_MODE_REQUIRED);
        Optional<TimeCursor> lifespanBoundary = nextLifespanBoundary(body, agingRate);
        if (mode != Mode.ACTIVE) {
            return lifespanBoundary;
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
        if (lifespanBoundary.isEmpty()) {
            return resourceBoundary;
        }
        if (resourceBoundary.isEmpty() || lifespanBoundary.get().elapsedMillis() <= resourceBoundary.get().elapsedMillis()) {
            return lifespanBoundary;
        }
        return resourceBoundary;
    }
}
