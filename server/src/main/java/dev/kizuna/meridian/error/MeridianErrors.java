package dev.kizuna.meridian.error;

import java.util.Objects;

/** Java 参考实现的异常类型与文案；公开线协议失败码仍由 Protobuf 定义。 */
public final class MeridianErrors {
    private MeridianErrors() {
        // 错误定义不持有身体或宿主状态，只供各层在原有校验位置使用。
    }

    public static IllegalArgumentException invalid(String message) {
        // 输入不合法时保留原有异常类型，便于宿主识别无效请求。
        return new IllegalArgumentException(message);
    }

    public static IllegalArgumentException invalid(String message, Throwable cause) {
        // 解码故障保留原因链供服务端诊断，对外响应仍不携带异常详情。
        return new IllegalArgumentException(message, cause);
    }

    public static IllegalStateException inconsistent(String message) {
        // 宿主或事务违反既有约束时使用状态异常，与无效输入分开。
        return new IllegalStateException(message);
    }

    public static <T> T required(T value, String message) {
        // 显式必填引用统一保留 NullPointerException 语义及原有文案。
        return Objects.requireNonNull(value, message);
    }

    public static LifespanRejected rejectLifespan(LifespanRejectionReason reason) {
        // 寿元业务拒绝由同一文件构造，算子只负责判断何时拒绝。
        return new LifespanRejected(reason);
    }

    public static TimeRejected rejectTime(TimeRejectionReason reason) {
        // 时间业务拒绝与格式错误分开，处理器按枚举映射公开失败码。
        return new TimeRejected(reason);
    }

    public static TransportRejected rejectTransport(TransportRejectionReason reason) {
        // 运输业务拒绝保持结构化原因，不由调用方解析文本来判断重试方式。
        return new TransportRejected(reason);
    }

    public static CapabilityRejected rejectCapability(CapabilityRejectionReason reason) {
        // 组织与器官业务拒绝统一定义；实际校验仍在对应算子。
        return new CapabilityRejected(reason);
    }

    /** 环境配置与派生速率的诊断；配置读取和计算仍在各自入口执行。 */
    public static final class Environment {
        public static final String CONFIG_VERSION = "不支持的环境配置版本";
        public static final String REGION_SIZE = "区域边长必须为正 int32 整数，单位 chunk";
        public static final String DEFAULT_TRANSMISSION = "默认方块传播系数必须在 [0, 1) 范围内";
        public static final String DUPLICATE_BLOCK = "方块传播配置存在重复 ID";
        public static final String AIR_TRANSMISSION = "air、cave_air、void_air 必须显式配置且传播系数为 1";
        public static final String DEFAULT_RESOURCE = "缺少内置环境 TOML 配置资源";
        public static final String CONFIG_TOO_LARGE = "环境 TOML 文件超过 256 KiB 上限";
        public static final String CONFIG_TEXT_REQUIRED = "环境 TOML 文本不能为空引用";
        public static final String CONFIG_PATH_REQUIRED = "环境配置路径不能为空";
        public static final String INVENTORY_REQUIRED = "环境库存不能为空";

        private Environment() {
            // 集中诊断信息，避免配置层与运行层各自定义异常类型。
        }

        public static String readFailed(Object path) {
            // 路径帮助宿主定位配置，原始 I/O 原因另存于异常链。
            return "无法读取环境配置：" + path;
        }

        public static String parseFailed(Object detail) {
            // TOML 解析器提供行列位置，保留它以便用户直接修正文件。
            return "环境 TOML 格式错误：" + detail;
        }

        public static String unknownKey(String path) {
            // 拒绝拼写错误，防止配置看似生效却实际沿用默认值。
            return "未知环境配置项：" + path;
        }

        public static String wrongType(String path, String expected) {
            // 字段类型与业务范围分开报告，避免把字符串误转为数字。
            return "环境配置项 " + path + " 必须为" + expected;
        }
    }

    /** 核心数据与构型校验文案。 */
    public static final class Data {
        public static final String LOCAL_ID_FORMAT = "局部标识必须使用非空小写字母、数字、点、横线或下划线";
        public static final String CONTENT_ID_FORMAT = "内容标识必须采用 namespace:path 格式";
        public static final String NEGATIVE_REVISION = "版本号不能为负数";
        public static final String NO_PARTS = "身体必须包含至少一个部位";
        public static final String MERIDIAN_WITHOUT_PART = "经脉必须映射至少一个实际部位";
        public static final String WALL_WITHOUT_REFERENCE = "外壁储备空间必须有液态参照";
        public static final String SELF_CONNECTION = "连接不能指向自身";
        public static final String QI_KIND_MISMATCH = "只有相同相态和属性的真元才能直接合并";
        public static final String DUPLICATE_QI_KIND = "同相态同属性的真元必须合并为一条明细";
        public static final String NEGATIVE_ARRIVAL = "到达时间不能为负数";
        public static final String NEGATIVE_TIME = "模拟时间不能为负数";
        public static final String INVALID_TEMPLATE_VERSION = "模板版本必须为正整数";

