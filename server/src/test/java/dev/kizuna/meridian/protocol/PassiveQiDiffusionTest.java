package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/** 自然扩散整体回归：公开构型、事件推进、调用权限与唯一资源归属。 */
public class PassiveQiDiffusionTest {
    private static final double PRECISION_DENSITY = 0.1;
    private static final double PRECISION_SOURCE_VOLUME = 20;
    private static final double PRECISION_TARGET_VOLUME = 3;
    private static final double PRECISION_FIRE_QI = 0.2608695652173913;
    private static final double PRECISION_MINIMUM_BATCH = 0.0001;
    private static final double QI_TOLERANCE = 1e-12;
    private static final long PRECISION_END_MILLIS = 2000;
    private static final long PRECISION_STEP_MILLIS = 37;
    private static final double ARRIVAL_EARTH_QI = 0.001259916539660459;
    private static final double ARRIVAL_EARTH_DENSITY = 0.011417225561026473;
    private static final double ARRIVAL_DUST_QI = 1.6686408809637269e-19;
    private static final double ARRIVAL_DUST_DENSITY = 0.012023824719557466;
    private static final double TARGET_EARTH_QI = 0.01278233693638232;
    private static final double TARGET_EARTH_DENSITY = 0.01125309958988419;
    private static final double TARGET_NONE_QI = 0.0024323750517362013;
    private static final double TARGET_NONE_DENSITY = 0.016935334172379305;
    private static final long DUST_END_MILLIS = 60100;

