package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;

import java.util.Optional;

/** 宿主只读选择器；必须证明从身体旧游标到 target 全程位于该来源且允许摄入。 */
@FunctionalInterface
public interface BodyEnvironmentSource<C> {
    /** 区间内移动、离线模式或条件改变时应先拆段；empty 表示当前不能提供可信来源。 */
    Optional<String> resolve(C trustedContext, BodySnapshot body, TimeAdvancePlan plan);
}
