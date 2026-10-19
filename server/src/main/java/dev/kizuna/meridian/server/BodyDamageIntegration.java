package dev.kizuna.meridian.server;

/** 可信宿主的伤害两阶段集成；输入输出均为 health_host.proto，不向客户端开放。 */
public interface BodyDamageIntegration {
    byte[] prepare(byte[] context);
    void committed(byte[] commit);
}
