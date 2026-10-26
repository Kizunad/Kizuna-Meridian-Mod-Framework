package dev.kizuna.meridian.core;

import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.OpeningPolicyDecision;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.OpeningPolicyRequest;

/** Java 宿主的策略发现适配；跨模块输入输出均为 opening_policy.proto 的消息。 */
public interface OpeningPolicy {
    String id();

    OpeningPolicyDecision evaluate(OpeningPolicyRequest request);

    default byte[] evaluate(byte[] payload) {
        // 跨语言或字节调用仍经过完整协议校验；同进程使用同一生成消息避免反复编解码。
        OpeningPolicyRequest request = OpeningProtobuf.decodePolicyRequest(payload);
        OpeningPolicyDecision decision = evaluate(request);
        OpeningProtobuf.validatePolicyDecision(decision);
        return decision.toByteArray();
    }
}
