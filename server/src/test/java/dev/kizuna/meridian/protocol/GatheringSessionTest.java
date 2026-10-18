package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.*;
import org.junit.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** 公共协议与非人体编排回归；人体具体数值由 Human 附属自己的整体测试覆盖。 */
public class GatheringSessionTest {
    @Test
    public void protocolRejectsMissingRevisionUnknownFieldsAndClientTime() {
        // 固定字节来自独立字段布局，锁定查询字段号而不只验证自身往返。
        byte[] query = HexFormat.of().parseHex("0801120267311a0a63726561747572652d315200");
        GatheringRequest request = GatheringProtobuf.decodeRequest(query);
        assertTrue(request.hasInspect());
        assertEquals("creature-1", request.getBodyId());
        var start = StartGathering.newBuilder().setOrganId("filter").setRecipeId("sample:refine")
                .setRecipeVersion(1).setConnectionId("organ.channel");
        assertThrows(IllegalArgumentException.class, () -> GatheringProtobuf.decodeRequest(
                request.toBuilder().setStart(start).build().toByteArray()));
        assertEquals(0, GatheringProtobuf.decodeRequest(request.toBuilder()
                .setStart(start.setExpectedRevision(0)).build().toByteArray()).getStart().getExpectedRevision());
        // 未定义的时间字段不能被悄悄接受，再由后续实现误当可信事实。
        byte[] withUnknown = java.util.Arrays.copyOf(query, query.length + 2);
        withUnknown[query.length] = 32;
        withUnknown[query.length + 1] = 1;
        assertThrows(IllegalArgumentException.class, () -> GatheringProtobuf.decodeRequest(withUnknown));
        // 三维瞄准必须经过同一入口校验，越界深度不能绕过局部观察区。
        var breath = dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathControl.newBuilder()
                .setAction(dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathAction.INHALE)
                .setSequence(1).setAimX(.5).setAimY(.4).setAimZ(.8);
        assertEquals(.8, GatheringProtobuf.decodeRequest(request.toBuilder().setBreath(breath).build().toByteArray())
                .getBreath().getAimZ(), 0);
        assertFalse(GatheringProtobuf.decodeRequest(request.toBuilder().setBreath(breath.clone().clearAimZ())
                .build().toByteArray()).getBreath().hasAimZ());
        for (double depth : List.of(-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> GatheringProtobuf.decodeRequest(
                    request.toBuilder().setBreath(breath.setAimZ(depth)).build().toByteArray()));
        }
    }

