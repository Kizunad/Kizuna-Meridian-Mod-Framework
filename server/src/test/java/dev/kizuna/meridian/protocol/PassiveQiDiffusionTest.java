package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** 自然扩散整体回归：公开构型、事件推进、调用权限与唯一资源归属。 */
public class PassiveQiDiffusionTest {
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
