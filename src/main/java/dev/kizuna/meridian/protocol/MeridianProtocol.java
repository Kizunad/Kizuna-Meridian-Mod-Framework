package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

/** 宿主无关的 Protobuf 请求/响应边界；不注册网络通道，也不授予读取权限。 */
public final class MeridianProtocol {
    public static final int VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    public static final int MAX_RECURSION_DEPTH = 32;
    public static final int MAX_LIST_ENTRIES = 4096;
    public static final int MAX_FIELD_VALUES = 65536;
    public static final int MAX_TEXT_BYTES = 1024;

    private MeridianProtocol() {
        // 静态编解码不持有连接或会话，身份与权限由宿主绑定。
    }

    public static MeridianMessages.RequestEnvelope decodeRequest(byte[] bytes) {
        // 请求解析入口只接受请求类型，响应快照不能被当成客户端写入指令。
        var request = ProtoWire.read(bytes, MeridianMessages.RequestEnvelope.parser());
        validateRequest(request);
        return request;
    }

    public static byte[] encodeRequest(MeridianMessages.RequestEnvelope request) {
        // 本地构造的生成消息也必须满足相同的版本与意图校验。
        validateRequest(request);
        return ProtoWire.write(request);
    }

    public static MeridianMessages.ResponseEnvelope decodeResponse(byte[] bytes) {
        // 响应携带的数据需要经过核心校验，不能只验证外层信封。
        var response = ProtoWire.read(bytes, MeridianMessages.ResponseEnvelope.parser());
        validateResponse(response);
        return response;
    }

    public static byte[] encodeResponse(MeridianMessages.ResponseEnvelope response) {
        // 发送前验证快照或失败类别，禁止发送未经校验的半份身体。
        validateResponse(response);
        return ProtoWire.write(response);
    }

    private static void validateRequest(MeridianMessages.RequestEnvelope request) {
        // 当前只开放快照查询的数据契约，未实现的编辑或激发操作不得伪装成成功。
        ProtoWire.validate(request);
        ProtoWire.require(request.getProtocolVersion() == VERSION, "不支持的请求协议版本");
        switch (request.getRequestCase()) {
            case GET_BODY_SNAPSHOT -> new BodyId(request.getGetBodySnapshot().getBodyId());
            case REQUEST_NOT_SET -> throw new IllegalArgumentException("缺少查询意图");
        }
    }

    private static void validateResponse(MeridianMessages.ResponseEnvelope response) {
        // request_id 仅用于响应关联；有权读取哪个身体必须由宿主在生成响应前检查。
        ProtoWire.validate(response);
        ProtoWire.require(response.getProtocolVersion() == VERSION, "不支持的响应协议版本");
        switch (response.getResultCase()) {
            case BODY_SNAPSHOT -> BodyProtobuf.fromMessage(response.getBodySnapshot());
            case FAILURE -> {
                // 失败码存在性及未知枚举已由公共边界校验，载荷不含私有策略详情。
            }
            case RESULT_NOT_SET -> throw new IllegalArgumentException("缺少查询结果");
        }
    }
}
