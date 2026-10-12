package dev.kizuna.meridian.server;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;

import java.util.Optional;

/** 宿主认证与命令授权接口；拒绝时不读取身体或回执，亦无默认放行。 */
@FunctionalInterface
public interface BodyCommandAccess<C> {
    /**
     * 授权该意图后返回可信、稳定的调用者 ID，拒绝返回 empty。
     * ID 来自已认证上下文，跨连接重连保持一致，不能直接采用客户端提供的身份。
     * 必须检查身体归属、具体操作与剂量许可；拥有读取权不代表可任意燃烧寿元。
     * advance_time 应仅授权宿主调度或明确允许的结算触发者，时间事实始终来自 BodyTimeSource。
     * transport_qi 必须检查路线、货物种类/数量和申请吞吐，不能从读取权推导调元许可。
     * 回调只读，进入事务、返回历史结果或提交前会再次调用；不同身份视为授权失效。
     */
    Optional<String> authorize(C trustedContext, BodyCommand command);
}
