package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.*;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/** 不持有身体状态的可信附属策略目录；规则判断全部交给对应附属。 */
final class OpeningPolicies {
    private static final Map<String, OpeningPolicy> POLICIES = discover();

    private OpeningPolicies() {
        // 目录只保存已安装的执行器，不能替代身体事务或保存进度。
    }

    private static Map<String, OpeningPolicy> discover() {
        // Java 参考宿主通过标准 SPI 发现适配器；适配器也可转发同一协议到其他语言核心。
        var policies = new HashMap<String, OpeningPolicy>();
        for (OpeningPolicy policy : ServiceLoader.load(OpeningPolicy.class)) {
            new DefinitionId(policy.id());
            if (policies.putIfAbsent(policy.id(), policy) != null) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.POLICY_INVALID);
            }
        }
        return Map.copyOf(policies);
    }

    static boolean available(MeridianOpeningRule rule) {
        // 缺失执行器时停机，不将其他生物的规则作为默认兜底。
        return POLICIES.containsKey(rule.policyId().value());
    }

    static OpeningPolicyDecision evaluate(BodySnapshot body, MeridianOpeningRule rule,
                                          OpeningPolicyPhase phase, long intervalMillis) {
        // 只传不可变快照与固定规则；附属得到裁定权，但没有 Store 或可写身体引用。
        OpeningPolicy policy = POLICIES.get(rule.policyId().value());
        if (policy == null) {
            throw MeridianErrors.rejectIntake(
                    dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.OPENING_POLICY_UNAVAILABLE);
        }
        var request = OpeningPolicyRequest.newBuilder().setProtocolVersion(1)
                .setBody(BodyProtobuf.toMessage(body)).setRule(OpeningProtobuf.toRule(rule))
                .setPhase(phase).setIntervalMillis(intervalMillis).build();
        OpeningPolicyDecision decision = OpeningProtobuf.decodePolicyDecision(policy.evaluate(request.toByteArray()));
        if (decision.getRejection() != dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.FAILURE_CODE_UNSPECIFIED) {
            throw MeridianErrors.rejectIntake(decision.getRejection());
        }
        return decision;
    }
}
