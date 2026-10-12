package dev.kizuna.meridian.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Protocol;

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
                Protocol.payloadSize(MeridianProtocol.MAX_PAYLOAD_BYTES));
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        input.setSizeLimit(MeridianProtocol.MAX_PAYLOAD_BYTES);
        input.setRecursionLimit(MeridianProtocol.MAX_RECURSION_DEPTH);
        try {
            T message = parser.parseFrom(input);
            require(input.isAtEnd(), Protocol.UNCONSUMED_PAYLOAD);
            validate(message);
            return message;
        } catch (IOException exception) {
            // 对外统一为输入错误，不把截断消息当成可用的部分状态。
            throw MeridianErrors.invalid(Protocol.INVALID_PAYLOAD, exception);
        }
    }

    static byte[] write(Message message) {
        // 发送方也遵守接收方限制，避免本地能构造却无法交换的数据。
        validate(message);
        return message.toByteArray();
    }

    static void validate(Message message) {
        // 生成类型不保证业务必填字段；在进入数据映射前检查存在性及资源上限。
        require(message != null, Protocol.NULL_MESSAGE);
        require(message.getSerializedSize() <= MeridianProtocol.MAX_PAYLOAD_BYTES, Protocol.OVERSIZED_MESSAGE);
        validateMessage(message, 0, new int[] {MeridianProtocol.MAX_FIELD_VALUES});
    }

    private static void validateMessage(Message message, int depth, int[] remaining) {
        // 当前精确版本拒绝未知字段，防止映射为核心值时静默丢失尚不理解的身体数据。
        require(depth <= MeridianProtocol.MAX_RECURSION_DEPTH, Protocol.NESTING_TOO_DEEP);
        require(message.getUnknownFields().asMap().isEmpty(), Protocol.UNKNOWN_FIELDS);
        for (var oneof : message.getDescriptorForType().getRealOneofs()) {
            require(message.hasOneof(oneof), Protocol.missingChoice(oneof.getFullName()));
        }

        for (var field : message.getDescriptorForType().getFields()) {
            // oneof 的其他分支必须跳过；optional 标量产生的 synthetic oneof 仍须检查。
            if (field.getRealContainingOneof() != null && !message.hasField(field)) {
                continue;
            }
            if ((field.getFullName().equals("kizuna.meridian.v1.CommandReceipt.time_advance")
                    || field.getFullName().equals("kizuna.meridian.v1.CommandReceipt.organ_recipe")
                    || field.getFullName().equals("kizuna.meridian.v1.OrganState.process")) && !message.hasField(field)) {
                // 回执事实随命令类型出现；空闲器官没有加工记录，其他单值消息仍必须存在。
                continue;
            }
            if (field.isRepeated()) {
                List<?> values = (List<?>) message.getField(field);
                require(values.size() <= MeridianProtocol.MAX_LIST_ENTRIES, Protocol.listTooLong(field.getFullName()));
                for (Object value : values) {
                    validateValue(field, value, depth, remaining);
                }
            } else {
                // 除前面明确列出的条件消息外，单值消息与 optional 字段必填，0/false 须显式编码。
                require(!field.hasPresence() || message.hasField(field), Protocol.missingField(field.getFullName()));
                validateValue(field, message.getField(field), depth, remaining);
            }
        }
    }

    private static void validateValue(Descriptors.FieldDescriptor field, Object value, int depth, int[] remaining) {
        // 单表上限之外再限制全消息的字段值总数，防止大量小列表绕过规模限制。
        require(--remaining[0] >= 0, Protocol.TOO_MANY_FIELD_VALUES);
        switch (field.getJavaType()) {
            case MESSAGE -> validateMessage((Message) value, depth + 1, remaining);
            case STRING -> {
                String text = (String) value;
                // Java 字符串可能包含孤立代理项，拒绝编码时被替换而悄悄改变身份。
                require(StandardCharsets.UTF_8.newEncoder().canEncode(text), Protocol.invalidUnicode(field.getFullName()));
                require(!text.isBlank() && text.getBytes(StandardCharsets.UTF_8).length <= MeridianProtocol.MAX_TEXT_BYTES,
                        Protocol.invalidText(field.getFullName()));
            }
            case ENUM -> {
                var enumeration = (Descriptors.EnumValueDescriptor) value;
                require(enumeration.getIndex() >= 0 && enumeration.getNumber() != 0,
                        Protocol.invalidEnum(field.getFullName()));
            }
            case DOUBLE -> require(Double.isFinite((double) value) && (double) value >= 0,
                    Protocol.invalidNumber(field.getFullName()));
            case INT -> require((int) value >= 0, Protocol.negativeInteger(field.getFullName()));
            case LONG -> require((long) value >= 0, Protocol.negativeLong(field.getFullName()));
            case BOOLEAN -> {
                // 布尔值的 false 是有效状态，存在性已在上一层检查。
            }
            default -> throw MeridianErrors.invalid(Protocol.unsupportedFieldType(field.getFullName()));
        }
    }

    static void require(boolean condition, String message) {
        // 协议错误使用一致异常类型，宿主可转换为公开拒绝类别。
        if (!condition) {
            throw MeridianErrors.invalid(message);
        }
    }
}
