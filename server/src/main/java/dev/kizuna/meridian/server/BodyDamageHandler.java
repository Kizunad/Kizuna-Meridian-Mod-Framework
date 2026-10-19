package dev.kizuna.meridian.server;

/**
 * 可信 Addon 的伤害接口：接收 health.proto 的 DamageContext bytes，返回 DamageResolution bytes。
 * 宿主按身体模板选择实现；回调只计算方案，不写账本、不发副作用。普通网络不暴露此接口。
 */
@FunctionalInterface
public interface BodyDamageHandler {
    byte[] resolve(byte[] context);
}
