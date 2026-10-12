package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyAuditSink;
import dev.kizuna.meridian.server.BodyCommandAccess;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodySecurityPolicy;
import dev.kizuna.meridian.server.BodyTransactionStore;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.*;

public class BodyCommandHandlerTest {
    @Test
    public void spendingChangesOnlyLifespanAndRevisionAndSurvivesReceiptRestore() {
        // 通过正式线协议观察完整状态；重建处理器与存储后重试仍只能扣一次。
        var initial = body();
        var store = store(initial);
        var audits = new AtomicInteger();
        var handler = handler(store, (context, command, current) -> true,
                (context, receipt, before, after) -> audits.incrementAndGet());
        var payload = request("spend-1", 0, 5);
        var first = handler.handle("player", payload);
        assertEquals(1, MeridianProtocol.decodeResponse(first).getCommandCommitted().getRevision());
        var expected = BodyProtobuf.toMessage(initial).toBuilder().setRevision(1)
                .setVitality(BodyProtobuf.toMessage(initial).getVitality().toBuilder().setSpentLifespanSeconds(10));
        var saved = store.find(initial.id()).orElseThrow();
        assertEquals(BodyProtobuf.fromMessage(expected.build()), saved);
        assertArrayEquals(first, handler.handle("player", payload));
        // Protobuf 允许同值单字段重复编码；去重按解析后的命令，不能按原始字节哈希。
        var equivalentWire = java.util.Arrays.copyOf(payload, payload.length + 2);
        equivalentWire[payload.length] = 0x08;
        equivalentWire[payload.length + 1] = 0x01;
        assertArrayEquals(first, handler.handle("player", equivalentWire));
        assertEquals(1, audits.get());

        // 回执不是客户端载荷，按内部 Protobuf 记录与身体一起恢复。
        var receipt = store.transact(initial.id(), tx -> tx.receipt("player", "spend-1").orElseThrow());
        var restored = new InMemoryBodyStore(List.of(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(saved))),
                List.of(MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt))));
        var resumed = handler(restored, (context, command, current) -> {
            throw new AssertionError("已提交重试不能再次调用私有策略");
        }, (context, committed, before, after) -> {
            throw new AssertionError("已提交重试不能再次通知审计");
        });
        assertArrayEquals(first, resumed.handle("player", payload));
        assertEquals(saved, restored.find(initial.id()).orElseThrow());
    }

    @Test
    public void requestIdentityIncludesCallerAndBodyAndRejectsChangedParameters() {
        // 同一个成功 ID 不能换剂量或 revision；不同调用者或身体不能串用它的成功回执。
        var initial = body();
        var other = BodyProtobuf.fromMessage(BodyProtobuf.toMessage(initial).toBuilder().setId("other-body").build());
        var store = new InMemoryBodyStore(List.of(initial, other), List.of());
        var handler = handler(store);
        var original = handler.handle("alice", request("same", 0, 5));
        assertFailure(handler.handle("alice", request("same", 1, 6)), MeridianMessages.FailureCode.REQUEST_ID_CONFLICT);
        assertFailure(handler.handle("bob", request("same", 0, 5)), MeridianMessages.FailureCode.REVISION_CONFLICT);
        assertTrue(MeridianProtocol.decodeResponse(handler.handle("bob", request("same", 1, 5))).hasCommandCommitted());
        assertArrayEquals(original, handler.handle("alice", request("same", 0, 5)));
        assertEquals(2, store.find(initial.id()).orElseThrow().revision());
        var otherRequest = MeridianProtocol.decodeRequest(request("same", 0, 5));
        var otherPayload = MeridianProtocol.encodeRequest(otherRequest.toBuilder()
                .setBodyCommand(otherRequest.getBodyCommand().toBuilder().setBodyId("other-body")).build());
        assertEquals("other-body", MeridianProtocol.decodeResponse(handler.handle("alice", otherPayload))
                .getCommandCommitted().getBodyId());
    }

    @Test
    public void concurrentDuplicatesReplayButCompetingCommandsConflict() throws Exception {
        // 用两个并发调用验证串行事务的外部结果：重复请求同结果，不同请求仅一个提交。
        for (boolean duplicate : List.of(true, false)) {
            var store = store(body());
            var audits = new AtomicInteger();
            var handler = handler(store, (context, command, current) -> true,
                    (context, receipt, before, after) -> audits.incrementAndGet());
            var start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> {
                    // 两个请求都使用初始版本，交错顺序不影响唯一提交语义。
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return handler.handle("player", request("one", 0, 5));
                });
                var second = executor.submit(() -> {
                    // 重复组用同一个去重键，竞争组使用不同键。
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return handler.handle("player", request(duplicate ? "one" : "two", 0, 5));
                });
                start.countDown();
                var a = first.get(5, TimeUnit.SECONDS);
                var b = second.get(5, TimeUnit.SECONDS);
                if (duplicate) {
                    assertArrayEquals(a, b);
                    assertTrue(MeridianProtocol.decodeResponse(a).hasCommandCommitted());
                } else {
                    var left = MeridianProtocol.decodeResponse(a);
                    var right = MeridianProtocol.decodeResponse(b);
                    assertTrue(left.hasCommandCommitted() ^ right.hasCommandCommitted());
                    assertEquals(MeridianMessages.FailureCode.REVISION_CONFLICT,
                            (left.hasFailure() ? left : right).getFailure().getCode());
                }
                assertEquals(1, store.find(body().id()).orElseThrow().revision());
                assertEquals(10, store.find(body().id()).orElseThrow().vitality().spentLifespanSeconds(), 0);
                assertEquals(1, audits.get());
            } finally {
                // 用例失败时也释放线程，不留下后台执行污染其他验证。
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void deniedOrUnauthenticatedCallsNeverAccessStorageAndMalformedCallsNeverAuthorize() {
        // 不存在性和历史回执均属于授权后的信息，拒绝必须发生在任何存储回调之前。
        var accessCalls = new AtomicInteger();
        BodyTransactionStore forbidden = new BodyTransactionStore() {
            @Override
            public <T> T transact(BodyId bodyId, Function<Transaction, T> work) {
                // 一旦访问存储就失败，证明拒绝路径没有探测身体。
                throw new AssertionError("未授权请求不应进入存储");
            }
        };
        var handler = new BodyCommandHandler<String>((context, command) -> {
            accessCalls.incrementAndGet();
            return Optional.empty();
        }, (context, command, current) -> true, BodyCommandHandlerTest::unusedTime,
                forbidden, (context, receipt, before, after) -> { });
        assertThrows(IllegalArgumentException.class, () -> handler.handle("player", new byte[] {10}));
        assertThrows(IllegalArgumentException.class,
                () -> handler.handle("player", MeridianProtocol.encodeRequest(ProtocolFixtures.request())));
        assertFailure(handler.handle(null, request("one", 0, 5)), MeridianMessages.FailureCode.ACCESS_DENIED);
        assertEquals(0, accessCalls.get());
        assertFailure(handler.handle("player", request("one", 0, 5)), MeridianMessages.FailureCode.ACCESS_DENIED);
        assertEquals(1, accessCalls.get());

        // 等待事务期间失去授权也不能看到 BODY_NOT_FOUND，必须先重新授权再读回执/身体。
        var attempts = new AtomicInteger();
        var revokedWhileWaiting = new BodyCommandHandler<String>((context, command) -> attempts.incrementAndGet() == 1
                ? Optional.of(context) : Optional.empty(), (context, command, current) -> true,
                BodyCommandHandlerTest::unusedTime, new InMemoryBodyStore(List.of(), List.of()),
                (context, receipt, before, after) -> { });
        assertFailure(revokedWhileWaiting.handle("player", request("one", 0, 5)),
                MeridianMessages.FailureCode.ACCESS_DENIED);
        assertEquals(2, attempts.get());
    }

    @Test
    public void policyFailureAndAuthorizationRevocationLeaveNoStateOrReceipt() {
        // 私有拒绝、异常以及策略执行期间撤权都不能留下扣款或成功记录。
        for (int scenario : List.of(0, 1, 2, 3)) {
            var store = store(body());
            var allowed = new AtomicBoolean(true);
            BodyCommandAccess<String> access = (context, command) -> allowed.get()
                    ? Optional.of("player") : scenario == 3 ? Optional.of("changed-caller") : Optional.empty();
            var handler = new BodyCommandHandler<>(access, (context, command, current) -> {
                // 用宿主替身模拟外部策略结果，私有检测算法不进入公共框架。
                if (scenario == 0) {
                    return false;
                }
                if (scenario == 1) {
                    throw new IllegalStateException("private-detail");
                }
                allowed.set(false);
                return true;
            }, BodyCommandHandlerTest::unusedTime, store, (context, receipt, before, after) -> { });
            assertFailure(handler.handle("player", request("one", 0, 5)), scenario == 1
                    ? MeridianMessages.FailureCode.INTERNAL_ERROR : MeridianMessages.FailureCode.ACCESS_DENIED);
            assertEquals(body(), store.find(body().id()).orElseThrow());
            assertTrue(store.transact(body().id(), tx -> tx.receipt("player", "one").isEmpty()));
        }
    }

    @Test
    public void replayStillRequiresCurrentAuthorization() {
        // 之前成功并不授予永久访问权；撤权后的重试不能直接读取去重结果。
        var store = store(body());
        var allowed = new AtomicBoolean(true);
        var handler = new BodyCommandHandler<String>((context, command) -> allowed.get()
                ? Optional.of(context) : Optional.empty(), (context, command, current) -> true,
                BodyCommandHandlerTest::unusedTime, store, (context, receipt, before, after) -> { });
        var payload = request("one", 0, 5);
        assertTrue(MeridianProtocol.decodeResponse(handler.handle("player", payload)).hasCommandCommitted());
        allowed.set(false);
        assertFailure(handler.handle("player", payload), MeridianMessages.FailureCode.ACCESS_DENIED);
        assertEquals(1, store.find(body().id()).orElseThrow().revision());
    }

    @Test
    public void coreRejectionsRunBeforePrivatePolicyAndDoNotWrite() {
        // 锁定关键边界：非致死支出、死体、版本耗尽以及无法表达的小额支出。
        var message = BodyProtobuf.toMessage(body());
        var dead = BodyProtobuf.fromMessage(message.toBuilder()
                .setVitality(message.getVitality().toBuilder().setDead(true)).build());
        var exhausted = BodyProtobuf.fromMessage(message.toBuilder().setRevision(Long.MAX_VALUE).build());
        var bodies = List.of(body(), body(), dead, exhausted, body());
        var amounts = List.of(985.0, 986.0, 5.0, 5.0, Double.MIN_VALUE);
        var failures = List.of(MeridianMessages.FailureCode.INSUFFICIENT_LIFESPAN,
                MeridianMessages.FailureCode.INSUFFICIENT_LIFESPAN, MeridianMessages.FailureCode.BODY_DEAD,
                MeridianMessages.FailureCode.REVISION_EXHAUSTED, MeridianMessages.FailureCode.NUMERIC_LIMIT);
        for (int i = 0; i < bodies.size(); i++) {
            var initial = bodies.get(i);
            var store = store(initial);
            var handler = handler(store, (context, command, current) -> {
                throw new AssertionError("核心拒绝不能进入私有策略");
            }, (context, receipt, before, after) -> { });
            assertFailure(handler.handle("player", request("one", initial.revision(), amounts.get(i))), failures.get(i));
            assertEquals(initial, store.find(initial.id()).orElseThrow());
        }
        assertFailure(handler(new InMemoryBodyStore(List.of(), List.of())).handle("player", request("one", 0, 5)),
                MeridianMessages.FailureCode.BODY_NOT_FOUND);
    }

    @Test
    public void storageFailureBeforePublishRollsBackBothStateAndReceipt() {
        // 在 stage 成功之后、真正发布之前注入故障，验证回调异常不会留下半次成功。
        var store = store(body());
        BodyTransactionStore failing = new BodyTransactionStore() {
            @Override
            public <T> T transact(BodyId bodyId, Function<Transaction, T> work) {
                // 故障位于宿主事务内部，内存适配必须一起丢弃暂存快照和回执。
                return store.transact(bodyId, tx -> {
                    work.apply(tx);
                    throw new IllegalStateException("commit-failed");
                });
            }
        };
        assertFailure(handler(failing).handle("player", request("one", 0, 5)), MeridianMessages.FailureCode.INTERNAL_ERROR);
        assertEquals(body(), store.find(body().id()).orElseThrow());
        assertTrue(store.transact(body().id(), tx -> tx.receipt("player", "one").isEmpty()));
        assertTrue(MeridianProtocol.decodeResponse(handler(store).handle("player", request("one", 0, 5))).hasCommandCommitted());
        assertEquals(1, store.find(body().id()).orElseThrow().revision());
    }

    @Test
    public void wrongBodyOrReceiptFromHostCannotBeAccepted() {
        // 宿主索引串号必须暴露为内部错误，不能写入另一具身体或返回别人的成功。
        var initial = body();
        var wrongBody = BodyProtobuf.fromMessage(BodyProtobuf.toMessage(initial).toBuilder().setId("wrong-body").build());
        var command = MeridianProtocol.decodeRequest(request("one", 0, 5)).getBodyCommand();
        var wrongReceipt = MeridianMessages.CommandReceipt.newBuilder().setCallerId("someone-else")
                .setRequestId("one").setCommand(command)
                .setCommitted(MeridianMessages.CommandCommitted.newBuilder().setBodyId(initial.id().value())
                        .setPreviousRevision(0).setRevision(1)).build();
        for (boolean corruptReceipt : List.of(false, true)) {
            BodyTransactionStore corrupted = new BodyTransactionStore() {
                @Override
                public <T> T transact(BodyId bodyId, Function<Transaction, T> work) {
                    // 替身模拟不符合接口约定的适配器，处理器仍必须检查目标关系。
                    return work.apply(new Transaction() {
                        @Override
                        public Optional<BodySnapshot> snapshot() {
                            // 模拟索引错误返回另一具完整、结构合法的身体。
                            return Optional.of(wrongBody);
                        }

                        @Override
                        public Optional<MeridianMessages.CommandReceipt> receipt(String callerId, String requestId) {
                            // 回执结构合法，但可信调用者不匹配查询键。
                            return corruptReceipt ? Optional.of(wrongReceipt) : Optional.empty();
                        }

                        @Override
                        public void stage(BodySnapshot next, MeridianMessages.CommandReceipt receipt) {
                            // 错误必须在暂存之前暴露，不允许补偿式回滚掩盖串号。
                            throw new AssertionError("错误目标不得进入暂存");
                        }
                    });
                }
            };
            assertFailure(handler(corrupted).handle("player", request("one", 0, 5)),
                    MeridianMessages.FailureCode.INTERNAL_ERROR);
        }
    }

    @Test
    public void ambiguousCommitAndAuditFailureCannotCauseDuplicateSpending() {
        // 模拟已提交却丢失确认；原请求重试通过回执恢复成功，不能依赖错误响应判断未执行。
        var store = store(body());
        BodyTransactionStore lostAcknowledgment = new BodyTransactionStore() {
            @Override
            public <T> T transact(BodyId bodyId, Function<Transaction, T> work) {
                // 提交完成后才抛异常，重试必须使用同一个可信身份与请求 ID。
                store.transact(bodyId, work);
                throw new IllegalStateException("connection-lost-after-commit");
            }
        };
        var payload = request("one", 0, 5);
        assertFailure(handler(lostAcknowledgment).handle("player", payload), MeridianMessages.FailureCode.INTERNAL_ERROR);
        assertTrue(MeridianProtocol.decodeResponse(handler(store).handle("player", payload)).hasCommandCommitted());
        assertEquals(1, store.find(body().id()).orElseThrow().revision());
        var auditCalls = new AtomicInteger();
        var auditFails = handler(store, (context, command, current) -> true, (context, receipt, before, after) -> {
            auditCalls.incrementAndGet();
            throw new IllegalStateException("audit-unavailable");
        });
        var second = request("two", 1, 5);
        var response = auditFails.handle("player", second);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        assertArrayEquals(response, auditFails.handle("player", second));
        assertEquals(1, auditCalls.get());
        assertEquals(15, store.find(body().id()).orElseThrow().vitality().spentLifespanSeconds(), 0);
    }

    private static BodySnapshot body() {
        // 复用非人混相构型，但使用可递增的初始版本；本轮不推进已有时间或在途货物。
        return BodyProtobuf.fromMessage(BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.AWAKENING))
                .toBuilder().setRevision(0).build());
    }

    private static InMemoryBodyStore store(BodySnapshot body) {
        // 显式初始快照与空回执，不用模板自动创建满血身体。
        return new InMemoryBodyStore(List.of(body), List.of());
    }

    private static BodyCommandHandler<String> handler(BodyTransactionStore store) {
        // 测试明确装配公共放行策略；生产宿主不能把这里的授权当作默认规则。
        return handler(store, (context, command, current) -> true, (context, receipt, before, after) -> { });
    }

    private static BodyCommandHandler<String> handler(BodyTransactionStore store, BodySecurityPolicy<String> security,
                                                      BodyAuditSink<String> audit) {
        // 上下文在测试中直接代表可信身份，网络认证仍由真实宿主实现。
        return new BodyCommandHandler<>((context, command) -> Optional.of(context), security,
                BodyCommandHandlerTest::unusedTime, store, audit);
    }

    private static MeridianMessages.TimeAdvancePlan unusedTime(String context, BodySnapshot current) {
        // 寿元支出不能隐式推进时间，也不能为了查询宿主时钟产生额外副作用。
        throw new AssertionError("寿元支出不应读取时间源");
    }

    private static byte[] request(String requestId, long revision, double seconds) {
        // 使用生产 Protobuf 边界，生成类型本身不代表已通过授权。
        return MeridianProtocol.encodeRequest(MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION).setRequestId(requestId)
                .setBodyCommand(MeridianMessages.BodyCommand.newBuilder().setBodyId("creature-1")
                        .setExpectedRevision(revision)
                        .setSpendLifespan(MeridianMessages.SpendLifespan.newBuilder().setSeconds(seconds))).build());
    }

    private static void assertFailure(byte[] payload, MeridianMessages.FailureCode code) {
        // 只接受固定公开错误，不能同时出现身体或成功回执。
        var response = MeridianProtocol.decodeResponse(payload);
        assertEquals(MeridianMessages.ResponseEnvelope.ResultCase.FAILURE, response.getResultCase());
        assertEquals(code, response.getFailure().getCode());
    }
}
