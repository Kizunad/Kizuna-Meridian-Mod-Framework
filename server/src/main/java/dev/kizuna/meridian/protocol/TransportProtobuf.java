package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.QiTransport;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

/** 运输意图映射；生成消息通过核心值校验后才可用于执行。 */
public final class TransportProtobuf {
    private TransportProtobuf() {
        // 编解码不保存资源、不查权限，也不自行创建任何在途货物。
    }

    public static QiTransport.Request fromMessage(MeridianMessages.TransportQi request) {
        // 校验未知字段、消息规模和必填项，再检查局部 ID、唯一货物类型与正数吞吐。
        ProtoWire.validate(request);
        return new QiTransport.Request(new Anatomy.ConnectionId(request.getConnectionId()),
                BodyProtobuf.fromInventory(request.getCargo()), request.getThroughputPerSecond());
    }
}
