package dev.kizuna.meridian.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Protocol;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 有界 Protobuf 读写；格式校验与身体业务约束分层执行。 */
public final class ProtoWire {
    /** 契约明确允许省略的单值字段：缺省时跳过，出现时照常校验。其余单值消息与 optional 字段必填。 */
    private static final Set<String> OMITTABLE_FIELDS = Set.of(
            "kizuna.meridian.v1.CommandReceipt.time_advance",
            "kizuna.meridian.v1.CommandReceipt.organ_recipe",
            "kizuna.meridian.v1.CommandReceipt.organ_intake",
            "kizuna.meridian.v1.CommandReceipt.action_definition",
            "kizuna.meridian.v1.CommandReceipt.opening",
            "kizuna.meridian.v1.CommandReceipt.qi_capture",
            "kizuna.meridian.v1.CommandReceipt.health",
            "kizuna.meridian.v1.HealthResult.damage_resolution",
            "kizuna.meridian.v1.HealthResult.revival",
            "kizuna.meridian.v1.DamageResolution.spillover",
            "kizuna.meridian.v1.BodyCommand.damage_attachment",
            "kizuna.meridian.v1.DamageBody.hit",
            "kizuna.meridian.v1.DamageBody.death_protection",
            "kizuna.meridian.v1.DamageBody.organ_share",
            "kizuna.meridian.v1.HealthHostResponse.health",
            "kizuna.meridian.v1.DamageIntegrationPlan.death_protection",
            "kizuna.meridian.v1.BodySnapshot.opening_task",
            "kizuna.meridian.v1.OrganDefinition.endurance",
            "kizuna.meridian.v1.OrganDefinition.intake",
            "kizuna.meridian.v1.TissueDefinition.training",
            "kizuna.meridian.v1.Connection.passive_qi_flow",
            "kizuna.meridian.v1.BreathingProfile.body_action",
            "kizuna.meridian.v1.OpeningPolicyDecision.next_check_delay_millis",
            "kizuna.meridian.v1.OrganState.process",
            "kizuna.meridian.v1.QiTransit.circuit",
            "kizuna.meridian.v1.BodyActivity.realm",
            "kizuna.meridian.v1.BodyActivity.damage",
            "kizuna.meridian.v1.BodyActivity.released_qi",
            "kizuna.meridian.v1.BodyActivity.ingested_qi",
            "kizuna.meridian.v1.CircuitChange.replacement",
            "kizuna.meridian.v1.BreathControl.aim_z",
            "kizuna.meridian.v1.DebugQi.amount",
            "kizuna.meridian.v1.CapabilityModifier.tempo_peak",
            "kizuna.meridian.v1.CapabilityModifier.intake_peak",
            "kizuna.meridian.v1.CapabilityModifier.throughput_peak");
    /** 全身 fill 明确使用空目标；其他调试模式仍由命令专用校验要求指定经脉。 */
    private static final String WHOLE_BODY_DEBUG_TARGET = "kizuna.meridian.v1.DebugQi.meridian_id";
    /** 开脉策略的拒绝原因可以是零值，表示没有拒绝。 */
    private static final String ZERO_ENUM_ALLOWED = "kizuna.meridian.v1.OpeningPolicyDecision.rejection";
    /** Any 的内部 schema 归声明策略校验；外层仍受整包字节数和字段数量限制。 */
    private static final String ANY_VALUE = "google.protobuf.Any.value";
    /** 只有能力曲线振幅允许负数；其他资源和剂量继续要求非负。 */
    private static final String SIGNED_CURVE_VALUE = "kizuna.meridian.v1.CurvePoint.value";
    /** 每种消息类型的规则只从描述符推导一次；描述符在进程内唯一且不可变，可跨线程共享。 */
    private static final Map<Descriptors.Descriptor, MessageRules> RULES = new ConcurrentHashMap<>();
    /** 每个线程记住最近几条校验通过的消息实例，见 {@link #validate(Message)}。 */
    private static final int REMEMBERED_MESSAGES = 8;
    private static final ThreadLocal<RecentlyValid> RECENTLY_VALID = ThreadLocal.withInitial(RecentlyValid::new);

    private ProtoWire() {
        // 协议操作不保存会话或权威状态，工具类无需实例化。
    }

