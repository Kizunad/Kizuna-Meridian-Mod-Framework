package dev.kizuna.meridian.protocol;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.OpeningPolicy;
import dev.kizuna.meridian.fixture.pb.SampleOpeningMessages.SampleOpeningConditions;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.*;

/** 测试内容包；证明核心可运行非人体策略，不向生产框架提供默认门槛。 */
public final class SampleOpeningPolicy implements OpeningPolicy {
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
    public byte[] evaluate(byte[] payload) {
        // 短时规则仅服务核心跨事件回归，人体门槛由 Human 的整体流程另行保护。
        var request = OpeningProtobuf.decodePolicyRequest(payload);
        var body = request.getBody();
        var rule = request.getRule();
        SampleOpeningConditions conditions;
        try {
            conditions = rule.getPolicyConfig().unpack(SampleOpeningConditions.class);
        } catch (InvalidProtocolBufferException invalid) {
            throw new AssertionError(invalid);
        }
        var decision = OpeningPolicyDecision.newBuilder().setProtocolVersion(1);
        double threshold = rule.getMinimumLumenQi().getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
        if (request.getPhase() == OpeningPolicyPhase.PREPARE_OPENING) {
            double capacity = body.getStructure().getMeridiansList().stream()
                    .filter(definition -> definition.getId().equals(rule.getTargetMeridianId()))
                    .findFirst().orElseThrow().getLumenCapacity();
            decision.addParticipants(OpeningPressureLimit.newBuilder().setMeridianId(rule.getTargetMeridianId()).setQi(threshold))
                    .setRequiredMillis(conditions.getDurationMillis()).setTargetStableCapacity(capacity + rule.getLumenCapacityGain());
            if (conditions.getRequiresIntake()) {
                decision.addObservedOrganIds("filter").setReceiptWindowMillis(1000);
            }
            return decision.build().toByteArray();
        }
        var task = body.getOpeningTask();
        double amount = body.getMeridianStatesList().stream().filter(state -> state.getId().equals(rule.getTargetMeridianId()))
                .findFirst().orElseThrow().getLumenQi().getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
        double rate = task.getStatus() == OpeningTaskStatus.OPENING_HOLDING ? rule.getHoldQiPerSecond() : 0;
        double projected = amount - rate * request.getIntervalMillis() / 1000.0;
        boolean ready = conditions.getStrict() ? projected > threshold + 1e-9 : projected + 1e-9 >= threshold;
        double delay = Double.POSITIVE_INFINITY;
        if (rate > 0) {
            delay = (amount - threshold) / rate * 1000;
            double refillGap = amount - threshold - rule.getGuidanceBufferQi() / 2;
            if (rule.getGuidanceBufferQi() > 0 && refillGap > 1e-9) {
                delay = Math.min(delay, refillGap / rate * 1000);
            }
        }
        if (conditions.getRequiresIntake()) {
            double received = 0;
            for (OpeningIntakeSample sample : task.getIntakeSamplesList()) {
                long age = body.getSettledAt().getElapsedMillis() - sample.getAtMillis();
                if (age < 1000) {
                    received += sample.getQi();
                    delay = Math.min(delay, 1000 - age);
                }
            }
            ready &= received + 1e-9 >= .1;
        }
        if (Double.isFinite(delay) && delay < Long.MAX_VALUE) {
            // 浮点余额可能把整毫秒算成略大于整数，避免每次分段保存累积一毫秒偏移。
            decision.setNextCheckDelayMillis(Math.max(1, (long) Math.ceil(delay - 1e-6)));
        }
        return decision.setReady(ready).build().toByteArray();
    }
}
