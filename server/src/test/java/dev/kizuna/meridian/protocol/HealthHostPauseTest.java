package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** 暂停是已认领身体的失败状态，不能编码成未绑定。 */
public final class HealthHostPauseTest {
    @Test
    public void pausedClaimSurvivesHealthHostRoundTrip() {
        // 公开编解码保留原始失败码，下游据此停止轮询并封住原版回退。
        var failure = RequestFailure.newBuilder().setCode(FailureCode.BODY_PAUSED).build();
        var response = HealthHostResponse.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("paused-body")
                .setFailure(failure)
                .build();
        var decoded = HealthHostProtobuf.decodeResponse(HealthHostProtobuf.encodeResponse(response));
        assertEquals(response, decoded);
        assertFalse(decoded.getUnbound());
    }
}
