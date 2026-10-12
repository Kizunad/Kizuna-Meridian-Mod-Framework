package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejected;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejectionReason;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyTimeSource;
import dev.kizuna.meridian.server.BodyTransactionStore;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static dev.kizuna.meridian.core.BodyTimeAdvancement.Mode.ACTIVE;
import static dev.kizuna.meridian.core.BodyTimeAdvancement.Mode.LIFESPAN_ONLY;
import static org.junit.Assert.*;

public class BodyTimeAdvancementTest {
    @Test
    public void trustedTimeAdvancesAgeOnceAndReplayDoesNotReadTheClockAgain() {
        // 经过实际请求入口结算；查询/重试不能再次读取变化中的宿主时钟。
        var initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var calls = new AtomicInteger();
        var handler = handler(store, (context, current) -> {
            // 每次调用返回更晚时间，以便暴露重试重新计算的错误。
            return TimeProtobuf.toMessage(plan(1000 + 1000L * calls.incrementAndGet(), 2, ACTIVE));
        });
        var request = request("tick", 0);
        var response = handler.handle("host", request);
        assertCommitted(response);
        assertArrayEquals(response, handler.handle("host", request));
        assertEquals(1, calls.get());
        var actual = store.find(initial.id()).orElseThrow();
        var expected = BodyProtobuf.toMessage(initial).toBuilder().setRevision(1)
                .setSettledAt(BodyProtobuf.toTime(plan(2000, 2, ACTIVE).target()))
                .setVitality(BodyProtobuf.toMessage(initial).getVitality().toBuilder().setAgeSeconds(12)).build();
        assertEquals(BodyProtobuf.fromMessage(expected), actual);

        // 即使换请求 ID，同一时段也不能再次结算；旧 revision 同样不得触碰时间源。
        var fixed = handler(store, (context, current) -> TimeProtobuf.toMessage(plan(2000, 2, ACTIVE)));
        assertFailure(fixed.handle("host", request("same-time", 1)), MeridianMessages.FailureCode.TIME_NOT_ADVANCED);
        assertFailure(handler.handle("host", request("stale", 0)), MeridianMessages.FailureCode.REVISION_CONFLICT);
        assertEquals(1, calls.get());
        assertEquals(actual, store.find(initial.id()).orElseThrow());
    }

    @Test
    public void lifespanOnlyFreezesTransitWhileActiveModeStopsAtUnimplementedEvents() {
        // 相同目标在两种模式下含义不同：冻结保持运输剩余时间，正常推进不能跳过到货。
        var initial = body();
        var frozen = BodyTimeAdvancement.apply(initial, plan(11_000, 1, LIFESPAN_ONLY)).body();
        assertEquals(20, frozen.vitality().ageSeconds(), 0);
        assertEquals(initial.transits().get(0).arrivalMillis() - initial.settledAt().elapsedMillis(),
                frozen.transits().get(0).arrivalMillis() - frozen.settledAt().elapsedMillis());
        assertEquals(initial.meridianStates(), frozen.meridianStates());
        assertEquals(initial.totalQi(), frozen.totalQi(), 0);
        for (long target : new long[] {5000, 11_000}) {
            assertRejected(TimeRejectionReason.PENDING_TIME_EVENT, initial, plan(target, 1, ACTIVE));
        }
        var beforeArrival = BodyTimeAdvancement.apply(frozen, plan(12_000, 1, ACTIVE)).body();
        assertEquals(frozen.transits(), beforeArrival.transits());
    }

