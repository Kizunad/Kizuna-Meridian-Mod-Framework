package dev.kizuna.meridian.core;

/** Java 宿主的策略发现适配；跨模块输入输出均为 opening_policy.proto 的消息。 */
public interface OpeningPolicy {
    String id();

    byte[] evaluate(byte[] request);
}
