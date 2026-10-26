package dev.kizuna.meridian.protocol;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.QiConsumption;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.OpeningPolicy;
import dev.kizuna.meridian.fixture.pb.SampleOpeningMessages.SampleOpeningConditions;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;

import java.util.List;
import java.util.function.Consumer;

/** 测试内容包；证明核心可运行非人体策略，不向生产框架提供默认门槛。 */
public final class SampleOpeningPolicy implements OpeningPolicy {
    private static final double THRESHOLD_FRACTION = 0.8;
    private static final double QI_TOLERANCE = 1e-9;
    private static final double DELAY_TOLERANCE_MILLIS = 1e-6;
    private static final long RECEIPT_WINDOW_MILLIS = 1_000;
    private static final double MINIMUM_RECEIVED_QI = 0.1;
    private static final ThreadLocal<Consumer<OpeningPolicyRequest>> REQUEST_OBSERVER = new ThreadLocal<>();
    @Override
    public String id() {
        // 该 SPI 资源仅进入测试 classpath。
        return "sample:opening";
    }

    public static Any configuration(long durationMillis, boolean strict, boolean requiresIntake) {
        // 测试条件也由 protoc 消息表达，不用字符串字段约定或测试专用核心分支。
        return Any.pack(SampleOpeningConditions.newBuilder().setDurationMillis(durationMillis)
                .setStrict(strict).setRequiresIntake(requiresIntake).build());
    }

    @Override
    public OpeningPolicyDecision evaluate(OpeningPolicyRequest request) {
        // 同进程直接检查生成消息；字节入口由 SPI 的协议适配统一校验。
        Consumer<OpeningPolicyRequest> observer = REQUEST_OBSERVER.get();
        if (observer != null) {
            observer.accept(request);
        }
        SampleOpeningConditions conditions = conditions(request);
        var body = request.getBody();
        var rule = request.getRule();
        double targetCapacity = body.getStructure().getMeridiansList().stream()
                .filter(definition -> definition.getId().equals(rule.getTargetMeridianId()))
                .findFirst().orElseThrow().getLumenVolume();
        if (request.getPhase() == OpeningPolicyPhase.PREPARE_OPENING) {
            return prepare(request, conditions, targetCapacity);
        }
        return check(request, conditions, targetCapacity * THRESHOLD_FRACTION);
    }

    static void observeRequests(Consumer<OpeningPolicyRequest> observer) {
        // 观察器限定当前测试线程，用于比较整次与保存后逐段推进的策略可见事实。
        REQUEST_OBSERVER.set(observer);
    }

    static void stopObservingRequests() {
        // 每个测试在 finally 释放观察器，避免影响后续整体回归或独立基准。
        REQUEST_OBSERVER.remove();
    }

    private static SampleOpeningConditions conditions(OpeningPolicyRequest request) {
        // 测试条件仍是附属自己的生成消息，不能用框架私有状态表达门槛。
        try {
            return request.getRule().getPolicyConfig().unpack(SampleOpeningConditions.class);
        } catch (InvalidProtocolBufferException invalid) {
            throw new AssertionError(invalid);
        }
    }

    private static OpeningPolicyDecision prepare(OpeningPolicyRequest request, SampleOpeningConditions conditions,
                                                 double targetCapacity) {
        // 固定启动计划，不在维持回调中修改参与组和成功后的稳定体积。
        var rule = request.getRule();
        var target = OpeningGuidanceTarget.newBuilder()
                .setMeridianId(rule.getTargetMeridianId())
                .setVolume(targetCapacity * THRESHOLD_FRACTION)
                .build();
        var decision = OpeningPolicyDecision.newBuilder()
                .setProtocolVersion(1)
                .addParticipants(target)
                .setRequiredMillis(conditions.getDurationMillis())
                .setTargetStableVolume(targetCapacity + rule.getLumenVolumeGain());
        if (conditions.getRequiresIntake()) {
            decision.addObservedOrganIds("filter");
            decision.setReceiptWindowMillis(RECEIPT_WINDOW_MILLIS);
        }
        return decision.build();
    }

