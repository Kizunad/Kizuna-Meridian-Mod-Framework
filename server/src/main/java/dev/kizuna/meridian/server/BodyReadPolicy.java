package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyId;

/** 宿主查询授权策略；上下文由宿主认证后提供，不从请求中的身体 ID 推导身份。 */
@FunctionalInterface
public interface BodyReadPolicy<C> {
    /** 判断是否允许读取完整快照；私有策略可在宿主实现中组合，但不能修改身体。 */
    boolean canRead(C trustedContext, BodyId bodyId);
}
