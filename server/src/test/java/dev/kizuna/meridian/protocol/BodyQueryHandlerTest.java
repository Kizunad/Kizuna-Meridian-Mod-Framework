package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyQueryHandler;
import dev.kizuna.meridian.server.BodySnapshotReader;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BodyQueryHandlerTest {
    @Test
    public void authorizedQueryReturnsMatchingSnapshotWithoutAdvancingState() {
        // 按外部输入输出验证完整查询，同时确认读取顺序和身体时间、资源均未变化。
        var body = ProtocolFixtures.snapshot(Realm.VOID_TRANSFORMATION);
        var steps = new ArrayList<String>();
        var handler = new BodyQueryHandler<String>((caller, id) -> {
            // 身份取自宿主上下文，不把客户端选择的目标身份当作操作者。
            steps.add("authorize");
            return caller.equals("trusted-player") && id.equals(body.id());
        }, id -> {
            // 提供同一份不可变权威快照，不为每次查询创建新身体。
            steps.add("read");
            return Optional.of(body);
        });

        var response = MeridianProtocol.decodeResponse(handler.handle("trusted-player", request(body.id().value())));
        assertEquals("query-1", response.getRequestId());
        assertEquals(body, BodyProtobuf.fromMessage(response.getBodySnapshot()));
        assertEquals(List.of("authorize", "read"), steps);
    }

    @Test
    public void deniedQueriesNeverReadStorageOrRevealExistence() {
        // 对有无该身体都在读取之前拒绝，避免未授权的存在性探测。
        var reads = new AtomicInteger();
        var handler = new BodyQueryHandler<String>((caller, id) -> false, id -> {
            reads.incrementAndGet();
            return Optional.empty();
        });
        for (String bodyId : List.of("creature-1", "missing-body")) {
            assertFailure(handler.handle("other-player", request(bodyId)), MeridianMessages.FailureCode.ACCESS_DENIED);
        }
        assertEquals(0, reads.get());
    }

    @Test
    public void missingTrustedContextIsDeniedBeforeCallbacks() {
        // 未认证连接不能靠宽松策略兜底变成已认证调用。
        var calls = new AtomicInteger();
        var handler = new BodyQueryHandler<String>((caller, id) -> {
            calls.incrementAndGet();
            return true;
        }, id -> {
            calls.incrementAndGet();
            return Optional.empty();
        });
        assertFailure(handler.handle(null, request("creature-1")), MeridianMessages.FailureCode.ACCESS_DENIED);
        assertEquals(0, calls.get());
    }

    @Test
    public void authorizedMissingBodyIsNotCreated() {
        // 查询只读；不存在时返回业务错误，不凭模板补出默认身体。
        var handler = new BodyQueryHandler<String>((caller, id) -> true, id -> Optional.empty());
        assertFailure(handler.handle("trusted-player", request("missing-body")), MeridianMessages.FailureCode.BODY_NOT_FOUND);
    }

    @Test
    public void repeatedRequestRechecksAuthorizationRatherThanReplayingPrivateData() {
        // 查询 ID 只作关联，权限撤销后即使复用旧 ID 也不能读缓存中的私有快照。
        var checks = new AtomicInteger();
        var reads = new AtomicInteger();
        var body = ProtocolFixtures.snapshot(Realm.AWAKENING);
        var handler = new BodyQueryHandler<String>((caller, id) -> checks.incrementAndGet() == 1, id -> {
            reads.incrementAndGet();
            return Optional.of(body);
        });
        var payload = request(body.id().value());
        assertTrue(MeridianProtocol.decodeResponse(handler.handle("trusted-player", payload)).hasBodySnapshot());
        assertFailure(handler.handle("trusted-player", payload), MeridianMessages.FailureCode.ACCESS_DENIED);
        assertEquals(2, checks.get());
        assertEquals(1, reads.get());
    }

    @Test
    public void mismatchedBodyFromHostNeverLeaksAsSuccess() {
        // 存储串号属于宿主故障；不能把另一个身体发送给当前目标的合法读者。
        var handler = new BodyQueryHandler<String>((caller, id) -> true,
                id -> Optional.of(ProtocolFixtures.snapshot(Realm.AWAKENING)));
        assertFailure(handler.handle("trusted-player", request("different-body")), MeridianMessages.FailureCode.INTERNAL_ERROR);
    }

    @Test
    public void policyFailureDoesNotFallThroughToStorage() {
        // 权限回调故障必须失败关闭，且公开响应不含私有异常文本。
        var reads = new AtomicInteger();
        var handler = new BodyQueryHandler<String>((caller, id) -> {
            throw new IllegalStateException("private-policy-detail");
        }, id -> {
            reads.incrementAndGet();
            return Optional.empty();
        });
        assertFailure(handler.handle("trusted-player", request("creature-1")), MeridianMessages.FailureCode.INTERNAL_ERROR);
        assertEquals(0, reads.get());
    }

    @Test
    public void storageFailuresDoNotMasqueradeAsMissingBodies() {
        // 故障与确实不存在分开，null 也视为违反读取契约而非不存在。
        List<BodySnapshotReader> faultyReaders = List.of(id -> {
            throw new IllegalArgumentException("private-storage-detail");
        }, id -> null);
        for (var reader : faultyReaders) {
            var handler = new BodyQueryHandler<String>((caller, id) -> true, reader);
            assertFailure(handler.handle("trusted-player", request("creature-1")), MeridianMessages.FailureCode.INTERNAL_ERROR);
        }
    }

    @Test
    public void malformedUnsupportedOrOversizedRequestsCannotReachHostCallbacks() {
        // 不合规输入缺少可依赖的完整请求，不构造虚假的关联响应，也不调用宿主。
        var calls = new AtomicInteger();
        var handler = new BodyQueryHandler<String>((caller, id) -> {
            calls.incrementAndGet();
            return true;
        }, id -> {
            calls.incrementAndGet();
            return Optional.empty();
        });
        var valid = request("creature-1");
        var unknown = Arrays.copyOf(valid, valid.length + 2);
        unknown[valid.length] = 0x78;
        unknown[valid.length + 1] = 1;
        var unsupported = ProtocolFixtures.request().toBuilder().setProtocolVersion(2).build().toByteArray();
        for (byte[] invalid : new byte[][] {null, new byte[0], new byte[] {0x0a},
                Arrays.copyOf(valid, valid.length - 1), unknown, unsupported,
                new byte[MeridianProtocol.MAX_PAYLOAD_BYTES + 1]}) {
            assertThrows(IllegalArgumentException.class, () -> handler.handle("trusted-player", invalid));
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void snapshotResponseCannotBeUsedAsAQuery() {
        // 服务入口不能让响应消息绕过请求方向限制成为身体上传操作。
        var body = ProtocolFixtures.snapshot(Realm.AWAKENING);
        var response = MeridianMessages.ResponseEnvelope.newBuilder()
                .setProtocolVersion(1).setRequestId("query-1")
                .setBodySnapshot(BodyProtobuf.toMessage(body)).build();
        var calls = new AtomicInteger();
        var handler = new BodyQueryHandler<String>((caller, id) -> {
            calls.incrementAndGet();
            return true;
        }, id -> Optional.of(body));
        assertThrows(IllegalArgumentException.class,
                () -> handler.handle("trusted-player", MeridianProtocol.encodeResponse(response)));
        assertEquals(0, calls.get());
    }

    private static byte[] request(String bodyId) {
        // 所有常规用例通过正式编码入口，非法输入用例再单独破坏字节。
        return MeridianProtocol.encodeRequest(MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION).setRequestId("query-1")
                .setGetBodySnapshot(MeridianMessages.GetBodySnapshot.newBuilder().setBodyId(bodyId)).build());
    }

    private static void assertFailure(byte[] payload, MeridianMessages.FailureCode code) {
        // 整份响应比较保证错误不夹带快照、异常文本或额外私有字段。
        var actual = MeridianProtocol.decodeResponse(payload);
        var expected = MeridianMessages.ResponseEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION).setRequestId("query-1")
                .setFailure(MeridianMessages.RequestFailure.newBuilder().setCode(code)).build();
        assertEquals(expected, actual);
        assertFalse(actual.hasBodySnapshot());
    }
}