    public static <T extends Message> T read(byte[] bytes, Parser<T> parser) {
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

    public static byte[] write(Message message) {
        // 发送方也遵守接收方限制，避免本地能构造却无法交换的数据。
        validate(message);
        return message.toByteArray();
    }

    public static void validate(Message message) {
        // 生成类型不保证业务必填字段；在进入数据映射前检查存在性及资源上限。
        require(message != null, Protocol.NULL_MESSAGE);
        // 生成消息不可变，同一实例的校验结果不会变。编码前、解码后和 toMessage→fromMessage 这类内部往返
        // 会把刚校验过的同一实例再交上来，此时跳过；只记通过的实例，失败的每次都重新校验并报错。
        RecentlyValid recent = RECENTLY_VALID.get();
        if (recent.contains(message)) {
            return;
        }
        require(message.getSerializedSize() <= MeridianProtocol.MAX_PAYLOAD_BYTES, Protocol.OVERSIZED_MESSAGE);
        validateMessage(message, 0, new int[] {MeridianProtocol.MAX_FIELD_VALUES});
        recent.add(message);
    }

    private static void validateMessage(Message message, int depth, int[] remaining) {
        // 当前精确版本拒绝未知字段，防止映射为核心值时静默丢失尚不理解的身体数据。
        require(depth <= MeridianProtocol.MAX_RECURSION_DEPTH, Protocol.NESTING_TOO_DEEP);
        require(message.getUnknownFields().isEmpty(), Protocol.UNKNOWN_FIELDS);
        MessageRules rules = RULES.computeIfAbsent(message.getDescriptorForType(), MessageRules::of);
        for (int index = 0; index < rules.realOneofs().size(); index++) {
            require(message.hasOneof(rules.realOneofs().get(index)), rules.missingChoices().get(index));
        }

        for (FieldRule rule : rules.fields()) {
            var field = rule.field();
            if (rule.wholeBodyWhenEmpty() && ((String) message.getField(field)).isEmpty()) {
                continue;
            }
            // oneof 的其他分支必须跳过；optional 标量产生的 synthetic oneof 仍须检查。
            if (rule.inRealOneof() && !message.hasField(field)) {
                continue;
            }
            if (rule.omittable() && !message.hasField(field)) {
                // 仅跳过契约明确允许省略的字段；吐纳深度由执行方补为入口所在平面。
                continue;
            }
            if (rule.repeated()) {
                List<?> values = (List<?>) message.getField(field);
                require(values.size() <= MeridianProtocol.MAX_LIST_ENTRIES, rule.listTooLong());
                for (Object value : values) {
                    validateValue(rule, value, depth, remaining);
                }
            } else {
                // 除前面明确列出的条件消息外，单值消息与 optional 字段必填，0/false 须显式编码。
                require(!rule.presenceRequired() || message.hasField(field), rule.missing());
                validateValue(rule, message.getField(field), depth, remaining);
            }
        }
    }

    private static void validateValue(FieldRule rule, Object value, int depth, int[] remaining) {
        // 单表上限之外再限制全消息的字段值总数，防止大量小列表绕过规模限制。
        require(--remaining[0] >= 0, Protocol.TOO_MANY_FIELD_VALUES);
        switch (rule.type()) {
            case MESSAGE -> validateMessage((Message) value, depth + 1, remaining);
            case STRING -> {
                String text = (String) value;
                // Java 字符串可能包含孤立代理项，拒绝编码时被替换而悄悄改变身份。
                int utf8Bytes = utf8Length(text);
                require(utf8Bytes >= 0, rule.invalidValue());
                require(!text.isBlank() && utf8Bytes <= MeridianProtocol.MAX_TEXT_BYTES, rule.invalidText());
            }
            case ENUM -> {
                var enumeration = (Descriptors.EnumValueDescriptor) value;
                require(enumeration.getIndex() >= 0 && (enumeration.getNumber() != 0 || rule.zeroEnumAllowed()),
                        rule.invalidValue());
            }
            case BYTE_STRING -> require(rule.bytesAllowed(), rule.invalidValue());
            case DOUBLE -> {
                double number = (double) value;
                require(Double.isFinite(number) && number >= rule.minimum() && number <= rule.maximum(),
                        rule.invalidValue());
            }
            case INT -> require((int) value >= 0, rule.invalidValue());
            case LONG -> require((long) value >= 0, rule.invalidValue());
            case BOOLEAN -> {
                // 布尔值的 false 是有效状态，存在性已在上一层检查。
            }
            default -> throw MeridianErrors.invalid(rule.invalidValue());
        }
    }

    private static int utf8Length(String text) {
        // 一遍扫描同时得到 UTF-8 字节数并找出孤立代理项（UTF-8 唯一无法编码的情形）；有孤立代理项返回 -1。
        int bytes = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current < 0x80) {
                bytes += 1;
            } else if (current < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return -1;
                }
                bytes += 4;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return -1;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    static void require(boolean condition, String message) {
        // 协议错误使用一致异常类型，宿主可转换为公开拒绝类别。
        if (!condition) {
            throw MeridianErrors.invalid(message);
        }
    }

    /** 一种消息类型的校验规则：真正 oneof 的必选项与逐字段规则，顺序与描述符一致。 */
    private record MessageRules(List<Descriptors.OneofDescriptor> realOneofs, List<String> missingChoices,
                                List<FieldRule> fields) {
        static MessageRules of(Descriptors.Descriptor type) {
            // 只读本类型自己的字段，不递归推导子消息，避免在 computeIfAbsent 里嵌套写同一张表。
            List<Descriptors.OneofDescriptor> oneofs = List.copyOf(type.getRealOneofs());
            List<String> missingChoices = oneofs.stream().map(oneof -> Protocol.missingChoice(oneof.getFullName())).toList();
            List<FieldRule> fields = type.getFields().stream().map(FieldRule::of).toList();
            return new MessageRules(oneofs, missingChoices, fields);
        }
    }

    /** 一个字段的校验规则与错误文案，原先每个值都要现算的名字比较和字符串拼接都在这里一次算好。 */
    private record FieldRule(Descriptors.FieldDescriptor field, Descriptors.FieldDescriptor.JavaType type,
                             boolean repeated, boolean inRealOneof, boolean omittable, boolean wholeBodyWhenEmpty,
                             boolean presenceRequired, boolean zeroEnumAllowed, boolean bytesAllowed,
                             double minimum, double maximum,
                             String listTooLong, String missing, String invalidValue, String invalidText) {
        static FieldRule of(Descriptors.FieldDescriptor field) {
            // 名字只在这里比较一次；数值范围默认非负有限，能力曲线振幅放宽到 [-1, 1]。
            String name = field.getFullName();
            double minimum = 0;
            double maximum = Double.MAX_VALUE;
            if (name.equals(SIGNED_CURVE_VALUE)) {
                minimum = -1;
                maximum = 1;
            }
            String invalidValue = switch (field.getJavaType()) {
                case STRING -> Protocol.invalidUnicode(name);
                case ENUM -> Protocol.invalidEnum(name);
                case DOUBLE -> Protocol.invalidNumber(name);
                case INT -> Protocol.negativeInteger(name);
                case LONG -> Protocol.negativeLong(name);
                default -> Protocol.unsupportedFieldType(name);
            };
            return new FieldRule(field, field.getJavaType(), field.isRepeated(),
                    field.getRealContainingOneof() != null, OMITTABLE_FIELDS.contains(name),
                    name.equals(WHOLE_BODY_DEBUG_TARGET), field.hasPresence(), name.equals(ZERO_ENUM_ALLOWED),
                    name.equals(ANY_VALUE), minimum, maximum,
                    Protocol.listTooLong(name), Protocol.missingField(name), invalidValue, Protocol.invalidText(name));
        }
    }

    /** 一个线程最近校验通过的消息实例，按实例身份比较；只为跳过同一实例的重复校验。 */
    private static final class RecentlyValid {
        private final Message[] messages = new Message[REMEMBERED_MESSAGES];
        private int next;

        boolean contains(Message message) {
            // 只比实例身份：内容相同的另一个实例照样完整校验。
            for (Message remembered : messages) {
                if (remembered == message) {
                    return true;
                }
            }
            return false;
        }

        void add(Message message) {
            // 环形覆盖最旧的一条，最多持有几条消息，不随调用次数增长。
            messages[next] = message;
            next = (next + 1) % messages.length;
        }
    }
}
