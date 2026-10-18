package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.pb.OpeningMessages.MeridianOpeningRequest;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 连接内的开脉入口；串行结清旧时间后再开始操作，身体事实仍由命令处理器保存。 */
public final class OpeningSession<C> {
    private final C context;
    private final BodyId bodyId;
    private final BodyCommandHandler<C> commands;
    private final BodySnapshotReader bodies;
    private final BodyReadPolicy<C> access;
    private final Map<String, Reply> replies = new LinkedHashMap<>();

    public OpeningSession(C context, BodyId bodyId, BodyCommandHandler<C> commands,
                          BodySnapshotReader bodies, BodyReadPolicy<C> access) {
        // 宿主绑定身份与身体；所有调用和其他身体事件在同一宿主执行序列中处理。
        this.context = context;
        this.bodyId = bodyId;
        this.commands = commands;
        this.bodies = bodies;
        this.access = access;
    }

    public ResponseEnvelope handle(MeridianOpeningRequest request) {
        // 重试先重新确认授权，再比较原始意图；不能用旧连接回执绕过撤权。
        OpeningProtobuf.validateRequest(request);
        if (!request.getBodyId().equals(bodyId.value()) || !access.canRead(context, bodyId)) {
            return failure(request, FailureCode.ACCESS_DENIED);
        }
        Reply previous = replies.get(request.getRequestId());
        if (previous != null) {
            if (!previous.request().equals(request)) {
                return failure(request, FailureCode.REQUEST_ID_CONFLICT);
            }
            return previous.response();
        }

        // 客户端依据的版本必须是当前身体；只允许随后由本入口结时导致的版本变化。
        BodySnapshot body = bodies.find(bodyId).orElse(null);
        ResponseEnvelope response;
        if (body == null) {
            response = failure(request, FailureCode.BODY_NOT_FOUND);
        } else if (body.revision() != request.getExpectedRevision()) {
            response = failure(request, FailureCode.REVISION_CONFLICT);
        } else {
            response = execute(request, body);
        }
        replies.put(request.getRequestId(), new Reply(request, response));
        if (replies.size() > 64) {
            // 回执缓存只解决当前连接的重试；被淘汰的旧意图仍受身体 revision 保护。
            replies.remove(replies.keySet().iterator().next());
        }
        return response;
    }

    private ResponseEnvelope execute(MeridianOpeningRequest request, BodySnapshot body) {
        // 旧区间沿旧动作结算，避免把按下按钮以前的时间算作凝聚或免除已有负载。
        ResponseEnvelope settled = command(UUID.randomUUID().toString(), BodyCommand.newBuilder()
                .setBodyId(bodyId.value()).setExpectedRevision(body.revision())
                .setAdvanceTime(AdvanceTime.getDefaultInstance()));
        if (settled.hasFailure() && settled.getFailure().getCode() != FailureCode.TIME_NOT_ADVANCED) {
            return failure(request, settled.getFailure().getCode());
        }

        BodySnapshot current = bodies.find(bodyId).orElseThrow();
        var intent = BodyCommand.newBuilder().setBodyId(bodyId.value()).setExpectedRevision(current.revision());
        if (request.hasStart()) {
            intent.setStartMeridianOpening(request.getStart());
        } else {
            intent.setPauseMeridianOpening(request.getPause());
        }
        return command(request.getRequestId(), intent);
    }

    private ResponseEnvelope command(String requestId, BodyCommand.Builder command) {
        // 授权、私有策略、目录、版本、费用与事务仍经过唯一权威处理器。
        var envelope = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(requestId)
                .setBodyCommand(command).build();
        return MeridianProtocol.decodeResponse(commands.handle(context, MeridianProtocol.encodeRequest(envelope)));
    }

    private static ResponseEnvelope failure(MeridianOpeningRequest request, FailureCode code) {
        // 对外仅返回公开失败码，响应身份固定为本次请求。
        return ResponseEnvelope.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId())
                .setFailure(RequestFailure.newBuilder().setCode(code)).build();
    }

    private record Reply(MeridianOpeningRequest request, ResponseEnvelope response) { }
}