    @Test
    public void mixedRoundingCargoCommitsAndArrivesWithoutMovingTheSkippedAttribute() {
        // 20 与 3 的异构容量使取货产生不可扣除的木属性尾差，真实事务仍须提交正常火属性。
        BodySnapshot initial = precisionBody(PRECISION_MINIMUM_BATCH);
        BodySnapshot dispatched = advanceThroughStore(initial, 0);
        assertEquals(initial.revision() + 1, dispatched.revision());
        assertEquals(initial.settledAt(), dispatched.settledAt());
        List<QiTransit> transits = dispatched.transits();
        assertEquals(1, transits.size());
        assertEquals(List.of(new Qi(Qi.QiAttribute.FIRE, PRECISION_FIRE_QI, PRECISION_DENSITY)),
                transits.get(0).cargo().entries());
        assertEquals(1, dispatched.meridianStates().get(0).lumenQi().entries().get(1).amount(), 0);
        assertKindsConserved(initial, dispatched);

        // 存档中的在途继续跨越到达边界；到达前后各属性都只有一份所有权。
        BodySnapshot restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(dispatched));
        long arrivalMillis = restored.transits().get(0).arrivalMillis();
        BodySnapshot beforeArrival = advance(restored, arrivalMillis - 1, BodyTimeAdvancement.Mode.ACTIVE);
        assertTrue(beforeArrival.meridianStates().get(1).lumenQi().entries().isEmpty());
        assertKindsConserved(initial, beforeArrival);
        BodySnapshot arrived = advanceThroughStore(beforeArrival, arrivalMillis);
        assertEquals(beforeArrival.revision() + 1, arrived.revision());
        assertEquals(arrivalMillis, arrived.settledAt().elapsedMillis());
        assertTrue(arrived.transits().isEmpty());
        assertEquals(PRECISION_FIRE_QI, arrived.meridianStates().get(1).lumenQi().totalQi(), 0);
        assertKindsConserved(initial, arrived);
    }

    @Test
    public void mixedRoundingMatchesContinuousSegmentedAndSavedAdvancement() {
        // 每段都重新编解码，确保不可扣除货物过滤不会依赖未保存的临时状态。
        BodySnapshot initial = precisionBody(PRECISION_MINIMUM_BATCH);
        BodySnapshot continuous = advance(initial, PRECISION_END_MILLIS, BodyTimeAdvancement.Mode.ACTIVE);
        BodySnapshot segmented = initial;
        for (long millis = PRECISION_STEP_MILLIS; millis < PRECISION_END_MILLIS; millis += PRECISION_STEP_MILLIS) {
            BodySnapshot advanced = advance(segmented, millis, BodyTimeAdvancement.Mode.ACTIVE);
            segmented = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(advanced));
        }
        BodySnapshot completed = advance(segmented, PRECISION_END_MILLIS, BodyTimeAdvancement.Mode.ACTIVE);
        assertEquals(continuous.meridianStates(), completed.meridianStates());
        assertEquals(continuous.transits(), completed.transits());
        assertTrue(completed.transits().isEmpty());
        assertKindsConserved(initial, completed);
    }

    @Test
    public void minimumBatchAppliesAfterUndebitableEntriesAreFiltered() {
        // 未过滤的尾差恰好能跨过门槛，过滤后不足最小批次时应保持静止和完整源余额。
        BodySnapshot initial = precisionBody(Math.nextUp(PRECISION_FIRE_QI));
        assertTrue(PassiveQiDiffusion.nextBoundary(initial).isEmpty());
        BodySnapshot advanced = advance(initial, PRECISION_END_MILLIS, BodyTimeAdvancement.Mode.ACTIVE);
        assertEquals(initial.meridianStates(), advanced.meridianStates());
        assertTrue(advanced.transits().isEmpty());
        assertKindsConserved(initial, advanced);
    }

    @Test
    public void legacyDustSurvivesSavedAdvancementWithoutBlockingNormalArrivals() {
        // 到货复现的余额和密度保持原值；旧尘埃排在首项，确保正常货物仍能跨越真实到货边界。
        BodySnapshot initial = arrivalDustBody();
        BodySnapshot restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(initial));
        assertEquals(initial, restored);
        Qi dust = initial.meridianStates().get(0).lumenQi().entries().get(0);
        var request = new QiTransport.Request(new Anatomy.ConnectionId("a.b"), new QiInventory(List.of(dust)), 1);
        var rejected = assertThrows(MeridianErrors.TransportRejected.class, () -> QiTransport.start(initial, request));
        assertEquals(MeridianErrors.TransportRejectionReason.NUMERIC_LIMIT, rejected.reason());

        BodySnapshot dispatched = advanceThroughStore(restored, 0);
        List<QiTransit> transits = dispatched.transits();
        assertEquals(1, transits.size());
        QiTransit transit = transits.get(0);
        assertTrue(transit.cargo().entries().stream()
                .allMatch(entry -> entry.amount() >= QiAccounting.MIN_TRANSFER_QI));
        long arrivalMillis = transit.arrivalMillis();
        BodySnapshot arrived = advanceThroughStore(dispatched, arrivalMillis);
        assertTrue(arrived.meridianStates().get(1).lumenQi().entries().get(0).amount() > TARGET_EARTH_QI);
        assertDustRetained(initial, arrived);

        BodySnapshot continuous = advance(initial, DUST_END_MILLIS, BodyTimeAdvancement.Mode.ACTIVE);
        BodySnapshot segmented = initial;
        for (long millis : new long[] {arrivalMillis, PRECISION_END_MILLIS, DUST_END_MILLIS}) {
            BodySnapshot advanced = advance(segmented, millis, BodyTimeAdvancement.Mode.ACTIVE);
            segmented = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(advanced));
        }
        assertEquals(continuous.meridianStates(), segmented.meridianStates());
        assertEquals(continuous.transits(), segmented.transits());
        assertDustRetained(initial, segmented);
    }

    @Test
    public void unopenedCycleConservesKindsAndMatchesSegmentedSavedAdvancement() {
        // 异构容量、混相和有向环路同时参与，不能用等量分配代替按容量占比扩散。
        BodySnapshot initial = body();
        assertEquals(initial, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(initial)));
        assertEquals(0, BodyTimeAdvancement.nextEventBoundary(initial, 0, BodyTimeAdvancement.Mode.ACTIVE)
                .orElseThrow().elapsedMillis());
        var denied = assertThrows(MeridianErrors.TransportRejected.class, () -> QiTransport.start(initial,
                new QiTransport.Request(new Anatomy.ConnectionId("a.b"), free(.1), .5)));
        assertEquals(MeridianErrors.TransportRejectionReason.MERIDIAN_UNOPENED, denied.reason());

        BodySnapshot dispatched = advance(initial, 0, BodyTimeAdvancement.Mode.ACTIVE);
        assertFalse(dispatched.transits().isEmpty());
        BodySnapshot restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(dispatched));
        BodySnapshot batch = advance(restored, 120_000, BodyTimeAdvancement.Mode.ACTIVE);
        BodySnapshot segmented = restored;
        for (long millis = 137; millis < 120_000; millis += 137) {
            segmented = advance(segmented, millis, BodyTimeAdvancement.Mode.ACTIVE);
        }
        segmented = advance(segmented, 120_000, BodyTimeAdvancement.Mode.ACTIVE);
        assertEquals(batch.meridianStates(), segmented.meridianStates());
        assertEquals(batch.transits(), segmented.transits());
        assertTrue(batch.transits().isEmpty());
        assertTrue(BodyTimeAdvancement.nextEventBoundary(batch, 0, BodyTimeAdvancement.Mode.ACTIVE).isEmpty());
        assertEquals(initial.totalQi(), batch.totalQi(), 1e-12);
        assertEquals(initial.wallQiTotal(), batch.wallQiTotal(), 0);
        QiInventory originalKinds = QiInventory.combine(initial.meridianStates().stream().map(MeridianState::lumenQi).toList());
        QiInventory settledKinds = QiInventory.combine(batch.meridianStates().stream().map(MeridianState::lumenQi).toList());
        for (Qi original : originalKinds.entries()) {
            Qi settled = settledKinds.entries().stream().filter(qi -> qi.phase() == original.phase()
                    && qi.attribute().equals(original.attribute())).findFirst().orElseThrow();
            assertEquals(original.amount(), settled.amount(), 1e-12);
        }
        double initialLumenVolume = initial.meridianStates().stream().mapToDouble(state -> state.lumenQi().volume()).sum();
        double settledLumenVolume = 0;
        for (int index = 0; index < batch.meridianStates().size(); index++) {
            assertEquals(new MeridianOpening(MeridianOpening.Stage.UNOPENED, 0), batch.meridianStates().get(index).opening());
            double fraction = batch.meridianStates().get(index).lumenQi().volume()
                    / batch.structure().meridians().get(index).lumenVolume();
            assertTrue(fraction >= 0 && fraction <= 1);
            settledLumenVolume += batch.meridianStates().get(index).lumenQi().volume();
        }
        assertEquals(initialLumenVolume, settledLumenVolume, 1e-9);
        assertTrue(batch.meridianStates().get(1).lumenQi().totalQi() > 0);
    }

    @Test
    public void sharedThroughputDamageFreezeAndDeathKeepOneOwner() {
        // 主动批次占满两端吞吐后，被动只能使用其他空闲通路，不能另开一份预算。
        var original = BodyProtobuf.toMessage(body());
        var message = original.toBuilder().setStructure(original.getStructure().toBuilder().clearConnections()
                .addAllConnections(original.getStructure().getConnectionsList().stream()
                        .filter(connection -> connection.getId().equals("a.b") || connection.getId().equals("b.a"))
                        .toList())).build();
        var opened = message.getMeridianStates(0).toBuilder().setOpening(BodyMessages.MeridianOpening.newBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.OPENED).setProgress(1));
        BodySnapshot initial = BodyProtobuf.fromMessage(message.toBuilder().setMeridianStates(0, opened).build());
        BodySnapshot active = QiTransport.start(initial,
                new QiTransport.Request(new Anatomy.ConnectionId("a.b"), free(.25), 1));
        assertTrue(PassiveQiDiffusion.nextBoundary(active).isEmpty());
        BodySnapshot frozen = advance(active, 5000, BodyTimeAdvancement.Mode.LIFESPAN_ONLY);
        assertEquals(active.meridianStates(), frozen.meridianStates());
        assertEquals(active.transits().get(0).arrivalMillis() + 5000, frozen.transits().get(0).arrivalMillis());

        // 目标断裂后，货物留在在途；修复后同一时刻重试，恢复扩散并保持总量。
        var frozenMessage = BodyProtobuf.toMessage(frozen);
        BodySnapshot severed = BodyProtobuf.fromMessage(frozenMessage.toBuilder().setMeridianStates(1,
                frozenMessage.getMeridianStates(1).toBuilder().setCondition(BodyMessages.MeridianCondition.SEVERED)).build());
        BodySnapshot blocked = advance(severed, 6000, BodyTimeAdvancement.Mode.ACTIVE);
        assertEquals(QiTransit.Status.BLOCKED_ROUTE, blocked.transits().get(0).status());
        assertTrue(BodyTimeAdvancement.nextEventBoundary(blocked, 0, BodyTimeAdvancement.Mode.ACTIVE).isEmpty());
        var blockedMessage = BodyProtobuf.toMessage(blocked);
        BodySnapshot repaired = BodyProtobuf.fromMessage(blockedMessage.toBuilder().setMeridianStates(1,
                blockedMessage.getMeridianStates(1).toBuilder().setCondition(BodyMessages.MeridianCondition.INTACT)).build());
        BodySnapshot resumed = advance(repaired, 6000, BodyTimeAdvancement.Mode.ACTIVE);
        assertEquals(initial.totalQi(), resumed.totalQi(), 1e-12);
        assertTrue(resumed.transits().stream().allMatch(transit -> transit.status() == QiTransit.Status.MOVING));

        // 寿尽必须把扩散在途与实际局部库存一起清空且只释放一次。
        var resumedMessage = BodyProtobuf.toMessage(resumed);
        BodySnapshot shortLived = BodyProtobuf.fromMessage(resumedMessage.toBuilder().setVitality(
                resumedMessage.getVitality().toBuilder().setLifespanLimitSeconds(7)).build());
        var death = BodyTimeAdvancement.apply(shortLived, plan(20_000, BodyTimeAdvancement.Mode.ACTIVE, 1));
        assertTrue(death.died());
        assertEquals(0, death.body().totalQi(), 0);
        assertEquals(initial.totalQi(), death.releasedQi().totalQi(), 1e-12);
    }

    @Test
    public void wireRejectsInvalidPassiveRulesAndFullBalancedBodiesStayIdle() {
        // 公共配置的缺字段、非法范围和非经脉端点必须在执行前拒绝。
        var message = BodyProtobuf.toMessage(body());
        var structure = message.getStructure();
        var connection = structure.getConnections(0);
        for (var invalid : List.of(connection.getPassiveQiFlow().toBuilder().clearMaximumBatchQi().build(),
                connection.getPassiveQiFlow().toBuilder().setMinimumBatchQi(2).build(),
                connection.getPassiveQiFlow().toBuilder().setThroughputPerSecond(Double.NaN).build())) {
            var malformed = message.toBuilder().setStructure(structure.toBuilder().setConnections(0,
                    connection.toBuilder().setPassiveQiFlow(invalid))).build();
            assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.fromMessage(malformed));
            assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.decodeSnapshot(malformed.toByteArray()));
        }
        var selfRoute = message.toBuilder().setStructure(structure.toBuilder().setConnections(0,
                connection.toBuilder().setTo("a.port"))).build();
        assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.fromMessage(selfRoute));
        var organRoute = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.fromMessage(organRoute.toBuilder()
                .setStructure(organRoute.getStructure().toBuilder().setConnections(0,
                        organRoute.getStructure().getConnections(0).toBuilder().setPassiveQiFlow(connection.getPassiveQiFlow()))).build()));

        var full = message.toBuilder();
        for (int index = 0; index < full.getMeridianStatesCount(); index++) {
            full.setMeridianStates(index, full.getMeridianStates(index).toBuilder().setLumenQi(
                    BodyProtobuf.toInventory(free(structure.getMeridians(index).getLumenVolume() * .01))));
        }
        assertTrue(PassiveQiDiffusion.nextBoundary(BodyProtobuf.fromMessage(full.build())).isEmpty());
    }

    private static void assertDustRetained(BodySnapshot initial, BodySnapshot updated) {
        // 单独精确检查尘埃归属，不能用大于尘埃本身的总量容差掩盖丢失。
        List<MeridianState> states = updated.meridianStates();
        QiInventory source = states.get(0).lumenQi();
        assertEquals(initial.meridianStates().get(0).lumenQi().entries().get(0), source.entries().get(0));
        assertEquals(TARGET_NONE_QI, states.get(1).lumenQi().entries().get(1).amount(), 0);
        assertKindsConserved(initial, updated);
    }

    private static BodySnapshot arrivalDustBody() {
        // 两端使用到货复现的实际明细，满源和部分占用目标之间保留有效压差。
        var original = BodyProtobuf.toMessage(precisionBody(PRECISION_MINIMUM_BATCH));
        QiInventory stock = new QiInventory(List.of(
                new Qi(Qi.QiAttribute.NONE, ARRIVAL_DUST_QI, ARRIVAL_DUST_DENSITY),
                new Qi(Qi.QiAttribute.EARTH, ARRIVAL_EARTH_QI, ARRIVAL_EARTH_DENSITY)));
        QiInventory targetQi = new QiInventory(List.of(
                new Qi(Qi.QiAttribute.EARTH, TARGET_EARTH_QI, TARGET_EARTH_DENSITY),
                new Qi(Qi.QiAttribute.NONE, TARGET_NONE_QI, TARGET_NONE_DENSITY)));
        var sourceDefinition = original.getStructure().getMeridians(0).toBuilder()
                .setLumenVolume(stock.volume())
                .build();
        var structure = original.getStructure().toBuilder()
                .setMeridians(0, sourceDefinition)
                .build();
        var opening = BodyMessages.MeridianOpening.newBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.OPENED)
                .setProgress(1)
                .build();
        var source = original.getMeridianStates(0).toBuilder()
                .setLumenQi(BodyProtobuf.toInventory(stock))
                .setOpening(opening)
                .build();
        var target = original.getMeridianStates(1).toBuilder()
                .setLumenQi(BodyProtobuf.toInventory(targetQi))
                .build();
        var message = original.toBuilder()
                .setStructure(structure)
                .setMeridianStates(0, source)
                .setMeridianStates(1, target)
                .build();
        return BodyProtobuf.fromMessage(message);
    }

    private static BodySnapshot precisionBody(double minimumBatchQi) {
        // 只保留一条有向通路和两种正常余额，隔离体积反算产生的微量第二项。
        var original = BodyProtobuf.toMessage(body());
        var structure = precisionStructure(original.getStructure(), minimumBatchQi);
        QiInventory stock = new QiInventory(List.of(new Qi(Qi.QiAttribute.FIRE, 1, PRECISION_DENSITY),
                new Qi(Qi.QiAttribute.WOOD, 1, PRECISION_DENSITY)));
        var source = original.getMeridianStates(0).toBuilder()
                .setLumenQi(BodyProtobuf.toInventory(stock))
                .build();
        var message = original.toBuilder()
                .setStructure(structure)
                .clearMeridianStates()
                .addMeridianStates(source)
                .addMeridianStates(original.getMeridianStates(1))
                .build();
        return BodyProtobuf.fromMessage(message);
    }

    private static BodyMessages.BodyStructure precisionStructure(BodyMessages.BodyStructure original,
                                                                  double minimumBatchQi) {
        // 连接吞吐占满两端预算，第一次到达前只能有一批；容量差确定可复现的舍入边界。
        var source = original.getMeridians(0).toBuilder()
                .setLumenVolume(PRECISION_SOURCE_VOLUME)
                .build();
        var target = original.getMeridians(1).toBuilder()
                .setLumenVolume(PRECISION_TARGET_VOLUME)
                .build();
        var flow = BodyMessages.PassiveQiFlow.newBuilder()
                .setThroughputPerSecond(1)
                .setMaximumBatchQi(1)
                .setMinimumBatchQi(minimumBatchQi)
                .build();
        var connection = original.getConnections(0).toBuilder()
                .setPassiveQiFlow(flow)
                .build();
        return original.toBuilder()
                .clearMeridians()
                .addMeridians(source)
                .addMeridians(target)
                .clearConnections()
                .addConnections(connection)
                .build();
    }

    private static BodySnapshot advanceThroughStore(BodySnapshot initial, long millis) {
        // 通过公开命令入口原子提交，避免仅调用纯算子而遗漏宿主时间、版本和回执保存。
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        BodyId bodyId = initial.id();
        var handler = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true,
                (caller, body) -> TimeProtobuf.toMessage(plan(millis, BodyTimeAdvancement.Mode.ACTIVE, 0)),
                store, (caller, receipt, before, after) -> { /* 断言读取已发布的事务结果。 */ });
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(bodyId.value())
                .setExpectedRevision(initial.revision())
                .setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance())
                .build();
        var request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("precision-advance")
                .setBodyCommand(command)
                .build();
        var response = MeridianProtocol.decodeResponse(handler.handle("host", MeridianProtocol.encodeRequest(request)));
        assertTrue(response.toString(), response.hasCommandCommitted());
        assertEquals(1, store.receipts().size());
        return store.find(bodyId).orElseThrow();
    }

    private static void assertKindsConserved(BodySnapshot initial, BodySnapshot updated) {
        // 内腔、外壁和在途逐种汇总，不能用不同属性之间的增减相抵掩盖造货或丢货。
        QiInventory original = meridianKinds(initial);
        QiInventory actual = meridianKinds(updated);
        List<Qi> expectedEntries = original.entries();
        List<Qi> actualEntries = actual.entries();
        assertEquals(expectedEntries.size(), actualEntries.size());
        for (Qi expected : expectedEntries) {
            Qi remaining = actualEntries.stream()
                    .filter(qi -> qi.phase() == expected.phase() && qi.attribute().equals(expected.attribute()))
                    .findFirst().orElseThrow();
            assertEquals(expected.amount(), remaining.amount(), QI_TOLERANCE);
        }
        assertEquals(initial.wallQiTotal(), updated.wallQiTotal(), 0);
    }

    private static QiInventory meridianKinds(BodySnapshot body) {
        // 夹具没有器官，所有真元只可能位于这三类经脉资源账户中。
        var inventories = new ArrayList<QiInventory>();
        for (MeridianState state : body.meridianStates()) {
            inventories.add(state.lumenQi());
            inventories.add(state.wallQi());
        }
        body.transits().forEach(transit -> inventories.add(transit.cargo()));
        return QiInventory.combine(inventories);
    }

    private static BodySnapshot body() {
        // 三脉总容量 2000 体积，初值仅在 A；双向通路按当前体积占比扩散。
        var part = new Anatomy.PartId("shell");
        var definitions = List.of(meridian("a", part, 8), meridian("b", part, 4), meridian("c", part, 8));
        var flow = new Anatomy.PassiveQiFlow(.5, .25, .001);
        var connections = List.of(connection("a", "b", flow), connection("b", "a", flow),
                connection("b", "c", flow), connection("c", "b", flow),
                connection("c", "a", flow), connection("a", "c", flow));
        var structure = new BodyStructure(List.of(new Anatomy.PartDefinition(part, "外壳", 100)),
                List.of(), List.of(), definitions, connections);
        var mixed = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 3, .01),
                new Qi(Qi.QiAttribute.of("sample:lava"), 3, 10)));
        return new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("diffusion-body"),
                new BodyTemplate.Reference(new DefinitionId("sample:diffusion"), 1), structure, Realm.WANDERING_QI,
                List.of(new AnatomyState.Part(part, 100)), List.of(), List.of(),
                List.of(state("a", mixed), state("b", QiInventory.empty()), state("c", QiInventory.empty())),
                List.of(), new VitalityState(0, 1000, 0, false), plan(0, BodyTimeAdvancement.Mode.ACTIVE, 0).target(), 0);
    }

    private static Anatomy.MeridianDefinition meridian(String id, Anatomy.PartId part, double capacity) {
        // 参数直接使用液态参照体积，初始真元仍按 0.01 密度换算。
        return new Anatomy.MeridianDefinition(new Anatomy.MeridianId(id), id, List.of(part), capacity * 100, 100,
                1, 1, 100,
                List.of(new Anatomy.Port(new Anatomy.PortId(id + ".port"), Anatomy.PortDirection.BIDIRECTIONAL)));
    }

    private static Anatomy.Connection connection(String from, String to, Anatomy.PassiveQiFlow flow) {
        // 小距离使整体验证覆盖多轮真实到达，构型顺序与调度顺序刻意不同。
        return new Anatomy.Connection(new Anatomy.ConnectionId(from + "." + to), new Anatomy.PortId(from + ".port"),
                new Anatomy.PortId(to + ".port"), 0, .1, flow);
    }

    private static MeridianState state(String id, QiInventory lumen) {
        // 外壁非零用于保护储备账户；未开状态不会被自然流动改成已开。
        return new MeridianState(new Anatomy.MeridianId(id), lumen, free(.1), 0,
                MeridianState.Condition.INTACT, new MeridianOpening(MeridianOpening.Stage.UNOPENED, 0));
    }

    private static QiInventory free(double amount) {
        // 测试货物始终具有明确相态与属性。
        return new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, amount, .01)));
    }

    private static BodyTimeAdvancement.Plan plan(long millis, BodyTimeAdvancement.Mode mode, double agingRate) {
        // 固定时间身份，避免用真实时钟掩盖分段推进差异。
        return new BodyTimeAdvancement.Plan(new TimeCursor(new DefinitionId("sample:clock"), "epoch-1", millis), agingRate, mode);
    }

    private static BodySnapshot advance(BodySnapshot body, long millis, BodyTimeAdvancement.Mode mode) {
        // 平时暂停老化，死亡回归单独启用倍率，隔离资源推进的结果。
        return BodyTimeAdvancement.apply(body, plan(millis, mode, 0)).body();
    }
}