        public static final String PART_ID_REQUIRED = "部位标识不能为空";
        public static final String TISSUE_ID_REQUIRED = "组织标识不能为空";
        public static final String ORGAN_ID_REQUIRED = "器官标识不能为空";
        public static final String MERIDIAN_ID_REQUIRED = "经脉标识不能为空";
        public static final String PORT_ID_REQUIRED = "端口标识不能为空";
        public static final String PORT_DIRECTION_REQUIRED = "端口方向不能为空";
        public static final String CONNECTION_ID_REQUIRED = "连接标识不能为空";
        public static final String CONNECTION_FROM_REQUIRED = "连接起点不能为空";
        public static final String CONNECTION_TO_REQUIRED = "连接终点不能为空";
        public static final String TISSUE_KIND_REQUIRED = "组织类型不能为空";
        public static final String TISSUE_PART_REQUIRED = "组织必须归属部位";
        public static final String ORGAN_KIND_REQUIRED = "器官类型不能为空";
        public static final String ORGAN_PART_REQUIRED = "器官必须归属部位";
        public static final String BODY_ID_REQUIRED = "身体标识不能为空";
        public static final String BODY_TEMPLATE_REQUIRED = "身体模板引用不能为空";
        public static final String BODY_STRUCTURE_REQUIRED = "身体实际构型不能为空";
        public static final String REALM_REQUIRED = "境界不能为空";
        public static final String VITALITY_REQUIRED = "生命状态不能为空";
        public static final String SETTLED_AT_REQUIRED = "结算时间游标不能为空";
        public static final String TEMPLATE_REFERENCE_REQUIRED = "模板引用不能为空";
        public static final String TEMPLATE_STRUCTURE_REQUIRED = "模板构型不能为空";
        public static final String TEMPLATE_ID_REQUIRED = "模板标识不能为空";
        public static final String TIME_DOMAIN_REQUIRED = "时钟域不能为空";
        public static final String QI_PHASE_REQUIRED = "真元相态不能为空";
        public static final String QI_ATTRIBUTE_REQUIRED = "真元属性不能为空";
        public static final String QI_REQUIRED = "真元不能为空";
        public static final String QI_ATTRIBUTE_ID_REQUIRED = "真元属性标识不能为空";
        public static final String LUMEN_QI_REQUIRED = "内腔真元不能为空";
        public static final String WALL_QI_REQUIRED = "外壁真元不能为空";
        public static final String MERIDIAN_CONDITION_REQUIRED = "经脉损伤状态不能为空";
        public static final String MERIDIAN_OPENING_REQUIRED = "经脉开拓数据不能为空";
        public static final String OPENING_STAGE_REQUIRED = "经脉开拓阶段不能为空";
        public static final String OPENING_PROGRESS_OUT_OF_RANGE = "经脉开拓进度必须是 0 到 1 之间的有限数";
        public static final String OPENING_STAGE_PROGRESS_MISMATCH = "经脉开拓进度与阶段不符：未开拓为 0，开拓未完成小于 1，已开拓为 1";
        public static final String TRANSIT_CONNECTION_REQUIRED = "在途连接不能为空";
        public static final String TRANSIT_QI_REQUIRED = "在途真元不能为空";
        public static final String BODY_REQUIRED = "身体不能为空";
        public static final String TIME_PLAN_REQUIRED = "时间推进计划不能为空";
        public static final String TIME_MODE_REQUIRED = "时间推进模式不能为空";
        public static final String TIME_RESULT_REQUIRED = "时间推进结果不能为空";
        public static final String TRANSPORT_REQUEST_REQUIRED = "运输请求不能为空";
        public static final String TRANSIT_STATUS_REQUIRED = "在途状态不能为空";
        public static final String TRANSIT_BLOCKED_BEFORE_ARRIVAL = "未到达的货物不能标为到达阻塞";
        public static final String CAPACITY_REQUIRED = "必须提供组织基础能力";
        public static final String TISSUE_CONTEXT_INCOMPLETE = "组织能力计算缺少组织状态或所属部位数据";
        public static final String RECIPE_REQUIRED = "必须提供器官配方";
        public static final String INVALID_RECIPE = "配方必须有正数版本、时长及输入，输出不能超过输入";
        public static final String INVALID_PROCESS_TIME = "加工运行或阻塞状态必须与结算时间一致";
        public static final String PROCESS_KIND_MISMATCH = "加工配方与器官类型不匹配";
        public static final String DUPLICATE_RECIPE = "配方 ID 与版本重复";

