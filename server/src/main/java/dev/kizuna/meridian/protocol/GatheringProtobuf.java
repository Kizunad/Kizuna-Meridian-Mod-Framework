package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringRequest;

/** 聚气入口复用框架的大小、递归、未知字段与必填检查。 */
public final class GatheringProtobuf {
    private GatheringProtobuf() {
        // 此工具只解码意图，不读取身体、环境或时钟。
    }

    public static GatheringRequest decodeRequest(byte[] payload) {
        // 配方参数与可信时间没有上传字段，客户端只能选择已注册内容。
        GatheringRequest request = ProtoWire.read(payload, GatheringRequest.parser());
        if (request.getProtocolVersion() != 1 || (request.hasStart()
                && (request.getStart().getExpectedRevision() < 0 || request.getStart().getRecipeVersion() <= 0))) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_GATHERING);
        }
        if (request.hasBreath() && (request.getBreath().getAimX() > 1 || request.getBreath().getAimY() > 1
                || request.getBreath().getAimZ() > 1
                || request.getBreath().getSequence() == 0)) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_GATHERING);
        }
        return request;
    }
}