    private static OpeningPolicyDecision check(OpeningPolicyRequest request, SampleOpeningConditions conditions,
                                               double thresholdVolume) {
        // 维持费用按全身实际占用体积分摊，投影必须和框架实际扣账一致。
        var body = request.getBody();
        var rule = request.getRule();
        var decision = OpeningPolicyDecision.newBuilder().setProtocolVersion(1);
        var task = body.getOpeningTask();
        double targetVolume = targetVolume(body, rule.getTargetMeridianId());
        double rate = 0;
        if (task.getStatus() == OpeningTaskStatus.OPENING_HOLDING) {
            rate = rule.getHoldQiPerSecond();
        }
        double projectedCharge = targetChargeVolume(body, rule.getTargetMeridianId(),
                rate * request.getIntervalMillis() / 1000.0);
        boolean ready = targetVolume - projectedCharge + QI_TOLERANCE >= thresholdVolume;
        double delay = nextQiThresholdBoundary(body, rule, thresholdVolume, rate);
        if (conditions.getRequiresIntake()) {
            double received = 0;
            for (OpeningIntakeSample sample : task.getIntakeSamplesList()) {
                long age = body.getSettledAt().getElapsedMillis() - sample.getAtMillis();
                if (age < RECEIPT_WINDOW_MILLIS) {
                    received += sample.getQi();
                    delay = StrictMath.min(delay, RECEIPT_WINDOW_MILLIS - age);
                }
            }
            ready &= received + QI_TOLERANCE >= MINIMUM_RECEIVED_QI;
        }
        if (Double.isFinite(delay) && delay < Long.MAX_VALUE) {
            // 浮点余额可能把整毫秒算成略大于整数，避免每次分段保存累积一毫秒偏移。
            decision.setNextCheckDelayMillis(StrictMath.max(1, (long) StrictMath.ceil(delay - DELAY_TOLERANCE_MILLIS)));
        }
        return decision.setReady(ready).build();
    }

    private static double targetVolume(BodySnapshot body, String targetId) {
        // 目标占用体积由各密度明细直接求和，元数不参与容量判定。
        return body.getMeridianStatesList().stream()
                .filter(state -> state.getId().equals(targetId))
                .findFirst().orElseThrow().getLumenQi().getEntriesList().stream()
                .mapToDouble(qi -> qi.getAmount() / qi.getDensity()).sum();
    }

    private static double targetChargeVolume(BodySnapshot body, String targetId, double chargeQi) {
        // 对全身内腔调用公共分摊算子后，只取目标经脉实际会扣除的体积。
        List<QiInventory> lumenInventories = body.getMeridianStatesList().stream()
                .map(state -> BodyProtobuf.fromInventory(state.getLumenQi())).toList();
        List<QiInventory> charges = QiConsumption.byVolume(lumenInventories, chargeQi);
        for (int index = 0; index < body.getMeridianStatesCount(); index++) {
            if (body.getMeridianStates(index).getId().equals(targetId)) {
                return charges.get(index).volume();
            }
        }
        throw new IllegalArgumentException("开拓目标经脉不存在");
    }

    private static double nextQiThresholdBoundary(BodySnapshot body, MeridianOpeningRule rule,
                                                  double thresholdVolume, double rate) {
        // 通过整数毫秒二分定位首次跌破点，防止分段时间推进跨过资源边界。
        if (rate <= 0 || targetVolume(body, rule.getTargetMeridianId()) <= thresholdVolume) {
            return Double.POSITIVE_INFINITY;
        }
        long lowerMillis = 0;
        long upperMillis = 86_400_000;
        while (lowerMillis + 1 < upperMillis) {
            long middleMillis = lowerMillis + (upperMillis - lowerMillis) / 2;
            double chargedVolume = targetChargeVolume(body, rule.getTargetMeridianId(), rate * middleMillis / 1000.0);
            boolean remainsAbove = targetVolume(body, rule.getTargetMeridianId()) - chargedVolume >= thresholdVolume;
            if (remainsAbove) {
                lowerMillis = middleMillis;
            } else {
                upperMillis = middleMillis;
            }
        }
        return upperMillis;
    }
}
