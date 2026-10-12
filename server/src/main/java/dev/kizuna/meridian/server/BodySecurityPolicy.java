package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;

/** 私有安全策略扩展点；不能绕过核心校验、修改状态或在回调中产生世界副作用。 */
@FunctionalInterface
public interface BodySecurityPolicy<C> {
    /**
     * 在身体事务内按当前快照检查合法命令；false 拒绝，异常失败关闭。
     * 公共/单机宿主无私有策略时显式提供返回 true 的实现；基础校验仍必经。
     * 已提交请求的重试只重验访问授权，不再次运行策略或消费外部资源。
     */
    boolean allows(C trustedContext, BodyCommand command, BodySnapshot current);
}
