package dev.kizuna.meridian.fabric;

import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 一条 Protobuf 字节通道。通道 ID、限长和编解码只在这里定义一份，发送端和接收端共用同一个实例。
 *
 * <p>载荷只携带消息序列化后的字节，按什么消息解析、解析失败怎么处理都由收包方决定。构造器建立带长度前缀的通道，
 * 读入时超过限长属于协议错误，连接会被断开；{@link #unframed} 建立不带前缀的通道，由收包方用
 * {@link #accepts(Payload)} 丢弃空包与超长包。附属在 {@code onInitialize} 里按用到的方向调用
 * {@link #registerClientbound()} 或 {@link #registerServerbound()}，再用 {@link #type()} 登记收包回调。
 */
public final class ProtobufChannel {
    private final CustomPacketPayload.Type<Payload> type;
    private final StreamCodec<FriendlyByteBuf, Payload> codec;
    private final int maxBytes;

    public ProtobufChannel(Identifier id, int maxBytes) {
        // 限长只约束读入；写出的是发送方用有限字段构造的消息，原样发送。
        this(id, maxBytes, created -> StreamCodec.of((buffer, payload) -> buffer.writeByteArray(payload.bytes()),
                buffer -> new Payload(created, buffer.readByteArray(maxBytes))));
    }

    private ProtobufChannel(Identifier id, int maxBytes,
            Function<CustomPacketPayload.Type<Payload>, StreamCodec<FriendlyByteBuf, Payload>> codecFactory) {
        // 两种线格式共用同一个限长校验和通道类型，编解码按线格式由调用方给出。
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Protobuf 通道限长必须为正数：" + id);
        }
        this.type = new CustomPacketPayload.Type<>(id);
        this.codec = codecFactory.apply(type);
        this.maxBytes = maxBytes;
    }

    /**
     * 建立不带长度前缀的通道：载荷就是包里剩余的全部字节。读入不按限长断开连接，收包方先用
     * {@link #accepts(Payload)} 检查，空包与超长包直接丢弃。
     */
    public static ProtobufChannel unframed(Identifier id, int maxBytes) {
        // 线格式与只写原始字节的旧通道一致；包长上限仍由原版自定义载荷包约束。
        return new ProtobufChannel(id, maxBytes, created -> StreamCodec.of(
                (buffer, payload) -> buffer.writeBytes(payload.bytes()),
                buffer -> {
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    return new Payload(created, bytes);
                }));
    }

    public CustomPacketPayload.Type<Payload> type() {
        // 收包回调和 canSend 都按这个类型查找通道。
        return type;
    }

    public Payload payload(byte[] bytes) {
        // 每次发送新建载荷，字节数组不跨接收者复用。
        return new Payload(type, bytes);
    }

    public boolean accepts(Payload payload) {
        // 空载荷不是任何一条完整消息；超过限长的不交给解码器。
        return payload.bytes().length > 0 && payload.bytes().length <= maxBytes;
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