    @Test
    public void deathStopsAgeAtBoundaryAndAtomicallyReleasesAllThreeAccounts() {
        // 短寿命让死亡早于运输；外壁、内腔、在途的混合相态和属性必须完整入环境账。
        var initial = withLifespan(12, 1);
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, (context, current) -> TimeProtobuf.toMessage(plan(20_000, 1, ACTIVE)));
        var payload = request("death", 0);
        var response = handler.handle("host", payload);
        assertCommitted(response);
        var dead = store.find(initial.id()).orElseThrow();
        assertTrue(dead.vitality().dead());
        assertEquals(11, dead.vitality().ageSeconds(), 0);
        assertEquals(1, dead.vitality().spentLifespanSeconds(), 0);
        assertEquals(0, dead.vitality().remainingSeconds(), 0);
        assertEquals(2000, dead.settledAt().elapsedMillis());
        assertEquals(0, dead.totalQi(), 0);
        // 死亡释放余额，已保存的经脉开拓事实继续保留在身体快照中。
        assertEquals(initial.meridianStates().get(0).opening(), dead.meridianStates().get(0).opening());
        assertTrue(dead.transits().isEmpty());
        assertEquals(initial.totalQi(), store.environmentQi().totalQi(), 0);
        assertEquals(6, store.environmentQi().entries().size());
        for (var entry : store.environmentQi().entries()) {
            assertEquals("addon:lava", entry.attribute().id().value());
            assertEquals(1.5, entry.amount(), 0);
        }

