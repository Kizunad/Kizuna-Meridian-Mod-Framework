package dev.kizuna.meridian.server;

/** 可信附属的宿主入口；交换 health_host.proto 字节，绑定和权限由宿主验证。 */
@FunctionalInterface
public interface BodyHealthHost {
    byte[] exchange(byte[] request);
}
