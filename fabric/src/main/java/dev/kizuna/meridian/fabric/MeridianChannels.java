package dev.kizuna.meridian.fabric;

import dev.kizuna.meridian.protocol.MeridianProtocol;
import net.minecraft.resources.Identifier;

/**
 * 身体界面与身体宿主之间的 {@code meridian:*} 字节通道。宿主附属和界面附属都从这里取通道，
 * 通道 ID 与限长只有这一份。
 *
 * <p>六条通道都不带长度前缀，收包方先用 {@link ProtobufChannel#accepts} 丢弃空包与超长包。
 * 首次使用本类时两个方向一并登记，所以与模组初始化顺序无关。
 */
public final class MeridianChannels {
    /** 客户端请求打开或关闭身体视图。 */
    public static final ProtobufChannel BODY_VIEW_REQUEST = ProtobufChannel.unframed(id("body_view_request"), 4096);
    /** 服务端下发身体视图。 */
    public static final ProtobufChannel BODY_VIEW = ProtobufChannel.unframed(id("body_view"),
            MeridianProtocol.MAX_PAYLOAD_BYTES);
    /** 客户端提交聚气请求。 */
    public static final ProtobufChannel GATHERING_REQUEST = ProtobufChannel.unframed(id("gathering_request"),
            MeridianProtocol.MAX_PAYLOAD_BYTES);
    /** 服务端回复聚气结果。 */
    public static final ProtobufChannel GATHERING_RESPONSE = ProtobufChannel.unframed(id("gathering_response"),
            MeridianProtocol.MAX_PAYLOAD_BYTES);
    /** 客户端提交开脉请求。 */
    public static final ProtobufChannel OPENING_REQUEST = ProtobufChannel.unframed(id("opening_request"),
            MeridianProtocol.MAX_PAYLOAD_BYTES);
    /** 服务端回复开脉结果。 */
    public static final ProtobufChannel OPENING_RESPONSE = ProtobufChannel.unframed(id("opening_response"),
            MeridianProtocol.MAX_PAYLOAD_BYTES);

    static {
        // 自定义载荷要在两端都登记才能编解码；放在类初始化里，谁先用到都能拿到已登记的通道。
        BODY_VIEW_REQUEST.registerServerbound();
        GATHERING_REQUEST.registerServerbound();
        OPENING_REQUEST.registerServerbound();
        BODY_VIEW.registerClientbound();
        GATHERING_RESPONSE.registerClientbound();
        OPENING_RESPONSE.registerClientbound();
    }

    private MeridianChannels() {
        // 只放通道常量，不实例化。
    }

    private static Identifier id(String path) {
        // 六条通道沿用 1.20.1 的 meridian 命名空间与路径，线上通道 ID 不变。
        return Identifier.fromNamespaceAndPath("meridian", path);
    }
}