        private Data() {
            // 该分组只存放数据校验文案，不承担校验逻辑。
        }

        public static String empty(String label) {
            // 维持字段名在前的原有格式，便于定位哪一个数据值缺失。
            return label + "不能为空";
        }

        public static String nonNegativeFinite(String label) {
            // 统一非负有限数的描述，避免各模型给出不一致的规则。
            return label + "必须为非负有限数";
        }

        public static String positiveFinite(String label) {
            // 严格正数用于容量、参照及实际支出等不能为零的值。
            return label + "必须为正有限数";
        }

        public static String overLimit(String label) {
            // 上限名称由校验现场提供，文案本身在这里统一。
            return label + "超过上限";
        }

        public static String missingIdentifier(String label) {
            // 索引构建与重复检查共享模块名，不复制具体异常文案。
            return label + "标识不能为空";
        }

        public static String duplicateIdentifier(String label, Object id) {
            // 重复标识要保留冲突值，方便内容作者修复构型。
            return label + "标识重复：" + id;
        }

        public static String missingConnectionPort(Object id) {
            // 连接身份只用于定位非法引用，不在这里重新解释拓扑规则。
            return "连接引用不存在的端口：" + id;
        }

        public static String wrongConnectionDirection(Object id) {
            // 方向规则留在构型校验处，这里只格式化诊断信息。
            return "连接方向与端口不符：" + id;
        }

        public static String duplicateConnection(Object id) {
            // 重复边保留原有连接 ID 诊断。
            return "连接重复：" + id;
        }

        public static String missingPart(Object id) {
            // 模块引用了缺失部位时直接指出目标身份。
            return "引用不存在的部位：" + id;
        }

        public static String duplicatePort(Object id) {
            // 跨模块端口重名会造成连接歧义，报告具体端口。
            return "端口在身体内必须唯一：" + id;
        }

        public static String unsupportedBodyVersion(int version) {
            // 加载失败明确指出实际 schema，不能静默按当前版本解释。
            return "不支持的身体数据版本：" + version;
        }

        public static String transitConnectionMissing(Object id) {
            // 在途真元仍属于身体总账，不能保留悬空线路。
            return "在途记录引用不存在的连接：" + id;
        }

        public static String transitAlreadyArrived(Object id) {
            // 已到达货物须先结算再保存，保留 ID 便于诊断。
            return "已到达的运输必须先结算再形成快照：" + id;
        }

        public static String stateMismatch(String label) {
            // 定义和状态必须通过身份一一对应，数量相同也不能代替。
            return label + "定义与状态必须按标识一一对应";
        }
    }

    /** Protobuf 边界校验文案；线协议错误枚举仍使用 protoc 生成的类型。 */
    public static final class Protocol {
        public static final String INVALID_PAYLOAD = "无效的 Protobuf 载荷";
        public static final String UNCONSUMED_PAYLOAD = "载荷存在未消费数据";
        public static final String NULL_MESSAGE = "消息不能为空";
        public static final String OVERSIZED_MESSAGE = "消息超过字节上限";
        public static final String NESTING_TOO_DEEP = "消息嵌套过深";
        public static final String UNKNOWN_FIELDS = "消息包含当前版本不支持的字段";
        public static final String TOO_MANY_FIELD_VALUES = "消息字段值总数过多";
        public static final String MISSING_REQUEST = "缺少请求意图";
        public static final String MISSING_RESULT = "缺少请求结果";
        public static final String MISSING_COMMAND = "缺少身体变更意图";
        public static final String REQUEST_VERSION = "不支持的请求协议版本";
        public static final String RESPONSE_VERSION = "不支持的响应协议版本";
        public static final String RECEIPT_MISMATCH = "回执命令与提交结果不匹配";
        public static final String LIFESPAN_POSITIVE = "寿元支出必须为正数";
        public static final String COMMIT_REVISION = "提交版本必须恰好递增一次";
        public static final String TIME_RECEIPT_MISMATCH = "时间结算记录必须与推进命令对应";
        public static final String INVALID_TIME_RESULT = "时间结算位置或死亡释放不符合计划";
        public static final String RECIPE_RECEIPT_MISMATCH = "配方事实必须与加工命令的 ID 和版本一致";
        public static final String ENVIRONMENT_VERSION = "不支持的环境交换协议版本";
        public static final String ENVIRONMENT_CLOCK = "环境供给与交换必须使用同一时钟域和世代";
        public static final String ENVIRONMENT_INTERVAL = "环境供给或交换时间区间无效";
        public static final String ENVIRONMENT_EMPTY_EXCHANGE = "环境交换至少需要吸收或释放一项真元";
        public static final String ENVIRONMENT_ABSORPTION = "吸收明细超过环境供给额度或无法精确扣除";
        public static final String ENVIRONMENT_RATE_LIMIT = "吸收总量超过该时间区间的供给速率上限";
        public static final String ENVIRONMENT_RATE_OVERFLOW = "环境供给速率与时长的乘积超出数值范围";

