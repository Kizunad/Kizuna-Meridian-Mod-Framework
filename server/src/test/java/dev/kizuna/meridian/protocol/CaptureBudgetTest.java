package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyTimeSource;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.server.OrganRecipeCatalog;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 接触摄入的每秒额度属于身体存档，不属于某次吐纳会话。 */
public class CaptureBudgetTest {
    private static final String SOURCE = "sample:capture_budget";
    private static final String ORGAN = "filter";
    private static final String RECIPE = "sample:contact";
    private static final long SECOND = 1_000;
    private static final long SOURCE_EXPIRY = 10_000;
    private static final double SOURCE_VOLUME = 100;
    private static final double SOURCE_QI = 1_000;
    private static final double REFINED_FRACTION = 0.2;
    private static final double DENSITY = 0.01;
    private static final double EPSILON = 1e-9;

    @Test
    public void limitPersistsAcrossReloadAndRefreshesInNextSecond() {
        // 每个窗口内所有请求共用限额；存档往返后不能再领，下一秒才恢复。
        BodyMessages.BodySnapshot initial = initial();
        var body = BodyProtobuf.fromMessage(initial);
        var source = source(initial.getSettledAt());
        var store = new InMemoryBodyStore(List.of(body), List.of(), QiInventory.empty(), List.of(source));
        var now = new AtomicLong();
        BodyCommandHandler<String> handler = handler(store, initial, now);
        assertTrue(capture(handler, store, body.id(), "first").hasCommandCommitted());
        var after = store.find(body.id()).orElseThrow();
        assertEquals(REFINED_FRACTION, after.organStates().getFirst().buffer().totalQi(), EPSILON);
        var rejected = capture(handler, store, body.id(), "same-second");
        assertEquals(MeridianMessages.FailureCode.CAPACITY_EXCEEDED, rejected.getFailure().getCode());
        assertEquals(after, store.find(body.id()).orElseThrow());

        var persisted = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(after));
        var environment = store.environmentSource(SOURCE).orElseThrow();
        var restored = new InMemoryBodyStore(List.of(persisted), store.receipts(),
                store.environmentQi(), List.of(environment));
        BodyCommandHandler<String> resumed = handler(restored, initial, now);
        assertEquals(MeridianMessages.FailureCode.CAPACITY_EXCEEDED,
                capture(resumed, restored, body.id(), "reloaded").getFailure().getCode());
        assertEquals(environment, restored.environmentSource(SOURCE).orElseThrow());
        now.set(SECOND);
        advance(resumed, restored, body.id());
        assertTrue(capture(resumed, restored, body.id(), "next-second").hasCommandCommitted());
        var finalBody = restored.find(body.id()).orElseThrow();
        assertEquals(REFINED_FRACTION * 2, finalBody.organStates().getFirst().buffer().totalQi(), EPSILON);
        double remaining = BodyProtobuf.fromInventory(restored.environmentSource(SOURCE)
                .orElseThrow().getInventory()).totalQi();
        assertEquals(body.totalQi() + SOURCE_QI, finalBody.totalQi() + remaining, EPSILON);
    }

    private static BodyMessages.BodySnapshot initial() {
        // 非人器官使用同一规则，限额与模板种类无关。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var intake = BodyMessages.OrganIntakeCapability.newBuilder().setMaxQiPerSecond(1).build();
        var organ = original.getStructure().getOrgans(0).toBuilder().setIntake(intake).build();
        var structure = original.getStructure().toBuilder().setOrgans(0, organ).build();
        var time = original.getSettledAt().toBuilder().setElapsedMillis(0).build();
        return original.toBuilder()
                .setStructure(structure)
                .setSettledAt(time)
                .setRevision(0)
                .clearTransits()
                .build();
    }

    private static EnvironmentMessages.EnvironmentQiSource source(BodyMessages.TimeCursor time) {
        // 浓度足够高以触达摄入上限，剩余库存用于验证事务守恒。
        var space = EnvironmentMessages.EnvironmentSpace.newBuilder()
                .setProtocolVersion(1)
                .setSourceId(SOURCE)
                .setKind(EnvironmentMessages.EnvironmentSpaceKind.REGION_BACKGROUND)
                .setVolumeBlocks(SOURCE_VOLUME)
                .build();
        var expiry = time.toBuilder().setElapsedMillis(SOURCE_EXPIRY).build();
        return EnvironmentAbsorption.initialize(space, BodyProtobuf.fromInventory(qi("addon:mist", SOURCE_QI)),
                time, expiry, new EnvironmentQiRules(EnvironmentConfigLoader.defaults()));
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, BodyMessages.BodySnapshot initial,
                                                       AtomicLong now) {
        // 时钟由测试宿主推进，命令本身不能上报时间或刷新额度。
        BodyTimeSource<String> clock = (caller, body) -> {
            var time = initial.getSettledAt().toBuilder().setElapsedMillis(now.get()).build();
            return MeridianMessages.TimeAdvancePlan.newBuilder()
                    .setTarget(time)
                    .setMode(MeridianMessages.TimeAdvanceMode.ACTIVE)
                    .setBiologicalSecondsPerSimulationSecond(1)
                    .build();
        };
        var recipe = BodyMessages.OrganRecipe.newBuilder()
                .setId(RECIPE)
                .setVersion(1)
                .setOrganKind("sample:filter")
                .setInput(qi("addon:mist", 1))
                .setOutput(qi("meridian:none", REFINED_FRACTION))
                .setDurationMillis(1)
                .setMinimumRealm(BodyMessages.Realm.WANDERING_QI)
                .build();
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true, clock, store,
                (caller, receipt, before, after) -> { /* 内存 Store 已保存成功事务。 */ },
                new OrganRecipeCatalog(List.of(recipe)), (caller, body, plan) -> Optional.of(SOURCE));
    }

    private static MeridianMessages.ResponseEnvelope capture(BodyCommandHandler<String> handler,
            InMemoryBodyStore store, BodyId id, String requestId) {
        // 每次读取实际版本，避免把版本冲突误当成摄入上限生效。
        var capture = MeridianMessages.CaptureQi.newBuilder()
                .setOrganId(ORGAN)
                .setRecipeId(RECIPE)
                .setRecipeVersion(1)
                .setSampleVolumeBlocks(1)
                .build();
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(store.find(id).orElseThrow().revision())
                .setCaptureQi(capture)
                .build();
        var request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        return MeridianProtocol.decodeResponse(handler.handleCapturedQi("owner", request.toByteArray()));
    }

    private static BodyMessages.QiInventory qi(String attribute, double amount) {
        // 配方和环境保持相同属性与密度，测试只关注额度和守恒。
        var entry = BodyMessages.Qi.newBuilder()
                .setDensity(DENSITY)
                .setAttribute(attribute)
                .setAmount(amount)
                .build();
        return BodyMessages.QiInventory.newBuilder().addEntries(entry).build();
    }

    private static void advance(BodyCommandHandler<String> handler, InMemoryBodyStore store, BodyId id) {
        // 接触命令要求已结算到可信时刻，先走正式时间命令推进身体。
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(store.find(id).orElseThrow().revision())
                .setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance())
                .build();
        var request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("advance")
                .setBodyCommand(command)
                .build();
        var response = MeridianProtocol.decodeResponse(handler.handle("owner", request.toByteArray()));
        assertTrue(response.toString(), response.hasCommandCommitted());
    }
}
