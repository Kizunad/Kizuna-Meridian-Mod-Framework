package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

import java.util.Objects;

/**
 * Java 参考查询处理器：接收一个完整 Protobuf 请求，返回一个完整 Protobuf 响应。
 * 不注册网络、不认证连接、不持有身体副本；宿主负责串行调度或等价的一致读取边界。
 *
 * @param <C> 宿主已经验证的调用上下文，不属于客户端线协议
 */
public final class BodyQueryHandler<C> {
    private static final System.Logger LOGGER = System.getLogger(BodyQueryHandler.class.getName());

    private final BodyReadPolicy<C> policy;
    private final BodySnapshotReader snapshots;

    public BodyQueryHandler(BodyReadPolicy<C> policy, BodySnapshotReader snapshots) {
        // 权限策略必须显式提供，避免遗漏接入配置时默认公开所有身体。
        this.policy = Objects.requireNonNull(policy, "必须提供身体读取权限策略");
        this.snapshots = Objects.requireNonNull(snapshots, "必须提供权威快照读取入口");
    }

    /**
     * 非法或不支持版本的请求抛出 IllegalArgumentException，不调用宿主回调；
     * 此时关联 ID 未通过完整校验，由传输适配丢弃或拒绝，不伪造响应 ID。
     * 合法请求的拒绝及宿主故障使用协议错误码返回。
     */
    public byte[] handle(C trustedContext, byte[] payload) {
        // 格式拒绝位于业务异常处理之外，不能把恶意输入误报成服务器故障。
        var request = MeridianProtocol.decodeRequest(payload);
        if (trustedContext == null) {
            // 缺少可信上下文时明确拒绝，既不调用策略，也不探测身体是否存在。
            return failure(request.getRequestId(), MeridianMessages.FailureCode.ACCESS_DENIED);
        }

        try {
            // 当前协议只有快照查询；显式分派使以后新增消息必须另写处理逻辑。
            return switch (request.getRequestCase()) {
                case GET_BODY_SNAPSHOT -> query(trustedContext, request);
                case REQUEST_NOT_SET -> throw new IllegalArgumentException("缺少查询意图");
            };
        } catch (RuntimeException exception) {
            // 内部诊断只进服务端日志；不得把存储路径、策略详情或异常文本发给客户端。
            LOGGER.log(System.Logger.Level.ERROR, "身体快照查询处理失败", exception);
            return failure(request.getRequestId(), MeridianMessages.FailureCode.INTERNAL_ERROR);
        }
    }

    private byte[] query(C trustedContext, MeridianMessages.RequestEnvelope request) {
        // 先授权后读取，未授权者无法通过不存在与存在的区别枚举身体。
        var bodyId = new BodyId(request.getGetBodySnapshot().getBodyId());
        if (!policy.canRead(trustedContext, bodyId)) {
            return failure(request.getRequestId(), MeridianMessages.FailureCode.ACCESS_DENIED);
        }
        var snapshot = Objects.requireNonNull(snapshots.find(bodyId), "快照读取入口不能返回 null");
        if (snapshot.isEmpty()) {
            return failure(request.getRequestId(), MeridianMessages.FailureCode.BODY_NOT_FOUND);
        }

        // 防止宿主索引或缓存串号；授权给一个身体不能用于返回另一个身体的数据。
        var body = snapshot.get();
        if (!body.id().equals(bodyId)) {
            throw new IllegalStateException("快照身份与查询目标不一致");
        }
        var response = MeridianMessages.ResponseEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(request.getRequestId())
                .setBodySnapshot(BodyProtobuf.toMessage(body))
                .build();
        // 发送也执行完整边界校验；不合规或超限的宿主快照只能返回内部错误。
        return MeridianProtocol.encodeResponse(response);
    }

    private static byte[] failure(String requestId, MeridianMessages.FailureCode code) {
        // 所有合法请求的失败保留关联 ID，线协议只包含固定公开类别。
        var response = MeridianMessages.ResponseEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId)
                .setFailure(MeridianMessages.RequestFailure.newBuilder().setCode(code))
                .build();
        return MeridianProtocol.encodeResponse(response);
    }
}