        private Protocol() {
            // 协议文案集中存放，字段与请求校验仍留在调用现场。
        }

        public static String payloadSize(int maximum) {
            // 最大尺寸来自协议配置，拒绝文本须与实际限制一致。
            return "载荷必须为 1 到 " + maximum + " 字节";
        }

        public static String missingChoice(String name) {
            // oneof 必须显式选中分支，保留字段全名定位来源。
            return "消息未选择操作或结果：" + name;
        }

        public static String listTooLong(String name) {
            // 列表上限由校验器执行，文案指出对应字段。
            return "列表过长：" + name;
        }

        public static String missingField(String name) {
            // optional 字段缺失与显式零值必须区分。
            return "缺少字段：" + name;
        }

        public static String invalidUnicode(String name) {
            // Java 孤立代理项会被替换；明确指出不能稳定编码的字段。
            return "文本不是有效 Unicode：" + name;
        }

        public static String invalidText(String name) {
            // 长度和空白共用字段级错误，保持原有诊断格式。
            return "文本为空或过长：" + name;
        }

        public static String invalidEnum(String name) {
            // 未知和默认枚举不能被映射为合法业务值。
            return "枚举缺失或不受支持：" + name;
        }

        public static String invalidNumber(String name) {
            // 数值边界既拒绝负数，也拒绝 NaN 与无穷大。
            return "数值必须非负且有限：" + name;
        }

        public static String negativeInteger(String name) {
            // 线协议中的 int 与 long 保留各自的原有诊断文案。
            return "整数不能为负：" + name;
        }

        public static String negativeLong(String name) {
            // 线协议中的 int 与 long 保留各自的原有诊断文案。
            return "长整数不能为负：" + name;
        }

        public static String unsupportedFieldType(String name) {
            // 新字段类型在边界尚无校验规则时明确失败。
            return "当前协议未支持字段类型：" + name;
        }
    }

    /** 宿主读取、变更与事务参考实现的诊断文案。 */
    public static final class Host {
        public static final String COMMAND_ONLY = "变更入口只接受身体命令";
        public static final String QUERY_ONLY = "查询入口只接受快照查询";
        public static final String COMMAND_TO_QUERY = "变更请求不能交给查询入口";
        public static final String QUERY_MISSING = "缺少查询意图";
        public static final String RECEIPT_MISMATCH = "宿主返回了不匹配的回执";
        public static final String WRONG_BODY = "宿主返回了另一具身体";
        public static final String CALLER_ID_INVALID = "宿主调用者身份不符合协议";
        public static final String SNAPSHOT_ID_MISMATCH = "快照身份与查询目标不一致";
        public static final String DUPLICATE_BODY = "身体身份重复";
        public static final String RECEIPT_WITHOUT_BODY = "回执缺少对应身体或领先于身体版本";
        public static final String DUPLICATE_RECEIPT = "成功回执键重复";
        public static final String NESTED_TRANSACTION = "不允许嵌套身体写事务";
        public static final String INVALID_STAGE = "暂存需要现存身体且只能执行一次";
        public static final String STAGE_MISMATCH = "提交身体、回执或预期版本不一致";
        public static final String RECEIPT_OVERWRITE = "成功回执不能覆盖";
        public static final String INACTIVE_TRANSACTION = "事务已结束或被跨线程使用";
        public static final String ACCESS_REQUIRED = "必须提供身体变更授权策略";
        public static final String SECURITY_REQUIRED = "必须提供身体安全策略";
        public static final String STORE_REQUIRED = "必须提供身体事务存储";
        public static final String AUDIT_REQUIRED = "必须提供提交审计入口";
        public static final String POLICY_REQUIRED = "必须提供身体读取权限策略";
        public static final String SNAPSHOTS_REQUIRED = "必须提供权威快照读取入口";
        public static final String AUTH_RESULT_REQUIRED = "授权不能返回 null";
        public static final String SNAPSHOT_RESULT_REQUIRED = "快照读取入口不能返回 null";
        public static final String BODY_ID_REQUIRED = "身体身份不能为空";
        public static final String TRANSACTION_WORK_REQUIRED = "事务回调不能为空";
        public static final String AUDIT_FAILED = "身体提交审计通知失败";
        public static final String COMMAND_FAILED = "身体变更处理失败";
        public static final String QUERY_FAILED = "身体快照查询处理失败";
        public static final String TIME_SOURCE_REQUIRED = "必须提供宿主可信时间源";
        public static final String TIME_PLAN_REQUIRED = "宿主时间源不能返回 null";
        public static final String TIME_STAGE_MISMATCH = "时间结算回执与实际身体变化不一致";
        public static final String ENVIRONMENT_REQUIRED = "必须提供已保存的环境真元账户";
        public static final String TRANSPORT_STAGE_MISMATCH = "运输命令与源扣除、在途记录不一致";
        public static final String CAPABILITY_STAGE_MISMATCH = "组织或器官命令与候选身体不一致";
        public static final String RECIPES_REQUIRED = "必须提供器官配方目录";