    @Test
    public void nonHumanOrganCanRefineCustomAttributeAndStoreInUnopenedChannel() throws Exception {
        // 使用已有非人构型与第三方属性，证明 UI 链路不需要肺、五行或左右命名分支。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        TimeCursor startTime = original.getSettledAt().toBuilder().setElapsedMillis(0).build();
        var structure = original.getStructure().toBuilder().setOrgans(0, original.getStructure().getOrgans(0).toBuilder()
                .setIntake(OrganIntakeCapability.newBuilder().setMaxQiPerSecond(1)));
        var channel = original.getMeridianStates(0).toBuilder().setCondition(MeridianCondition.INTACT)
                .setOpening(MeridianOpening.newBuilder().setStage(MeridianOpeningStage.UNOPENED).setProgress(0));
        var initial = BodyProtobuf.fromMessage(original.toBuilder().setStructure(structure).setSettledAt(startTime)
                .setRevision(0).clearTransits().setMeridianStates(0, channel).build());
        var environment = EnvironmentAbsorption.initialize(EnvironmentSpace.newBuilder().setProtocolVersion(1)
                        .setSourceId("sample:region").setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(100).build(),
                BodyProtobuf.fromInventory(qi("addon:mist", 100)), startTime,
                startTime.toBuilder().setElapsedMillis(10000).build(), new EnvironmentQiRules(EnvironmentConfigLoader.defaults()));
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty(), List.of(environment));
        var recipe = OrganRecipe.newBuilder().setId("sample:refine").setVersion(1).setOrganKind("sample:filter")
                .setInput(qi("addon:mist", 1)).setOutput(qi("meridian:none", 0.5)).setDurationMillis(500)
                .setMinimumRealm(dev.kizuna.meridian.protocol.pb.BodyMessages.Realm.WANDERING_QI).build();
        var recipes = new OrganRecipeCatalog(List.of(recipe));
        var now = new AtomicLong();
        BodyTimeSource<String> clock = (caller, body) -> TimeAdvancePlan.newBuilder()
                .setTarget(startTime.toBuilder().setElapsedMillis(now.get()))
                .setBiologicalSecondsPerSimulationSecond(1).setMode(TimeAdvanceMode.ACTIVE).build();
        var handler = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true, clock, store, (caller, receipt, before, after) -> { }, recipes,
                (caller, body, plan) -> Optional.of("sample:region"));
        var session = new GatheringSession<>("owner", initial.id(), handler, store, (caller, id) -> true, clock,
                body -> store.environmentSource("sample:region"), recipes);
        GatheringRequest intent = GatheringRequest.newBuilder().setProtocolVersion(1).setRequestId("start")
                .setBodyId(initial.id().value()).setStart(StartGathering.newBuilder().setExpectedRevision(0)
                        .setOrganId("filter").setRecipeId("sample:refine").setRecipeVersion(1).setConnectionId("organ.channel")).build();
        assertTrue(GatheringResponse.parseFrom(session.handle(intent.toByteArray())).hasView());
        for (long boundary : List.of(1000L, 1500L, 2600L)) {
            assertEquals(boundary, session.nextBoundaryMillis());
            now.set(boundary);
            assertTrue(session.advance());
        }
        assertEquals(GatheringStage.COMPLETED, session.view().getProgress().getStage());
        assertEquals(0.5, session.view().getProgress().getStoredQi(), 0);
        var after = store.find(initial.id()).orElseThrow();
        assertEquals(initial.totalQi() + 0.5, after.totalQi(), 1e-12);
        assertEquals(initial.totalQi() + 100, after.totalQi() + store.environmentQi().totalQi()
                + BodyProtobuf.fromInventory(store.environmentSource("sample:region").orElseThrow().getInventory()).totalQi(), 1e-12);
        assertEquals(MeridianOpeningStage.UNOPENED, session.view().getBody().getMeridianStates(0).getOpening().getStage());
    }

    private static dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory qi(String attribute, double amount) {
        // 自定义属性保留完整身份，只有已安装配方可以决定加工产物。
        return dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory.newBuilder()
                .addEntries(Qi.newBuilder().setPhase(QiPhase.FREE).setAttribute(attribute).setAmount(amount)).build();
    }

    @Test
    public void contactsShareOneTimestampUseLiveConcentrationAndPersistAtomically() {
        // 非人体过滤器在零经过时间接收多颗，验证剂量不再绑定 250 ms 或器官每秒上限。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        TimeCursor now = original.getSettledAt().toBuilder().setElapsedMillis(0).build();
        var structure = original.getStructure().toBuilder().setOrgans(0, original.getStructure().getOrgans(0).toBuilder()
                .setIntake(OrganIntakeCapability.newBuilder().setMaxQiPerSecond(.001)));
        var initial = BodyProtobuf.fromMessage(original.toBuilder().setStructure(structure)
                .setSettledAt(now).setRevision(0).clearTransits().build());
        var source = EnvironmentAbsorption.initialize(EnvironmentSpace.newBuilder().setProtocolVersion(1)
                        .setSourceId("sample:contact").setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(100).build(),
                BodyProtobuf.fromInventory(qi("addon:mist", 100)), now, now.toBuilder().setElapsedMillis(10000).build(),
                new EnvironmentQiRules(EnvironmentConfigLoader.defaults()));
        var recipe = OrganRecipe.newBuilder().setId("sample:contact_refine").setVersion(1).setOrganKind("sample:filter")
                .setInput(qi("addon:mist", .05)).setOutput(qi("meridian:none", .01)).setDurationMillis(100)
                .setMinimumRealm(dev.kizuna.meridian.protocol.pb.BodyMessages.Realm.WANDERING_QI).build();
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty(), List.of(source));
        BodyTimeSource<String> clock = (caller, body) -> TimeAdvancePlan.newBuilder().setTarget(now)
                .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(1).build();
        var handler = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true, clock, store, (caller, receipt, before, after) -> { },
                new OrganRecipeCatalog(List.of(recipe)), (caller, body, plan) -> Optional.of("sample:contact"));
        var capture = CaptureQi.newBuilder().setOrganId("filter").setRecipeId(recipe.getId()).setRecipeVersion(1)
                .setSampleVolumeBlocks(1);
        var request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("contact-0")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId(initial.id().value()).setExpectedRevision(0).setCaptureQi(capture));

        // 即使宿主普通授权宽松，网络入口仍拒绝玩家伪造接触；可信入口才可扣环境库存。
        assertEquals(FailureCode.ACCESS_DENIED, MeridianProtocol.decodeResponse(handler.handle("owner", request.build().toByteArray()))
                .getFailure().getCode());
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        double expectedOutput = 0;
        for (int index = 0; index < 3; index++) {
            double concentration = BodyProtobuf.fromInventory(store.environmentSource("sample:contact").orElseThrow().getInventory())
                    .totalQi() / 100;
            expectedOutput += concentration * .2;
            request.setRequestId("contact-" + index).setBodyCommand(request.getBodyCommand().toBuilder().setExpectedRevision(index));
            var response = MeridianProtocol.decodeResponse(handler.handleCapturedQi("owner", request.build().toByteArray()));
            assertTrue(response.toString(), response.hasCommandCommitted());
            assertEquals(expectedOutput, store.find(initial.id()).orElseThrow().organStates().get(0).buffer().totalQi(), 1e-10);
        }
        var body = store.find(initial.id()).orElseThrow();
        var remaining = store.environmentSource("sample:contact").orElseThrow();
        assertEquals(now, BodyProtobuf.toTime(body.settledAt()));
        assertEquals(0, remaining.getUsageCount());
        assertEquals(initial.totalQi() + 100, body.totalQi() + BodyProtobuf.fromInventory(remaining.getInventory()).totalQi(), 1e-9);

        // 同一请求重放与存档恢复不能多领一次，来源版本与真实库存随回执一起保存。
        assertTrue(MeridianProtocol.decodeResponse(handler.handleCapturedQi("owner", request.build().toByteArray())).hasCommandCommitted());
        assertEquals(body, store.find(initial.id()).orElseThrow());
        var restored = new InMemoryBodyStore(List.of(body), store.receipts(), store.environmentQi(), List.of(remaining));
        assertEquals(body, restored.find(initial.id()).orElseThrow());
        assertEquals(remaining, restored.environmentSource("sample:contact").orElseThrow());

        // 高浓度或大体积遇到缓存上限只收可保存部分，满载后拒绝且两边账本均不变化。
        request.setRequestId("fill-contact").setBodyCommand(request.getBodyCommand().toBuilder()
                .setExpectedRevision(body.revision()).setCaptureQi(capture.setSampleVolumeBlocks(10000)));
        assertTrue(MeridianProtocol.decodeResponse(handler.handleCapturedQi("owner", request.build().toByteArray())).hasCommandCommitted());
        var full = store.find(initial.id()).orElseThrow();
        assertEquals(20, full.organStates().get(0).buffer().totalQi(), 1e-9);
        var fullSource = store.environmentSource("sample:contact").orElseThrow();
        request.setRequestId("blocked-contact").setBodyCommand(request.getBodyCommand().toBuilder().setExpectedRevision(full.revision()));
        assertEquals(FailureCode.CAPACITY_EXCEEDED,
                MeridianProtocol.decodeResponse(handler.handleCapturedQi("owner", request.build().toByteArray())).getFailure().getCode());
        assertEquals(full, store.find(initial.id()).orElseThrow());
        assertEquals(fullSource, store.environmentSource("sample:contact").orElseThrow());
    }
}
