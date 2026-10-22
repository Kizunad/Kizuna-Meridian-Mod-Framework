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
        double targetCapacity = body.getStructure().getMeridiansList().stream()
                .filter(definition -> definition.getId().equals(rule.getTargetMeridianId()))
                .findFirst().orElseThrow().getLumenVolume();
        double thresholdVolume = targetCapacity * .8;
        if (request.getPhase() == OpeningPolicyPhase.PREPARE_OPENING) {
            double capacity = body.getStructure().getMeridiansList().stream()
                    .filter(definition -> definition.getId().equals(rule.getTargetMeridianId()))
                    .findFirst().orElseThrow().getLumenVolume();
            decision.addParticipants(OpeningGuidanceTarget.newBuilder().setMeridianId(rule.getTargetMeridianId()).setVolume(thresholdVolume))
                    .setRequiredMillis(conditions.getDurationMillis()).setTargetStableVolume(capacity + rule.getLumenVolumeGain());
            if (conditions.getRequiresIntake()) {
                decision.addObservedOrganIds("filter").setReceiptWindowMillis(1000);
            }
            return decision.build().toByteArray();
        }
        var task = body.getOpeningTask();
        // 维持费用按全身实际占用体积分摊，投影必须和框架实际扣账一致。
        double targetVolume = targetVolume(body, rule.getTargetMeridianId());
        double rate = task.getStatus() == OpeningTaskStatus.OPENING_HOLDING ? rule.getHoldQiPerSecond() : 0;
        double projectedCharge = targetChargeVolume(body, rule.getTargetMeridianId(),
                rate * request.getIntervalMillis() / 1000.0);
        boolean ready = targetVolume - projectedCharge + 1e-9 >= thresholdVolume;
        double delay = nextQiThresholdBoundary(body, rule, thresholdVolume, rate);
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
