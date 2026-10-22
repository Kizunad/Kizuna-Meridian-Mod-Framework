package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.AnatomyState;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyStructure;
import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.QiTransit;
import dev.kizuna.meridian.core.QiTransport;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejected;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejectionReason;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejected;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyTransactionStore;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static dev.kizuna.meridian.core.BodyTimeAdvancement.Mode.ACTIVE;
import static dev.kizuna.meridian.core.BodyTimeAdvancement.Mode.LIFESPAN_ONLY;
import static org.junit.Assert.*;

public class QiTransportTest {
    @Test
    public void dispatchRestoreAndArrivalPreserveKindsAndNeverDuplicate() {
        // 正式请求先扣源，在途保存后重建宿主；重试与到达都不能再次扣源或重复入账。
        var message = BodyProtobuf.toMessage(body());
        // 未参与本次线路的第三条经脉保留未完成进度，防止账户重建误改整具身体的开拓状态。
        var partialOpening = BodyMessages.MeridianOpening.newBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.IN_PROGRESS).setProgress(0.375);
        var initial = BodyProtobuf.fromMessage(message.toBuilder().setMeridianStates(2,
                message.getMeridianStates(2).toBuilder().setOpening(partialOpening)).build());
        List<MeridianOpening> expectedOpenings = initial.meridianStates().stream().map(MeridianState::opening).toList();
        var clock = new AtomicLong(1000);
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, clock);
        var cargo = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 1, .01),
                new Qi(Qi.QiAttribute.of("addon:lava"), 1, 10)));
        var payload = send("send", 0, request("a.b", cargo, 2));
        var response = handler.handle("host", payload);
        committed(response);
        assertArrayEquals(response, handler.handle("host", payload));
        var moving = store.find(initial.id()).orElseThrow();
        assertEquals(expectedOpenings, moving.meridianStates().stream().map(MeridianState::opening).toList());
        assertEquals(18, moving.meridianStates().get(0).lumenQi().totalQi(), 0);
        assertEquals(initial.meridianStates().get(1), moving.meridianStates().get(1));
        assertEquals(cargo, moving.transits().get(0).cargo());
        assertEquals(3000, moving.transits().get(0).arrivalMillis());
        assertEquals(initial.totalQi(), moving.totalQi(), 0);
        var receipt = store.transact(initial.id(), tx -> tx.receipt("host", "send").orElseThrow());
        var restored = new InMemoryBodyStore(List.of(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(moving))),
                List.of(MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt))));
        var resumed = handler(restored, clock);
        assertArrayEquals(response, resumed.handle("host", payload));

        // 截止之前仍在途；到达时保持混相与属性，外壁没有参与发运。
        clock.set(2999);
        committed(resumed.handle("host", advance("before-arrival", 1)));
        assertEquals(cargo.totalQi(), restored.find(initial.id()).orElseThrow().transitQiTotal(), 0);
        clock.set(3000);
        var arrival = advance("arrival", 2);
        var arrivedResponse = resumed.handle("host", arrival);
        committed(arrivedResponse);
        assertArrayEquals(arrivedResponse, resumed.handle("host", arrival));
        var arrived = restored.find(initial.id()).orElseThrow();
        assertEquals(expectedOpenings, arrived.meridianStates().stream().map(MeridianState::opening).toList());
        assertTrue(arrived.transits().isEmpty());
        assertEquals(4, arrived.meridianStates().get(1).lumenQi().totalQi(), 0);
        assertEquals(2, arrived.meridianStates().get(1).lumenQi().entries().size());
        assertEquals(initial.wallQiTotal(), arrived.wallQiTotal(), 0);
        assertEquals(initial.totalQi(), arrived.totalQi(), 0);
        assertEquals(QiInventory.empty(), restored.environmentQi());
    }

    @Test
    public void throughputIsSharedAcrossConnectionsAndBothDirections() {
        // B 总吞吐为 4，A 为 6；换连接和反向发送都不能重复预约额度。
        var first = QiTransport.start(body(), request("a.b", free(1), 3));
        rejected(TransportRejectionReason.THROUGHPUT_EXCEEDED, first, request("c.b", free(1), 2));
        rejected(TransportRejectionReason.THROUGHPUT_EXCEEDED, first, request("b.a", free(1), 2));
        rejected(TransportRejectionReason.THROUGHPUT_EXCEEDED, first, request("a.c", free(1), 4));
        var shared = QiTransport.start(first, request("c.b", free(1), 1));
        assertEquals(2, shared.transits().size());
        var settled = BodyTimeAdvancement.apply(shared, plan(4000, ACTIVE)).body();
        assertTrue(settled.transits().isEmpty());
        assertEquals(1, QiTransport.start(settled, request("a.b", free(1), 4)).transits().size());
    }

    @Test
    public void reservationsPreventMultipleSendersOverfillingOneTarget() {
        // B 容量 12、原有 2；第一批预约 6 后，另一条线路只能再送 4。
        var initial = body();
        var first = QiTransport.start(initial, request("a.b", free(6), 1));
        rejected(TransportRejectionReason.CAPACITY_EXCEEDED, first, request("c.b", free(5), 1));
        var both = QiTransport.start(first, request("c.b", free(4), 1));
        assertEquals(10, both.transitQiTotal(), 0);
        var arrived = BodyTimeAdvancement.apply(both, plan(10_000, ACTIVE)).body();
        assertEquals(12, arrived.meridianStates().get(1).lumenQi().totalQi(), 0);
        assertTrue(arrived.transits().isEmpty());
        assertEquals(initial.totalQi(), both.totalQi(), 0);
        assertEquals(initial.totalQi(), arrived.totalQi(), 0);
    }

    @Test
    public void blockedRoutePreservesCargoAndRepairsCanSettleAtTheSameTime() {
        // 发运后模拟目标损伤；到达受阻不退款、不入目标、不丢弃货物。
        var moving = QiTransport.start(body(), request("a.b", free(2), 2));
        var damaged = condition(moving, 1, BodyMessages.MeridianCondition.SEVERED);
        var blocked = BodyTimeAdvancement.apply(damaged, plan(3000, ACTIVE)).body();
        assertEquals(QiTransit.Status.BLOCKED_ROUTE, blocked.transits().get(0).status());
        assertEquals(moving.totalQi(), blocked.totalQi(), 0);
        assertEquals(2, blocked.meridianStates().get(1).lumenQi().totalQi(), 0);
        assertTrue(QiTransport.nextArrivalBoundary(blocked).isEmpty());
        assertEquals(TimeRejectionReason.TIME_NOT_ADVANCED,
                assertThrows(TimeRejected.class, () -> BodyTimeAdvancement.apply(blocked, plan(3000, ACTIVE))).reason());

        // 阻塞状态可以保存；修复后无需等待下个 tick，同毫秒即可处理状态变化。
        var loaded = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(blocked));
        var repaired = condition(loaded, 1, BodyMessages.MeridianCondition.INTACT);
        var arrived = BodyTimeAdvancement.apply(repaired, plan(3000, ACTIVE)).body();
        assertTrue(arrived.transits().isEmpty());
        assertEquals(4, arrived.meridianStates().get(1).lumenQi().totalQi(), 0);
        assertEquals(blocked.vitality(), arrived.vitality());
        assertEquals(blocked.totalQi(), arrived.totalQi(), 0);
    }

    @Test
    public void capacityChangesBlockArrivalWithoutDeletingReservations() {
        // 模拟将来的改造/注入改变目标；预约不授予到达时强行超容量的许可。
        var moving = QiTransport.start(body(), request("a.b", free(2), 2));
        var full = lumen(moving, 1, free(12));
        var blocked = BodyTimeAdvancement.apply(full, plan(3000, ACTIVE)).body();
        assertEquals(QiTransit.Status.BLOCKED_CAPACITY, blocked.transits().get(0).status());
        assertEquals(full.totalQi(), blocked.totalQi(), 0);
        var room = lumen(blocked, 1, free(10));
        rejected(TransportRejectionReason.CAPACITY_EXCEEDED, room, request("c.b", free(1), 1));
        var arrived = BodyTimeAdvancement.apply(room, plan(3000, ACTIVE)).body();
        assertEquals(12, arrived.meridianStates().get(1).lumenQi().totalQi(), 0);
        assertEquals(room.totalQi(), arrived.totalQi(), 0);
    }

    @Test
    public void resistancePhasesFreezeAndDeathRespectEventBoundaries() {
        // 阻力使批次更慢；固态不额外增加阻力，所有相态使用同一公式。
        var initial = body();
        var normal = QiTransport.start(initial, request("a.b", free(2), 2));
        var solid = new QiInventory(List.of(new Qi(Qi.QiAttribute.of("addon:lava"), 2, 10)));
        assertEquals(normal.transits().get(0).arrivalMillis(),
                QiTransport.start(initial, request("a.b", solid, 2)).transits().get(0).arrivalMillis());
        var message = BodyProtobuf.toMessage(initial);
        var resistant = BodyProtobuf.fromMessage(message.toBuilder().setStructure(message.getStructure().toBuilder()
                .setConnections(0, message.getStructure().getConnections(0).toBuilder().setResistance(1))).build());
        assertEquals(4000, QiTransport.start(resistant, request("a.b", free(2), 2)).transits().get(0).arrivalMillis());
        var frozen = BodyTimeAdvancement.apply(normal, plan(6000, LIFESPAN_ONLY)).body();
        assertEquals(8000, frozen.transits().get(0).arrivalMillis());
        assertEquals(normal.transits().get(0).cargo(), frozen.transits().get(0).cargo());
        assertEquals(8000, BodyTimeAdvancement.nextEventBoundary(frozen, 1, ACTIVE).orElseThrow().elapsedMillis());
        assertTrue(BodyTimeAdvancement.nextEventBoundary(frozen, 0, LIFESPAN_ONLY).isEmpty());

        // 死亡位于到达之前、同刻和之后，都只能释放原有总量一次。
        for (double limit : new double[] {16, 17, 18}) {
            var sent = BodyProtobuf.toMessage(normal);
            var shortLived = BodyProtobuf.fromMessage(sent.toBuilder()
                    .setVitality(sent.getVitality().toBuilder().setLifespanLimitSeconds(limit)).build());
            var death = BodyTimeAdvancement.apply(shortLived, plan(10_000, ACTIVE));
            assertTrue(death.died());
            assertTrue(death.body().transits().isEmpty());
            assertEquals(0, death.body().totalQi(), 0);
            assertEquals(initial.totalQi(), death.releasedQi().totalQi(), 0);
        }
    }

    @Test
    public void routesBalancesAndNumericalLimitsRejectWithoutLosingQi() {
        // 破裂/断裂、属性余额不足和无法表示的扣款都必须在生成候选身体之前拒绝。
        var initial = body();
        rejected(TransportRejectionReason.ROUTE_NOT_FOUND, initial, request("missing", free(1), 1));
        var unsupported = BodyProtobuf.fromMessage(BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI))
                .toBuilder().setRevision(0).build());
        rejected(TransportRejectionReason.ROUTE_UNSUPPORTED, unsupported, request("channel.tissue", free(1), 1));
        for (var state : List.of(BodyMessages.MeridianCondition.LEAKING, BodyMessages.MeridianCondition.SEVERED)) {
            rejected(TransportRejectionReason.ROUTE_UNAVAILABLE, condition(initial, 0, state), request("a.b", free(1), 1));
        }
        rejected(TransportRejectionReason.INSUFFICIENT_QI, initial, request("a.c", free(11), 1));
        rejected(TransportRejectionReason.NUMERIC_LIMIT, initial, request("a.b", free(Double.MIN_VALUE), 1));
        var message = BodyProtobuf.toMessage(initial);
        var endOfClock = BodyProtobuf.fromMessage(message.toBuilder()
                .setSettledAt(message.getSettledAt().toBuilder().setElapsedMillis(Long.MAX_VALUE - 1)).build());
        rejected(TransportRejectionReason.NUMERIC_LIMIT, endOfClock, request("a.b", free(1), 1));

        // 目标大余额吞掉整笔微量入账时不能删掉在途货物，整次时间推进应拒绝。
        var hugeTarget = BodyProtobuf.fromMessage(message.toBuilder()
                .setStructure(message.getStructure().toBuilder().setMeridians(1,
                        message.getStructure().getMeridians(1).toBuilder().setLumenVolume(2e18)))
                .setMeridianStates(1, message.getMeridianStates(1).toBuilder().setLumenQi(BodyProtobuf.toInventory(free(1e16))))
                .build());
        var moving = QiTransport.start(hugeTarget, request("a.b", free(1), 1));
        assertEquals(TransportRejectionReason.NUMERIC_LIMIT,
                assertThrows(TransportRejected.class, () -> BodyTimeAdvancement.apply(moving, plan(4000, ACTIVE))).reason());
        assertEquals(1, moving.transitQiTotal(), 0);
    }

    @Test
    public void dispatchRequiresAuthorizationAndCurrentActiveHostTime() {
        // 不能从旧游标回填发运时间；冻结模式和未授权操作也不能产生扣源或在途。
        var initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var payload = send("send", 0, request("a.b", free(2), 2));
        failure(handler(store, new AtomicLong(2000)).handle("host", payload),
                MeridianMessages.FailureCode.TIME_REQUIRES_SETTLEMENT);
        var frozen = new BodyCommandHandler<String>((context, command) -> Optional.of(context),
                (context, command, current) -> true,
                (context, current) -> TimeProtobuf.toMessage(plan(1000, LIFESPAN_ONLY)),
                store, (context, receipt, before, after) -> { });
        failure(frozen.handle("host", payload), MeridianMessages.FailureCode.TRANSPORT_FROZEN);
        var denied = new BodyCommandHandler<String>((context, command) -> Optional.empty(),
                (context, command, current) -> true, (context, current) -> {
                    throw new AssertionError("未授权发运不得查询时钟");
                }, store, (context, receipt, before, after) -> { });
        failure(denied.handle("host", payload), MeridianMessages.FailureCode.ACCESS_DENIED);
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertTrue(store.transact(initial.id(), tx -> tx.receipt("host", "send").isEmpty()));
    }

    @Test
    public void rollbackAndLostAcknowledgmentCannotSplitOrDuplicateDispatch() {
        // 发布前故障回滚扣源与在途，确认丢失后的重试复用原回执。
        for (boolean afterPublish : List.of(false, true)) {
            var initial = body();
            var store = new InMemoryBodyStore(List.of(initial), List.of());
            BodyTransactionStore failing = new BodyTransactionStore() {
                @Override
                public <T> T transact(BodyId id, Function<Transaction, T> work) {
                    // 故障注入包裹生产事务，不以手写余额替代实际提交逻辑。
                    if (afterPublish) {
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
            var clock = new AtomicLong(1000);
            var payload = send("send", 0, request("a.b", free(2), 2));
            failure(handler(failing, clock).handle("host", payload), MeridianMessages.FailureCode.INTERNAL_ERROR);
            var saved = store.find(initial.id()).orElseThrow();
            assertEquals(afterPublish ? 2 : 0, saved.transitQiTotal(), 0);
            assertEquals(initial.totalQi(), saved.totalQi(), 0);
            committed(handler(store, clock).handle("host", payload));
            assertEquals(1, store.find(initial.id()).orElseThrow().transits().size());
            assertEquals(18, store.find(initial.id()).orElseThrow().meridianStates().get(0).lumenQi().totalQi(), 0);
        }
    }

    @Test
    public void wireContractRejectsInvalidCargoAndImpossibleTransitStates() {
        // 固定独立字节锁定 transport_qi 的字段号、货物结构和流量单位。
        var cargo = new QiInventory(List.of(new Qi(Qi.QiAttribute.of("x:q"), 1, .01)));
        var envelope = MeridianProtocol.decodeRequest(send("q1", 0, request("a.b", cargo, 1))).toBuilder();
        envelope.setBodyCommand(envelope.getBodyCommand().toBuilder().setBodyId("body-1"));
        var fixture = HexFormat.of().parseHex(
                "0801120271315a350a06626f64792d31100062290a03612e6212190a171203783a7119000000000000f03f217b14ae47e17a843f19000000000000f03f");
        assertArrayEquals(fixture, MeridianProtocol.encodeRequest(envelope.build()));
        assertEquals(envelope.build(), MeridianProtocol.decodeRequest(fixture));
        var command = envelope.getBodyCommand();
        var transport = command.getTransportQi();
        for (var invalid : List.of(transport.toBuilder().setThroughputPerSecond(0).build(),
                transport.toBuilder().setThroughputPerSecond(Double.NaN).build(),
                transport.toBuilder().setConnectionId("bad/route").build(),
                transport.toBuilder().setCargo(BodyProtobuf.toInventory(QiInventory.empty())).build(),
                transport.toBuilder().setCargo(transport.getCargo().toBuilder().addEntries(transport.getCargo().getEntries(0))).build())) {
            var bad = envelope.clone().setBodyCommand(command.toBuilder().setTransportQi(invalid)).build();
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeRequest(bad));
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(bad.toByteArray()));
        }
        var moving = BodyProtobuf.toMessage(QiTransport.start(body(), request("a.b", free(1), 1)));
        var transit = moving.getTransits(0);
        for (var invalid : List.of(transit.toBuilder().setThroughputPerSecond(0).build(),
                transit.toBuilder().setStatusValue(99).build(), transit.toBuilder().clearStatus().build(),
                transit.toBuilder().setStatus(BodyMessages.TransitStatus.BLOCKED_ROUTE).build())) {
            // 阻塞只能发生在到达之后，生成消息不能绕过快照的时序约束。
            var bad = moving.toBuilder().setTransits(0, invalid).build();
            assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.decodeSnapshot(bad.toByteArray()));
        }
    }

    private static BodySnapshot body() {
        // 三条非人经脉共享目标并允许双向连接，构型不依赖人体数量或左右命名。
        var part = new Anatomy.PartId("shell");
        var definitions = List.of(meridian("a", part, 40, 6), meridian("b", part, 12, 4), meridian("c", part, 40, 6));
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(part, "外壳", 100)),
                List.of(), List.of(), definitions,
                List.of(connection("a", "b"), connection("c", "b"), connection("b", "a"), connection("a", "c")));
        var mixed = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 10, .01),
                new Qi(Qi.QiAttribute.of("addon:lava"), 10, 10)));
        return new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("transport-body"),
                new BodyTemplate.Reference(new DefinitionId("sample:transport"), 1), structure, Realm.WANDERING_QI,
                List.of(new AnatomyState.Part(part, 100)), List.of(), List.of(),
                List.of(state("a", mixed), state("b", free(2)), state("c", mixed)), List.of(),
                new VitalityState(10, 1000, 5, false), plan(1000, ACTIVE).target(), 0);
    }

    private static Anatomy.MeridianDefinition meridian(String id, Anatomy.PartId part, double capacity, double rate) {
        // 参数按游离态下的等效元数配置；实际结构只保存液态参照体积。
        return new Anatomy.MeridianDefinition(new Anatomy.MeridianId(id), id, List.of(part), capacity * 100, 2_000,
                rate, 10, 20,
                List.of(new Anatomy.Port(new Anatomy.PortId(id + ".port"), Anatomy.PortDirection.BIDIRECTIONAL)));
    }

    private static Anatomy.Connection connection(String from, String to) {
        // 固定一米无阻力，基础批次服务时长可与传播时长分别核对。
        return new Anatomy.Connection(new Anatomy.ConnectionId(from + "." + to),
                new Anatomy.PortId(from + ".port"), new Anatomy.PortId(to + ".port"), 0, 1);
    }

    private static MeridianState state(String id, QiInventory lumen) {
        // 外壁余额显式非零，用于证明内腔不足时不能借用储备。
        return new MeridianState(new Anatomy.MeridianId(id), lumen, free(9), 0, MeridianState.Condition.INTACT,
                new MeridianOpening(MeridianOpening.Stage.OPENED, 1));
    }

    private static QiInventory free(double amount) {
        // 正数纯无属性货物便于检查容量与吞吐；零余额使用空账户。
        return amount == 0 ? QiInventory.empty() : new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, amount, .01)));
    }

    private static QiTransport.Request request(String route, QiInventory cargo, double rate) {
        // 核心请求与 Protobuf 请求共享相同的正数和类型约束。
        return new QiTransport.Request(new Anatomy.ConnectionId(route), cargo, rate);
    }

    private static BodyTimeAdvancement.Plan plan(long time, BodyTimeAdvancement.Mode mode) {
        // 固定倍率隔离运输变量，时间始终来自测试宿主而非运输请求。
        return new BodyTimeAdvancement.Plan(new TimeCursor(new DefinitionId("sample:clock"), "epoch-1", time), 1, mode);
    }

    private static BodySnapshot condition(BodySnapshot body, int index, BodyMessages.MeridianCondition condition) {
        // 仅测试中构造损伤后的权威快照，不表示客户端拥有直接改身体的 API。
        var message = BodyProtobuf.toMessage(body);
        return BodyProtobuf.fromMessage(message.toBuilder().setMeridianStates(index,
                message.getMeridianStates(index).toBuilder().setCondition(condition)).build());
    }

    private static BodySnapshot lumen(BodySnapshot body, int index, QiInventory inventory) {
        // 模拟其他已授权操作改变接收条件，以验证到达时必须重新检查容量。
        var message = BodyProtobuf.toMessage(body);
        return BodyProtobuf.fromMessage(message.toBuilder().setMeridianStates(index,
                message.getMeridianStates(index).toBuilder().setLumenQi(BodyProtobuf.toInventory(inventory))).build());
    }

    private static BodyCommandHandler<String> handler(BodyTransactionStore store, AtomicLong clock) {
        // 时钟独立于请求载荷；真实宿主还需检查身体归属、路线、剂量及流量许可。
        return new BodyCommandHandler<>((context, command) -> Optional.of(context),
                (context, command, current) -> true,
                (context, current) -> TimeProtobuf.toMessage(plan(clock.get(), ACTIVE)),
                store, (context, receipt, before, after) -> { });
    }

    private static byte[] send(String id, long revision, QiTransport.Request request) {
        // 使用正式请求边界，确保测试同时覆盖协议校验及事务入口。
        return MeridianProtocol.encodeRequest(MeridianMessages.RequestEnvelope.newBuilder().setProtocolVersion(1)
                .setRequestId(id).setBodyCommand(MeridianMessages.BodyCommand.newBuilder().setBodyId("transport-body")
                        .setExpectedRevision(revision).setTransportQi(MeridianMessages.TransportQi.newBuilder()
                                .setConnectionId(request.connectionId().value()).setCargo(BodyProtobuf.toInventory(request.cargo()))
                                .setThroughputPerSecond(request.throughputPerSecond()))).build());
    }

    private static byte[] advance(String id, long revision) {
        // 到达只能通过可信时间推进，调用者不能上传“已经抵达”的事实。
        return MeridianProtocol.encodeRequest(MeridianMessages.RequestEnvelope.newBuilder().setProtocolVersion(1)
                .setRequestId(id).setBodyCommand(MeridianMessages.BodyCommand.newBuilder().setBodyId("transport-body")
                        .setExpectedRevision(revision).setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance())).build());
    }

    private static void rejected(TransportRejectionReason reason, BodySnapshot body, QiTransport.Request request) {
        // 业务拒绝应保持结构化原因，不能由构造器错误偶然挡住非法转账。
        assertEquals(reason, assertThrows(TransportRejected.class, () -> QiTransport.start(body, request)).reason());
    }

    private static void committed(byte[] response) {
        // 成功必须经过完整线协议解码，不把未提交的候选身体当成成功。
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
    }

    private static void failure(byte[] response, MeridianMessages.FailureCode expected) {
        // 不依赖内部异常文案，公开错误类别应足以定位调整方向。
        assertEquals(expected, MeridianProtocol.decodeResponse(response).getFailure().getCode());
    }
}
