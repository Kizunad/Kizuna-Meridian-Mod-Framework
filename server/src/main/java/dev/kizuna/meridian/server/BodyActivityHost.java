package dev.kizuna.meridian.server;

/** 可信附属交换 activity_host.proto 请求与 HealthHostResponse；不得向客户端暴露。 */
@FunctionalInterface
public interface BodyActivityHost {
    byte[] exchange(String callerId, byte[] request);
}
