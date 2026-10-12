package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;

/** 宿主的可信时间与离线策略入口；以 protoc 消息描述，任何核心语言均可实现同一契约。 */
@FunctionalInterface
public interface BodyTimeSource<C> {
    /**
     * 在已授权、版本匹配的时间或运输事务内返回目标游标、固定倍率和推进模式。
     * 发运要求目标等于身体已结算游标，宿主可先以同一逻辑时刻结算，再用新版本发运。
     * 不得采用客户端自报离线时长；不得消费资源或修改宿主状态。
     * 已有成功回执的重试不调用此入口；单机暂停可返回当前游标，表示无需推进。
     * 倍率/模式改变前须先按旧策略结算；跨世代恢复须由宿主显式迁移，不能在这里重置游标。
     */
    TimeAdvancePlan resolve(C trustedContext, BodySnapshot current);
}
