package dev.kizuna.meridian.server;

/**
 * 区域环境与世界时钟的宿主入口，登记在 Fabric entrypoint {@code meridian_environment_host}；
 * 交换宿主定义的环境协议字节，查询规则、绑定和线程约束由宿主验证。
 */
@FunctionalInterface
public interface EnvironmentHostEntrypoint {
    byte[] exchange(byte[] request);
}