        private Host() {
            // 文案分组不介入宿主授权、事务或日志级别的选择。
        }
    }

    /** 核心明确拒绝寿元支出的原因；不能替代线协议 FailureCode。 */
    public enum LifespanRejectionReason {
        BODY_DEAD,
        INSUFFICIENT_LIFESPAN,
        REVISION_EXHAUSTED,
        NUMERIC_LIMIT
    }

    public enum TimeRejectionReason {
        BODY_DEAD,
        REVISION_EXHAUSTED,
        CLOCK_MISMATCH,
        TIME_NOT_ADVANCED,
        PENDING_TIME_EVENT,
        NUMERIC_LIMIT
    }

    public enum TransportRejectionReason {
        BODY_DEAD,
        REVISION_EXHAUSTED,
        NUMERIC_LIMIT,
        ROUTE_NOT_FOUND,
        ROUTE_UNSUPPORTED,
        ROUTE_UNAVAILABLE,
        INSUFFICIENT_QI,
        CAPACITY_EXCEEDED,
        THROUGHPUT_EXCEEDED,
        TIME_REQUIRES_SETTLEMENT,
        TRANSPORT_FROZEN
    }

    public enum CapabilityRejectionReason {
        MODULE_NOT_FOUND, MODULE_UNAVAILABLE, RECIPE_NOT_FOUND, RECIPE_INCOMPATIBLE,
        ORGAN_BUSY, TISSUE_LOAD_EXCEEDED
    }

    public static final class CapabilityRejected extends RuntimeException {
        private final CapabilityRejectionReason reason;

        private CapabilityRejected(CapabilityRejectionReason reason) {
            // 原因枚举供边界映射，异常文本不承载宿主策略细节。
            super(required(reason, "能力拒绝原因不能为空").name());
            this.reason = reason;
        }

        public CapabilityRejectionReason reason() {
            // 查询和命令使用同一明确原因，不解析错误文案。
            return reason;
        }
    }

    public static final class TransportRejected extends RuntimeException {
        private final TransportRejectionReason reason;

        private TransportRejected(TransportRejectionReason reason) {
            // 错误只记录业务原因，传输层自行映射稳定的 Protobuf 失败码。
            super(required(reason, "运输拒绝原因不能为空").name());
            this.reason = reason;
        }

        public TransportRejectionReason reason() {
            // 返回不可变原因，便于宿主决定等待到达、恢复通路或调整请求。
            return reason;
        }
    }

    public static final class TimeRejected extends RuntimeException {
        private final TimeRejectionReason reason;

        private TimeRejected(TimeRejectionReason reason) {
            // 业务原因可由 Java 调用者检查，异常文本不充当通信协议。
            super(required(reason, "时间拒绝原因不能为空").name());
            this.reason = reason;
        }

        public TimeRejectionReason reason() {
            // 仅暴露稳定原因，策略和宿主细节不进入公开响应。
            return reason;
        }
    }

    /** 已通过格式校验、但被核心业务规则拒绝的寿元支出。 */
    public static final class LifespanRejected extends RuntimeException {
        private final LifespanRejectionReason reason;

        private LifespanRejected(LifespanRejectionReason reason) {
            // 理由必须明确且稳定；协议映射读取枚举，不能解析异常文本。
            super(required(reason, "业务拒绝原因不能为空").name());
            this.reason = reason;
        }

        public LifespanRejectionReason reason() {
            // 仅返回核心拒绝原因，公开响应由宿主映射为 Protobuf 枚举。
            return reason;
        }
    }
}