        // 保存/恢复后重试仍复用原结果，环境账按保存值恢复，绝不重放历史释放。
        var receipt = store.transact(initial.id(), tx -> tx.receipt("host", "death").orElseThrow());
        var restoredReceipt = MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt));
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryBodyStore(List.of(dead), List.of(restoredReceipt)));
        var restored = new InMemoryBodyStore(List.of(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(dead))),
                List.of(restoredReceipt), store.environmentQi());
        var resumed = handler(restored, (context, current) -> {
            throw new AssertionError("成功重试不应再次读取时间");
        });
        assertArrayEquals(response, resumed.handle("host", payload));
        assertEquals(store.environmentQi(), restored.environmentQi());
        assertFailure(handler.handle("host", request("dead-again", 1)), MeridianMessages.FailureCode.BODY_DEAD);
        assertEquals(1, store.find(initial.id()).orElseThrow().revision());
    }

    @Test
    public void earlierTransitBlocksDeathButDeathWinsAtTheSameMillisecond() {
        // 更早到货必须先处理；同一毫秒寿命耗尽优先，避免先入账又再次释放。
        var laterDeath = withLifespan(20, 1);
        assertRejected(TimeRejectionReason.PENDING_TIME_EVENT, laterDeath, plan(20_000, 1, ACTIVE));
        var sameTimeDeath = withLifespan(15, 1);
        var result = BodyTimeAdvancement.apply(sameTimeDeath, plan(20_000, 1, ACTIVE));
        assertTrue(result.died());
        assertEquals(5000, result.body().settledAt().elapsedMillis());
        assertEquals(sameTimeDeath.totalQi(), result.releasedQi().totalQi(), 0);
    }

    @Test
    public void pauseClockIdentityAndIntegerTimeBoundsCannotSilentlyLoseAge() {
        // 零倍率可推进模拟时钟；暂停单机可不发请求，相同游标会明确拒绝无变化提交。
        var initial = body();
        var paused = BodyTimeAdvancement.apply(initial, plan(2000, 0, ACTIVE)).body();
        assertEquals(initial.vitality(), paused.vitality());
        assertTrue(BodyTimeAdvancement.nextLifespanBoundary(initial, 0).isEmpty());
        for (long target : new long[] {999, 1000}) {
            assertRejected(TimeRejectionReason.TIME_NOT_ADVANCED, initial, plan(target, 1, ACTIVE));
        }
        for (var cursor : List.of(new TimeCursor(new DefinitionId("other:world"), "epoch-1", 2000),
                new TimeCursor(initial.settledAt().domain(), "epoch-2", 2000))) {
            assertRejected(TimeRejectionReason.CLOCK_MISMATCH, initial,
                    new BodyTimeAdvancement.Plan(cursor, 1, ACTIVE));
        }

        // 两个 long 先相减，靠近 Long.MAX_VALUE 时的一毫秒也不会被 double 吞掉。
        var message = BodyProtobuf.toMessage(initial);
        var largeClock = BodyProtobuf.fromMessage(message.toBuilder().clearTransits()
                .setSettledAt(message.getSettledAt().toBuilder().setElapsedMillis(Long.MAX_VALUE - 1)).build());
        var largeResult = BodyTimeAdvancement.apply(largeClock, plan(Long.MAX_VALUE, 1000, ACTIVE));
        assertEquals(11, largeResult.body().vitality().ageSeconds(), 0);
        assertTrue(BodyTimeAdvancement.nextLifespanBoundary(largeClock, 1).isEmpty());
        assertRejected(TimeRejectionReason.NUMERIC_LIMIT, initial, plan(2000, Double.MIN_VALUE, ACTIVE));

        // 冻结平移超出 int64 范围必须整体拒绝，不能把到达时间截断或清掉在途货物。
        var farTransit = BodyProtobuf.fromMessage(message.toBuilder().setTransits(0,
                message.getTransits(0).toBuilder().setArrivalMillis(Long.MAX_VALUE)).build());
        assertRejected(TimeRejectionReason.NUMERIC_LIMIT, farTransit, plan(2000, 0, LIFESPAN_ONLY));
    }

    @Test
    public void lifespanBoundaryRoundsUpAndAlreadyExhaustedBodiesDieWithoutExtraAge() {
        // 阈值不是整毫秒时向上取整，边界之前仍存活；调度查询本身不更改身体。
        var initial = withLifespan(12, 1);
        assertEquals(1334, BodyTimeAdvancement.nextLifespanBoundary(initial, 3).orElseThrow().elapsedMillis());
        assertFalse(BodyTimeAdvancement.apply(initial, plan(1333, 3, ACTIVE)).died());
        assertTrue(BodyTimeAdvancement.apply(initial, plan(1334, 3, ACTIVE)).died());
        var exhausted = withLifespan(11, 1);
        var death = BodyTimeAdvancement.apply(exhausted, plan(1000, 0, ACTIVE));
        assertTrue(death.died());
        assertEquals(10, death.body().vitality().ageSeconds(), 0);
        assertTrue(BodyTimeAdvancement.nextLifespanBoundary(death.body(), 1).isEmpty());
    }

    @Test
    public void rollbackAndLostAcknowledgmentCannotDropOrDuplicateDeathRelease() {
        // 一个故障发生于发布前，另一个发生于确认返回前；分别验证整体回滚与成功重试。
        for (boolean committedBeforeFailure : List.of(false, true)) {
            var initial = withLifespan(12, 1);
            var store = new InMemoryBodyStore(List.of(initial), List.of());
            BodyTransactionStore failing = new BodyTransactionStore() {
                @Override
                public <T> T transact(BodyId id, Function<Transaction, T> work) {
                    // 故障注入只包裹生产事务，不自己模拟资源余额变化。
                    if (committedBeforeFailure) {
                        store.transact(id, work);
                    } else {
                        store.transact(id, tx -> {
                            work.apply(tx);
                            throw new IllegalStateException("before-publish");
                        });
                    }
                    throw new IllegalStateException("after-publish");
                }
            };
            BodyTimeSource<String> clock = (context, current) -> TimeProtobuf.toMessage(plan(20_000, 1, ACTIVE));
            var payload = request("death", 0);
            assertFailure(handler(failing, clock).handle("host", payload), MeridianMessages.FailureCode.INTERNAL_ERROR);
            assertEquals(committedBeforeFailure, store.find(initial.id()).orElseThrow().vitality().dead());
            assertEquals(committedBeforeFailure ? initial.totalQi() : 0, store.environmentQi().totalQi(), 0);
            assertEquals(committedBeforeFailure,
                    store.transact(initial.id(), tx -> tx.receipt("host", "death").isPresent()));
            assertCommitted(handler(store, clock).handle("host", payload));
            assertEquals(initial.totalQi(), store.environmentQi().totalQi(), 0);
        }
    }

    @Test
    public void deniedTimeCannotReadClockOrReleaseResources() {
        // 时间意图也必须独立授权，具备其他操作权限不能隐式获得结算能力。
        var initial = withLifespan(12, 1);
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var denied = new BodyCommandHandler<String>((context, command) -> Optional.empty(),
                (context, command, current) -> true, (context, current) -> {
                    throw new AssertionError("未授权请求不应读取时间");
                }, store, (context, receipt, before, after) -> { });
        assertFailure(denied.handle("host", request("denied", 0)), MeridianMessages.FailureCode.ACCESS_DENIED);
        var privateDenied = new BodyCommandHandler<String>((context, command) -> Optional.of(context),
                (context, command, current) -> false,
                (context, current) -> TimeProtobuf.toMessage(plan(20_000, 1, ACTIVE)),
                store, (context, receipt, before, after) -> { });
        assertFailure(privateDenied.handle("host", request("private-denied", 0)), MeridianMessages.FailureCode.ACCESS_DENIED);
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertEquals(QiInventory.empty(), store.environmentQi());
    }

    @Test
    public void protobufSeparatesCallerIntentFromTrustedPlanAndValidatesDeathReceipts() {
        // 固定空操作字节；任何客户端添加的时间字段都应因未知字段被拒绝。
        var fixture = HexFormat.of().parseHex("0801120271315a0c0a06626f64792d3110005a00");
        var request = MeridianMessages.RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("q1")
                .setBodyCommand(MeridianMessages.BodyCommand.newBuilder().setBodyId("body-1").setExpectedRevision(0)
                        .setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance())).build();
        assertArrayEquals(fixture, MeridianProtocol.encodeRequest(request));
        assertEquals(request, MeridianProtocol.decodeRequest(fixture));
        var forged = HexFormat.of().parseHex("0801120271315a0e0a06626f64792d3110005a020864");
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(forged));
        var valid = TimeProtobuf.toMessage(plan(2000, 1, ACTIVE));
        assertEquals(valid, TimeProtobuf.decodePlan(TimeProtobuf.encodePlan(valid)));
        for (var invalid : List.of(valid.toBuilder().clearTarget().build(),
                valid.toBuilder().clearBiologicalSecondsPerSimulationSecond().build(),
                valid.toBuilder().setBiologicalSecondsPerSimulationSecond(Double.NaN).build(),
                valid.toBuilder().setBiologicalSecondsPerSimulationSecond(-1).build(),
                valid.toBuilder().setModeValue(99).build())) {
            assertThrows(IllegalArgumentException.class, () -> TimeProtobuf.encodePlan(invalid));
            assertThrows(IllegalArgumentException.class, () -> TimeProtobuf.decodePlan(invalid.toByteArray()));
        }

        var initial = withLifespan(12, 1);
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        assertCommitted(handler(store, (context, current) -> valid).handle("host", request("death", 0)));
        var receipt = store.transact(initial.id(), tx -> tx.receipt("host", "death").orElseThrow());
        for (var invalid : List.of(receipt.toBuilder().clearTimeAdvance().build(),
                receipt.toBuilder().setTimeAdvance(receipt.getTimeAdvance().toBuilder().setDied(false)
                        .setPlan(TimeProtobuf.toMessage(plan(3000, 1, ACTIVE)))).build(),
                receipt.toBuilder().setCommand(receipt.getCommand().toBuilder().setSpendLifespan(
                        MeridianMessages.SpendLifespan.newBuilder().setSeconds(1))).build())) {
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeReceipt(invalid));
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeReceipt(invalid.toByteArray()));
        }
    }

    @Test
    public void transactionRejectsForgedReleaseWithoutChangingEnvironment() {
        // 直接误用进程内事务也不能提交一份与实际清账不符的释放记录。
        var initial = withLifespan(12, 1);
        var result = BodyTimeAdvancement.apply(initial, plan(2000, 1, ACTIVE));
        var validTime = TimeProtobuf.toMessage(TimeProtobuf.toMessage(plan(2000, 1, ACTIVE)), result);
        var receipt = MeridianMessages.CommandReceipt.newBuilder().setCallerId("host").setRequestId("death")
                .setCommand(MeridianProtocol.decodeRequest(request("death", 0)).getBodyCommand())
                .setCommitted(MeridianMessages.CommandCommitted.newBuilder().setBodyId(initial.id().value())
                        .setPreviousRevision(0).setRevision(1))
                .setTimeAdvance(validTime.toBuilder().setReleasedQi(BodyProtobuf.toInventory(QiInventory.empty()))).build();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        assertThrows(IllegalStateException.class, () -> store.transact(initial.id(), tx -> {
            tx.stage(result.body(), receipt);
            return null;
        }));
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertEquals(QiInventory.empty(), store.environmentQi());
    }

    private static BodySnapshot body() {
        // 复用混相非人构型；组织端口仍未实现，用它保护不能跳过未知事件的规则。
        var message = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.AWAKENING));
        return BodyProtobuf.fromMessage(message.toBuilder().setRevision(0)
                .setSettledAt(message.getSettledAt().toBuilder().setElapsedMillis(1000))
                .setTransits(0, message.getTransits(0).toBuilder().setArrivalMillis(5000)
                        .setConnectionId("channel.tissue")).build());
    }

    private static BodySnapshot withLifespan(double limit, double spent) {
        // 调整生命状态即可复用同一身体，测试不依赖人类部位或固定经脉数量。
        var message = BodyProtobuf.toMessage(body());
        return BodyProtobuf.fromMessage(message.toBuilder().setVitality(message.getVitality().toBuilder()
                .setLifespanLimitSeconds(limit).setSpentLifespanSeconds(spent)).build());
    }

    private static BodyTimeAdvancement.Plan plan(long millis, double rate, BodyTimeAdvancement.Mode mode) {
        // 所有正常样例沿用同一时钟域与世代，错域测试显式构造不同值。
        return new BodyTimeAdvancement.Plan(new TimeCursor(new DefinitionId("sample:world"), "epoch-1", millis),
                rate, mode);
    }

    private static BodyCommandHandler<String> handler(BodyTransactionStore store, BodyTimeSource<String> clock) {
        // 模拟已认证宿主；生产接入需要自己的操作授权和私有策略。
        return new BodyCommandHandler<>((context, command) -> Optional.of(context),
                (context, command, current) -> true, clock, store, (context, receipt, before, after) -> { });
    }

    private static byte[] request(String id, long revision) {
        // 玩家或宿主调用者只声明意图，载荷没有可伪造的时间或老化倍率。
        return MeridianProtocol.encodeRequest(MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1).setRequestId(id).setBodyCommand(MeridianMessages.BodyCommand.newBuilder()
                        .setBodyId("creature-1").setExpectedRevision(revision)
                        .setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance())).build());
    }

    private static void assertRejected(TimeRejectionReason reason, BodySnapshot body, BodyTimeAdvancement.Plan plan) {
        // 失败必须有稳定原因，不能由构造器异常掩盖缺失的运行校验。
        assertEquals(reason, assertThrows(TimeRejected.class, () -> BodyTimeAdvancement.apply(body, plan)).reason());
    }

    private static void assertCommitted(byte[] payload) {
        // 成功必须通过正式协议解码，防止只检查内部候选结果而遗漏提交。
        assertTrue(MeridianProtocol.decodeResponse(payload).hasCommandCommitted());
    }

    private static void assertFailure(byte[] payload, MeridianMessages.FailureCode code) {
        // 只比较公开错误类别，不依赖内部异常文案。
        assertEquals(code, MeridianProtocol.decodeResponse(payload).getFailure().getCode());
    }
}
