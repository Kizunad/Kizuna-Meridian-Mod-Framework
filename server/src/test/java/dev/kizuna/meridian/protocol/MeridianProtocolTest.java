package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import org.junit.Test;

import java.util.HexFormat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class MeridianProtocolTest {
    @Test
    public void requestMatchesFixedProtobufWireContract() {
        // 固定字节锁定字段号与传输格式，避免 Java 同时改写编码解码而往返测试仍通过。
        byte[] fixture = HexFormat.of().parseHex("08011202713152080a06626f64792d31");
        assertEquals(ProtocolFixtures.request(), MeridianProtocol.decodeRequest(fixture));
        assertArrayEquals(fixture, MeridianProtocol.encodeRequest(ProtocolFixtures.request()));
    }

    @Test
    public void fullSnapshotResponsePreservesRequestCorrelation() {
        // 响应与请求使用同一关联 ID；完整身体保留全部模块与明细。
        var response = MeridianMessages.ResponseEnvelope.newBuilder().setProtocolVersion(1).setRequestId("q1")
                .setBodySnapshot(BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.VOID_TRANSFORMATION))).build();
        assertEquals(response, MeridianProtocol.decodeResponse(MeridianProtocol.encodeResponse(response)));
    }

    @Test
    public void failureMatchesFixedWireCodeWithoutLeakingPrivateDetails() {
        // 公开拒绝只传固定错误类别，未知类别不能按成功或默认失败处理。
        var failure = MeridianMessages.ResponseEnvelope.newBuilder().setProtocolVersion(1).setRequestId("q1")
                .setFailure(MeridianMessages.RequestFailure.newBuilder().setCode(MeridianMessages.FailureCode.ACCESS_DENIED)).build();
        byte[] fixture = HexFormat.of().parseHex("0801120271315a020802");
        assertArrayEquals(fixture, MeridianProtocol.encodeResponse(failure));
        assertEquals(failure, MeridianProtocol.decodeResponse(fixture));
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeResponse(failure.toBuilder()
                .setFailure(MeridianMessages.RequestFailure.newBuilder().setCodeValue(999)).build().toByteArray()));
    }

    @Test
    public void invalidRequestsAreRejectedOnBothEncodeAndDecode() {
        // 版本、关联 ID 和明确意图都是必需项，缺失不能成为隐式默认操作。
        var request = ProtocolFixtures.request();
        for (var invalid : new MeridianMessages.RequestEnvelope[] {
                request.toBuilder().setProtocolVersion(2).build(),
                request.toBuilder().clearRequestId().build(),
                request.toBuilder().clearGetBodySnapshot().build(),
                request.toBuilder().setGetBodySnapshot(MeridianMessages.GetBodySnapshot.getDefaultInstance()).build()
        }) {
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeRequest(invalid));
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(invalid.toByteArray()));
        }
    }

    @Test
    public void bodyResponseCannotBeSubmittedAsRequest() {
        // 请求类型没有写入快照分支，误用响应载荷必须失败，不能产生状态覆盖入口。
        var response = MeridianMessages.ResponseEnvelope.newBuilder().setProtocolVersion(1).setRequestId("q1")
                .setBodySnapshot(BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.AWAKENING))).build();
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(response.toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeResponse(ProtocolFixtures.request().toByteArray()));
    }

    @Test
    public void invalidSnapshotIsRejectedBeforeAResponseIsAccepted() {
        // 外层响应合法也不能掩盖超额 HP 或缺失局部状态。
        var body = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.AWAKENING)).toBuilder().clearPartStates();
        var response = MeridianMessages.ResponseEnvelope.newBuilder().setProtocolVersion(1).setRequestId("q1")
                .setBodySnapshot(body).build();
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeResponse(response));
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeResponse(response.toByteArray()));
    }

    @Test
    public void commandAndCommittedResponseMatchFixedWireContract() {
        // 固定字段编号、显式零 revision 与 double 生物秒编码，防止双方映射同时改错。
        var request = ProtocolFixtures.request().toBuilder()
                .setBodyCommand(MeridianMessages.BodyCommand.newBuilder().setBodyId("body-1").setExpectedRevision(0)
                        .setSpendLifespan(MeridianMessages.SpendLifespan.newBuilder().setSeconds(5))).build();
        var wire = HexFormat.of().parseHex("0801120271315a150a06626f64792d3110005209090000000000001440");
        assertArrayEquals(wire, MeridianProtocol.encodeRequest(request));
        assertEquals(request, MeridianProtocol.decodeRequest(wire));
        var response = MeridianMessages.ResponseEnvelope.newBuilder().setProtocolVersion(1).setRequestId("q1")
                .setCommandCommitted(MeridianMessages.CommandCommitted.newBuilder().setBodyId("body-1")
                        .setPreviousRevision(0).setRevision(1)).build();
        var committed = HexFormat.of().parseHex("080112027131620c0a06626f64792d3110001801");
        assertArrayEquals(committed, MeridianProtocol.encodeResponse(response));
        assertEquals(response, MeridianProtocol.decodeResponse(committed));
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(committed));
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeResponse(wire));
    }

    @Test
    public void malformedCommandsAndMismatchedReceiptsAreRejected() {
        // 缺失、零剂量及非有限值不能到达授权；成功回执必须绑定原目标和版本。
        var command = MeridianMessages.BodyCommand.newBuilder().setBodyId("body-1").setExpectedRevision(0)
                .setSpendLifespan(MeridianMessages.SpendLifespan.newBuilder().setSeconds(5)).build();
        var invalidCommands = new java.util.ArrayList<MeridianMessages.BodyCommand>();
        invalidCommands.add(command.toBuilder().clearExpectedRevision().build());
        invalidCommands.add(command.toBuilder().setExpectedRevision(-1).build());
        invalidCommands.add(command.toBuilder().clearSpendLifespan().build());
        for (double amount : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            invalidCommands.add(command.toBuilder().setSpendLifespan(
                    MeridianMessages.SpendLifespan.newBuilder().setSeconds(amount)).build());
        }
        for (var invalid : invalidCommands) {
            var envelope = ProtocolFixtures.request().toBuilder().setBodyCommand(invalid).build();
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeRequest(envelope));
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(envelope.toByteArray()));
        }
        var receipt = MeridianMessages.CommandReceipt.newBuilder().setCallerId("caller").setRequestId("q1")
                .setCommand(command).setCommitted(MeridianMessages.CommandCommitted.newBuilder()
                        .setBodyId("body-1").setPreviousRevision(0).setRevision(1)).build();
        for (var invalid : new MeridianMessages.CommandReceipt[] {
                receipt.toBuilder().setCommitted(receipt.getCommitted().toBuilder().setBodyId("wrong-body")).build(),
                receipt.toBuilder().setCommitted(receipt.getCommitted().toBuilder().setRevision(2)).build(),
                receipt.toBuilder().setCommand(command.toBuilder().setExpectedRevision(1)).build(),
                receipt.toBuilder().setCommitted(receipt.getCommitted().toBuilder()
                        .setPreviousRevision(Long.MAX_VALUE).setRevision(0)).build()
        }) {
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeReceipt(invalid));
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeReceipt(invalid.toByteArray()));
        }
    }

    @Test
    public void unknownNestedGroupsRespectParserDepthLimit() {
        // 未知字段也可能带嵌套 group，生成解析器必须在业务校验前限制递归。
        int depth = MeridianProtocol.MAX_RECURSION_DEPTH + 1;
        byte[] payload = new byte[depth * 2];
        for (int i = 0; i < depth; i++) {
            payload[i] = 0x73;
            payload[depth + i] = 0x74;
        }
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(payload));
    }

    @Test
    public void invalidUnicodeCannotSilentlyChangeAnIdentity() {
        // 发送端拒绝孤立代理项，接收端拒绝非法 UTF-8，不能用替换字符修复身份。
        var request = ProtocolFixtures.request().toBuilder().setRequestId(String.valueOf((char) 0xd800)).build();
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeRequest(request));
        byte[] malformed = HexFormat.of().parseHex("08011201ff52080a06626f64792d31");
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(malformed));
    }
}
