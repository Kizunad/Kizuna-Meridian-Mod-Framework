package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;

/** 跨附属的可信健康宿主协议，复用核心命令、失败码与有界解析。 */
public final class HealthHostProtobuf {
    private HealthHostProtobuf() {
        // 身份绑定属于宿主；此处只维护交换消息的共同格式。
    }

    public static byte[] encodeRequest(HealthHostRequest request) {
        // 本地附属也必须满足协议边界，不能绕过正常请求的剂量和规模校验。
        validateRequest(request);
        return ProtoWire.write(request);
    }

    public static HealthHostRequest decodeRequest(byte[] payload) {
        // 先限制字节与嵌套，再检查健康专用入口允许的操作。
        HealthHostRequest request = ProtoWire.read(payload, HealthHostRequest.parser());
        validateRequest(request);
        return request;
    }

    private static void validateRequest(HealthHostRequest request) {
        // 外层请求负责宿主路由，内层请求保留身体版本与核心幂等身份。
        ProtoWire.validate(request);
        require(request.getProtocolVersion() == 1);
        if (request.hasInspect()) {
            require(request.getInspect());
            return;
        }
        if (request.hasExertion()) {
            // 做功仍走普通命令入口，不能借宿主操作夹带健康或其他身体命令。
            var exertion = request.getExertion();
            MeridianProtocol.encodeRequest(exertion);
            require(exertion.getRequestId().equals(request.getRequestId()));
            require(exertion.hasBodyCommand() && exertion.getBodyCommand().hasExertTissues());
            return;
        }
        var effect = request.getEffect();
        MeridianProtocol.encodeRequest(effect);
        require(effect.getRequestId().equals(request.getRequestId()));
        require(effect.hasBodyCommand() && HealthProtobuf.isHealthCommand(effect.getBodyCommand()));
    }

    public static byte[] encodeResponse(HealthHostResponse response) {
        // 返回完整权威快照或明确拒绝，避免把部分结果误认为伤害已落账。
        validateResponse(response);
        return ProtoWire.write(response);
    }

    public static HealthHostResponse decodeResponse(byte[] payload) {
        // 独立附属仍须校验宿主返回的身体，不能只信生成类型可解析。
        HealthHostResponse response = ProtoWire.read(payload, HealthHostResponse.parser());
        validateResponse(response);
        return response;
    }

    private static void validateResponse(HealthHostResponse response) {
        // unbound 与失败含义不同：只有未绑定允许继续寻找其他执行方。
        ProtoWire.validate(response);
        require(response.getProtocolVersion() == 1);
        // 固定效果事实必须与成功身体一起返回，拒绝或未绑定不能夹带保命凭据。
        require(!response.hasHealth() || response.hasBody());
        if (response.hasUnbound()) {
            require(response.getUnbound());
        } else if (response.hasBody()) {
            BodyProtobuf.fromMessage(response.getBody());
        }
    }

    private static void require(boolean condition) {
        // 诊断集中保存，线协议继续复用已有失败码。
        ProtoWire.require(condition, MeridianErrors.Health.HOST_EXCHANGE);
    }
}
