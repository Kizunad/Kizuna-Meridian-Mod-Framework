package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.ActivityHostMessages.ActivityHostRequest;
import dev.kizuna.meridian.server.BodyActivityExecution;

/** 身体活动宿主沿用核心版本、预期修订和幂等身份，只接受可信原子计划。 */
public final class ActivityHostProtobuf {
    private ActivityHostProtobuf() {
        // 编解码不选择身体执行方，也不授予客户端活动权限。
    }

    public static byte[] encodeRequest(ActivityHostRequest request) {
        // 本地调用同样经过有界格式与活动类型校验。
        validateRequest(request);
        return ProtoWire.write(request);
    }

    public static ActivityHostRequest decodeRequest(byte[] payload) {
        // 先限制载荷大小和深度，再验证内外身份及操作类型。
        ActivityHostRequest request = ProtoWire.read(payload, ActivityHostRequest.parser());
        validateRequest(request);
        return request;
    }

    private static void validateRequest(ActivityHostRequest request) {
        // 活动入口不能夹带健康或管理员命令；核心负责原子结算与权限验证。
        ProtoWire.validate(request);
        var envelope = request.getActivity();
        // 请求解析会检查必填字段、预期版本及活动规模，非法时抛 IllegalArgumentException。
        MeridianProtocol.encodeRequest(envelope);
        boolean valid = request.getProtocolVersion() == MeridianProtocol.VERSION
                && request.getRequestId().equals(envelope.getRequestId())
                && envelope.hasBodyCommand() && envelope.getBodyCommand().hasExecuteActivity();
        ProtoWire.require(valid, MeridianErrors.Health.ACTIVITY_HOST_EXCHANGE);
        BodyActivityExecution.validate(envelope.getBodyCommand().getExecuteActivity());
    }
}
