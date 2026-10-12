package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CommandReceipt;

/** 提交后只读通知，不承担游戏副作用或可靠消息投递。 */
@FunctionalInterface
public interface BodyAuditSink<C> {
    /**
     * 仅在本次调用新提交成功后通知；重试不重复通知，异常只记录日志。
     * 进程崩溃可能丢失通知，需要可靠审计的宿主须把投递记录纳入自身事务。
     */
    void onCommitted(C trustedContext, CommandReceipt receipt, BodySnapshot before, BodySnapshot after);
}
