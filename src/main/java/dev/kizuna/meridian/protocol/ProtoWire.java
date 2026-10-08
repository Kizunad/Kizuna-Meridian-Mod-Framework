package dev.kizuna.meridian.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 有界 Protobuf 读写；格式校验与身体业务约束分层执行。 */
final class ProtoWire {
    private ProtoWire() {
        // 协议操作不保存会话或权威状态，工具类无需实例化。
    }

    static <T extends Message> T read(byte[] bytes, Parser<T> parser) {
        // 分配解析对象前限制整个载荷，避免把不受限的网络输入交给生成解析器。
        require(bytes != null && bytes.length > 0 && bytes.length <= MeridianProtocol.MAX_PAYLOAD_BYTES,
                "载荷必须为 1 到 " + MeridianProtocol.MAX_PAYLOAD_BYTES + " 字节");
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        input.setSizeLimit(MeridianProtocol.MAX_PAYLOAD_BYTES);
        input.setRecursionLimit(MeridianProtocol.MAX_RECURSION_DEPTH);
        try {
            T message = parser.parseFrom(input);
            require(input.isAtEnd(), "载荷存在未消费数据");
            validate(message);
            return message;
        } catch (IOException exception) {
            // 对外统一为输入错误，不把截断消息当成可用的部分状态。
            throw new IllegalArgumentException("无效的 Protobuf 载荷", exception);
        }
    }

    static byte[] write(Message message) {
        // 发送方也遵守接收方限制，避免本地能构造却无法交换的数据。
        validate(message);
        return message.toByteArray();
    }

    static void validate(Message message) {
        // 生成类型不保证业务必填字段；在进入数据映射前检查存在性及资源上限。
        require(message != null, "消息不能为空");
        require(message.getSerializedSize() <= MeridianProtocol.MAX_PAYLOAD_BYTES, "消息超过字节上限");
        validateMessage(message, 0, new int[] {MeridianProtocol.MAX_FIELD_VALUES});
    }

    private static void validateMessage(Message message, int depth, int[] remaining) {
        // 当前精确版本拒绝未知字段，防止映射为核心值时静默丢失尚不理解的身体数据。
        require(depth <= MeridianProtocol.MAX_RECURSION_DEPTH, "消息嵌套过深");
        require(message.getUnknownFields().asMap().isEmpty(), "消息包含当前版本不支持的字段");
        for (var oneof : message.getDescriptorForType().getRealOneofs()) {
            require(message.hasOneof(oneof), "消息未选择操作或结果：" + oneof.getFullName());
        }

        for (var field : message.getDescriptorForType().getFields()) {
            // oneof 的其他分支必须跳过；optional 标量产生的 synthetic oneof 仍须检查。
            if (field.getRealContainingOneof() != null && !message.hasField(field)) {
                continue;
            }
            if (field.isRepeated()) {
                List<?> values = (List<?>) message.getField(field);
                require(values.size() <= MeridianProtocol.MAX_LIST_ENTRIES, "列表过长：" + field.getFullName());
                for (Object value : values) {
                    validateValue(field, value, depth, remaining);
                }
            } else {
                // v1 中所有单值消息及 optional 字段都必填，0/false 必须显式编码。
                require(!field.hasPresence() || message.hasField(field), "缺少字段：" + field.getFullName());
                validateValue(field, message.getField(field), depth, remaining);
            }
        }
    }

    private static void validateValue(Descriptors.FieldDescriptor field, Object value, int depth, int[] remaining) {
        // 单表上限之外再限制全消息的字段值总数，防止大量小列表绕过规模限制。
        require(--remaining[0] >= 0, "消息字段值总数过多");
        switch (field.getJavaType()) {
            case MESSAGE -> validateMessage((Message) value, depth + 1, remaining);
            case STRING -> {
                String text = (String) value;
                // Java 字符串可能包含孤立代理项，拒绝编码时被替换而悄悄改变身份。
                require(StandardCharsets.UTF_8.newEncoder().canEncode(text), "文本不是有效 Unicode：" + field.getFullName());
                require(!text.isBlank() && text.getBytes(StandardCharsets.UTF_8).length <= MeridianProtocol.MAX_TEXT_BYTES,
                        "文本为空或过长：" + field.getFullName());
            }
            case ENUM -> {
                var enumeration = (Descriptors.EnumValueDescriptor) value;
                require(enumeration.getIndex() >= 0 && enumeration.getNumber() != 0,
                        "枚举缺失或不受支持：" + field.getFullName());
            }
            case DOUBLE -> require(Double.isFinite((double) value) && (double) value >= 0,
                    "数值必须非负且有限：" + field.getFullName());
            case INT -> require((int) value >= 0, "整数不能为负：" + field.getFullName());
            case LONG -> require((long) value >= 0, "长整数不能为负：" + field.getFullName());
            case BOOLEAN -> {
                // 布尔值的 false 是有效状态，存在性已在上一层检查。
            }
            default -> throw new IllegalArgumentException("当前协议未支持字段类型：" + field.getFullName());
        }
    }

    static void require(boolean condition, String message) {
        // 协议错误使用一致异常类型，宿主可转换为公开拒绝类别。
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
