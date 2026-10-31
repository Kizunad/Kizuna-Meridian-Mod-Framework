package dev.kizuna.meridian.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 一条 Protobuf 字节通道。通道 ID、限长和编解码只在这里定义一份，发送端和接收端共用同一个实例。
 *
 * <p>载荷只携带消息序列化后的字节，按什么消息解析、解析失败怎么处理都由收包方决定。读入时超过限长属于协议错误，
 * 连接会被断开。附属在 {@code onInitialize} 里按用到的方向调用 {@link #registerClientbound()} 或
 * {@link #registerServerbound()}，再用 {@link #type()} 登记收包回调。
 */
public final class ProtobufChannel {
    private final CustomPacketPayload.Type<Payload> type;
    private final StreamCodec<FriendlyByteBuf, Payload> codec;

    public ProtobufChannel(Identifier id, int maxBytes) {
        // 限长只约束读入；写出的是发送方用有限字段构造的消息，原样发送。
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Protobuf 通道限长必须为正数：" + id);
        }
        CustomPacketPayload.Type<Payload> created = new CustomPacketPayload.Type<>(id);
        this.type = created;
        this.codec = StreamCodec.of((buffer, payload) -> buffer.writeByteArray(payload.bytes()),
                buffer -> new Payload(created, buffer.readByteArray(maxBytes)));
    }

    public CustomPacketPayload.Type<Payload> type() {
        // 收包回调和 canSend 都按这个类型查找通道。
        return type;
    }

    public Payload payload(byte[] bytes) {
        // 每次发送新建载荷，字节数组不跨接收者复用。
        return new Payload(type, bytes);
    }

    public void registerClientbound() {
        // 服务端发往客户端的方向；两端都要登记，客户端才能解码、服务端才能编码。
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
    }

    public void registerServerbound() {
        // 客户端发往服务端的方向，登记要求同上。
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
    }

    /** 一份已经序列化的消息；{@code type} 标明所属通道。 */
    public record Payload(CustomPacketPayload.Type<Payload> type, byte[] bytes) implements CustomPacketPayload {
    }
}
