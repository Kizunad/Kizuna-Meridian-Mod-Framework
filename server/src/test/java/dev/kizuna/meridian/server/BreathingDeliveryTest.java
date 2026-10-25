package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.CapabilityProtobuf;
import dev.kizuna.meridian.protocol.TimeProtobuf;
import dev.kizuna.meridian.protocol.pb.BreathingMessages;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/** 吐纳配送整体回归：混合缓存按真实容量发运，未达到单位的份额不离开器官。 */
public class BreathingDeliveryTest {
    private static final double DENSITY = 0.1;
    private static final double TARGET_VOLUME = 0.7;
    private static final double BUFFER_VOLUME = 20;
    private static final double DUST_QI = Math.nextDown(QiAccounting.MIN_TRANSFER_QI);
    private static final double QI_TOLERANCE = 1e-12;
    private static final double LIFESPAN_SECONDS = 1000;
    private static final double RECIPE_INPUT_QI = 2;

    @Test
    public void mixedOutputCommitsNormalCargoAndLeavesTheSubQuantumShareInItsBuffer() {
        // 比例装箱也可能从正常批次夹出微量属性，必须在真实命令提交前过滤。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        GatheringSession<String> session = session(initial, store);
        session.beginManual();
        var organ = BreathingMessages.BreathingOrgan.newBuilder().setOrganId("filter").build();
        var profile = BreathingMessages.BreathingProfile.newBuilder()
                .addOrgans(organ)
                .addRecipeIds("sample:refine")
                .build();
        var delivery = new BreathingDelivery<>(session, profile);
        assertTrue(delivery.advance());

        BodySnapshot dispatched = store.find(initial.id()).orElseThrow();
        assertEquals(initial.revision() + 1, dispatched.revision());
        assertEquals(1, store.receipts().size());
        List<QiTransit> transits = dispatched.transits();
        assertEquals(1, transits.size());
        QiTransit transit = transits.get(0);
        QiInventory cargo = transit.cargo();
        List<Qi> cargoEntries = cargo.entries();
        assertEquals(1, cargoEntries.size());
        assertEquals(Qi.QiAttribute.EARTH, cargoEntries.get(0).attribute());
        assertEquals(DENSITY, cargoEntries.get(0).density(), 0);
        assertTrue(cargo.totalQi() > 0);
        assertTrue(cargo.volume() <= TARGET_VOLUME);
        assertEquals(initial.organStates().get(0).buffer().entries().get(0),
                dispatched.organStates().get(0).buffer().entries().get(0));

        assertArrivalConservesQi(initial, dispatched, transit);
    }

    private static void assertArrivalConservesQi(BodySnapshot initial, BodySnapshot dispatched, QiTransit transit) {
        // 真正到达后再核对唯一资源归属，不能把被过滤的尘埃当成已配送产物。
        BodySnapshot arrived = BodyTimeAdvancement.apply(dispatched, plan(initial, transit.arrivalMillis())).body();
        assertTrue(arrived.transits().isEmpty());
        assertEquals(DUST_QI, arrived.organStates().get(0).buffer().entries().get(0).amount(), 0);
        assertEquals(transit.cargo(), arrived.meridianStates().get(0).lumenQi());
        assertEquals(initial.totalQi(), arrived.totalQi(), QI_TOLERANCE);
    }

    private static GatheringSession<String> session(BodySnapshot initial, InMemoryBodyStore store) {
        // 配送复用真实处理器和配方目录，不另建扣款或运输的测试替身。
        QiInventory input = new QiInventory(List.of(new Qi(Qi.QiAttribute.FIRE, RECIPE_INPUT_QI, DENSITY)));
        QiInventory output = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 1, DENSITY),
                new Qi(Qi.QiAttribute.EARTH, 1, DENSITY)));
        var recipe = new OrganRecipe(new DefinitionId("sample:refine"), 1, new DefinitionId("sample:filter"),
                input, output, 1, Realm.WANDERING_QI);
        var recipes = new OrganRecipeCatalog(List.of(CapabilityProtobuf.toRecipe(recipe)));
        BodyTimeSource<String> clock = (caller, body) -> TimeProtobuf.toMessage(plan(initial, 0));
        var handler = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true, clock, store,
                (caller, receipt, before, after) -> { /* 从存储核对已发布的身体与回执。 */ }, recipes);
        return new GatheringSession<>("owner", initial.id(), handler, store, (caller, id) -> true, clock,
                body -> Optional.empty(), recipes);
    }

    private static BodySnapshot body() {
        // 合成的器官与单脉只有一条实际出口，小目标空间会截取混合缓存的同一比例。
        var part = new Anatomy.PartId("shell");
        var organ = new Anatomy.OrganId("filter");
        var meridian = new Anatomy.MeridianId("channel");
        var organPort = new Anatomy.PortId("filter.out");
        var meridianPort = new Anatomy.PortId("channel.in");
        var partDefinition = new Anatomy.PartDefinition(part, "外壳", 1);
        var organDefinition = new Anatomy.OrganDefinition(organ, "过滤器", new DefinitionId("sample:filter"),
                part, 1, List.of(new Anatomy.Port(organPort, Anatomy.PortDirection.OUTPUT)), BUFFER_VOLUME, 1,
                new Anatomy.OrganIntakeCapability(1));
        var meridianDefinition = new Anatomy.MeridianDefinition(meridian, "通路", List.of(part), TARGET_VOLUME,
                1, 1, 1, 1, List.of(new Anatomy.Port(meridianPort, Anatomy.PortDirection.INPUT)));
        var connection = new Anatomy.Connection(new Anatomy.ConnectionId("filter.channel"),
                organPort, meridianPort, 0, 1);
        var structure = new BodyStructure(List.of(partDefinition), List.of(), List.of(organDefinition),
                List.of(meridianDefinition), List.of(connection));
        QiInventory buffer = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, DUST_QI, DENSITY),
                new Qi(Qi.QiAttribute.EARTH, 1, DENSITY)));
        var organState = new AnatomyState.Organ(organ, 1, buffer, null);
        var meridianState = new MeridianState(meridian, QiInventory.empty(), QiInventory.empty(), 0,
                MeridianState.Condition.INTACT, new MeridianOpening(MeridianOpening.Stage.UNOPENED, 0));
        var time = new TimeCursor(new DefinitionId("sample:world"), "fixed", 0);
        var template = new BodyTemplate.Reference(new DefinitionId("sample:filter_body"), 1);
        return new BodySnapshot(BodySnapshot.CURRENT_SCHEMA_VERSION, new BodyId("mixed-buffer"), template,
                structure, Realm.WANDERING_QI, List.of(new AnatomyState.Part(part, 1)), List.of(),
                List.of(organState), List.of(meridianState), List.of(),
                new VitalityState(0, LIFESPAN_SECONDS, 0, false /* dead */), time, 0);
    }

    private static BodyTimeAdvancement.Plan plan(BodySnapshot body, long millis) {
        // 同域同世代的事件时间由测试宿主提供，生物年龄按模拟时间正常推进。
        TimeCursor start = body.settledAt();
        var target = new TimeCursor(start.domain(), start.epoch(), millis);
        return new BodyTimeAdvancement.Plan(target, 1, BodyTimeAdvancement.Mode.ACTIVE);
    }
}
