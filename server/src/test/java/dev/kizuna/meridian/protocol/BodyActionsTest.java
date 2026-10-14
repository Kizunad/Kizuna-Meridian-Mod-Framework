package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.*;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** 整体回归：动作并发占用、指数恢复与启停事务。 */
public class BodyActionsTest {
    @Test
    public void sharedTissueRecoversOnlyAfterLastActionStopsAcrossSaveRestore() {
        // 非人动作由目录注册，同一组织可被两种动作占用；其他组织独立恢复。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var now = new AtomicLong();
        var handler = handler(store, now, true, catalog());
        byte[] start = request(store, "start-a", start("a", "sample:grip"));
        byte[] response = handler.handle("owner", start);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        assertArrayEquals(response, handler.handle("owner", start));
        committed(handler, store, "start-b", start("b", "sample:hold"));
        assertEquals(2, current(store).actions().size());
        now.set(4000);
        committed(handler, store, "advance-4", advance());
        assertEquals(20, current(store).tissueStates().get(0).fatigue(), 0);
        assertEquals(5, current(store).tissueStates().get(1).fatigue(), 1e-10);
        committed(handler, store, "stop-a", stop("a"));
        now.set(8000);
        committed(handler, store, "advance-8", advance());
        assertEquals(20, current(store).tissueStates().get(0).fatigue(), 0);

        // 已存实例固定占用定义，卸载目录后仍能结算和停止，不能偷偷解除另一动作。
        BodySnapshot saved = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(current(store)));
        var restored = new InMemoryBodyStore(List.of(saved), store.receipts(), store.environmentQi());
        assertEquals(store.receipts().size(), restored.receipts().size());
        assertThrows(UnsupportedOperationException.class, () -> store.receipts().clear());
        var missingContent = handler(restored, now, true, new ActionCatalog(List.of()));
        committed(missingContent, restored, "stop-b", stop("b"));
        now.set(12000);
        committed(missingContent, restored, "recover-12", advance());
        assertEquals(10, current(restored).tissueStates().get(0).fatigue(), 1e-10);
        assertTrue(current(restored).actions().isEmpty());
        assertEquals(initial.totalQi(), current(restored).totalQi(), 0);

        // 启动回执与快照都可恢复；重试不要求目录仍然存在，也不新增第二个实例。
        var receipt = store.transact(initial.id(), tx -> tx.receipt("owner", "start-a").orElseThrow());
        var persisted = MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt));
        var replayStore = new InMemoryBodyStore(List.of(saved), List.of(persisted));
        assertArrayEquals(response, handler(replayStore, now, true, new ActionCatalog(List.of())).handle("owner", start));
        assertEquals(saved, current(replayStore));
    }

    @Test
    public void actionChangesRequireSettledTimeAndAtomicAuthorization() {
        // 未先结算的启停被拒绝，不能让新占用或解除占用追溯改变之前四秒的恢复。
        var store = new InMemoryBodyStore(List.of(body()), List.of());
        var now = new AtomicLong(4000);
        var handler = handler(store, now, true, catalog());
        failure(handler.handle("owner", request(store, "early", start("a", "sample:grip"))), FailureCode.TIME_REQUIRES_SETTLEMENT);
        committed(handler, store, "settle", advance());
        assertEquals(10, current(store).tissueStates().get(0).fatigue(), 1e-10);
        BodySnapshot before = current(store);
        failure(handler(store, now, false, catalog()).handle("owner", request(store, "denied", start("a", "sample:grip"))),
                FailureCode.ACCESS_DENIED);
        assertEquals(before, current(store));
        failure(handler.handle("owner", request(store, "unknown", start("a", "sample:missing"))), FailureCode.ACTION_NOT_FOUND);
        committed(handler, store, "start", start("a", "sample:grip"));
        failure(handler.handle("owner", request(store, "duplicate", start("a", "sample:grip"))), FailureCode.ACTION_ALREADY_ACTIVE);
        failure(handler.handle("owner", request(store, "wrong-stop", stop("missing"))), FailureCode.ACTION_NOT_ACTIVE);
        now.set(8000);
        failure(handler.handle("owner", request(store, "stop-early", stop("a"))), FailureCode.TIME_REQUIRES_SETTLEMENT);
        committed(handler, store, "settle-active", advance());
        assertEquals(10, current(store).tissueStates().get(0).fatigue(), 1e-10);
        committed(handler, store, "stop", stop("a"));

        // 实际组织必须存在；客户端不能上传自选组织清单来替代目录内容。
        var invalidDefinition = definition("sample:invalid").toBuilder().clearTissueIds().addTissueIds("missing").build();
        var invalidCatalog = new ActionCatalog(List.of(invalidDefinition));
        failure(handler(store, now, true, invalidCatalog).handle("owner", request(store, "invalid", start("a", "sample:invalid"))),
                FailureCode.MODULE_NOT_FOUND);
        assertThrows(IllegalArgumentException.class, () -> ActionProtobuf.fromDefinition(
                definition("sample:grip").toBuilder().addTissueIds("tendril").build()));
    }

    @Test
    public void resourceMutationsFreezeAndDeathPreserveOrClearActionsCorrectly() {
        // 其他身体算子不得顺手清空动作，否则下一段恢复会错误解除占用。
        BodySnapshot active = BodyActions.start(body(), "a", ActionProtobuf.fromDefinition(definition("sample:grip")));
        assertEquals(active.actions(), LifespanSpending.apply(active, 1).actions());
        var message = BodyProtobuf.toMessage(active);
        BodySnapshot withEnergy = BodyProtobuf.fromMessage(message.toBuilder().setTissueStates(0,
                message.getTissueStates(0).toBuilder().setFatigue(10)).build());
        assertEquals(active.actions(), TissueCapabilities.exert(withEnergy,
                new TissueCapabilities.Exertion(new Anatomy.TissueId("tendril"), 0, 1, 1)).actions());
        var transport = new QiTransport.Request(new Anatomy.ConnectionId("channel.organ"),
                new QiInventory(List.of(new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.of("addon:lava"), .1))), 1);
        assertEquals(active.actions(), QiTransport.start(active, transport).actions());
        BodySnapshot frozen = BodyTimeAdvancement.apply(active, TimeProtobuf.fromMessage(plan(4000).toBuilder()
                .setMode(TimeAdvanceMode.LIFESPAN_ONLY).build())).body();
        assertEquals(active.actions(), frozen.actions());
        assertEquals(active.tissueStates(), frozen.tissueStates());

        // 寿尽会一次清除所有动作；死亡后不留下占用，也不能靠动作影响死体恢复。
        BodySnapshot mortal = BodyProtobuf.fromMessage(message.toBuilder().setVitality(message.getVitality().toBuilder()
                .setLifespanLimitSeconds(17)).build());
        BodySnapshot dead = BodyTimeAdvancement.apply(mortal, TimeProtobuf.fromMessage(plan(10000).toBuilder()
                .setBiologicalSecondsPerSimulationSecond(1).build())).body();
        assertTrue(dead.vitality().dead());
        assertTrue(dead.actions().isEmpty());
        assertEquals(20, dead.tissueStates().get(0).fatigue(), 0);
    }

    private static BodySnapshot body() {
        // 为恢复隔离出两个组织，内容来自非人测试构型，初始都疲劳。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.AWAKENING));
        var tissue = original.getStructure().getTissues(0).toBuilder().setRecoveryHalfLifeSeconds(4);
        return BodyProtobuf.fromMessage(original.toBuilder().setRevision(0).clearTransits().setSettledAt(plan(0).getTarget())
                .setMeridianStates(0, original.getMeridianStates(0).toBuilder().setCondition(BodyMessages.MeridianCondition.INTACT))
                .setStructure(original.getStructure().toBuilder().setTissues(0, tissue)
                        .addTissues(tissue.clone().setId("other").clearPorts()))
                .setTissueStates(0, original.getTissueStates(0).toBuilder().setFatigue(20))
                .addTissueStates(original.getTissueStates(0).toBuilder().setId("other").setFatigue(10)).build());
    }

    private static BodyMessages.ActionDefinition definition(String id) {
        // 两种内容可以同时占用同一组织，目录不隐含互斥。
        return BodyMessages.ActionDefinition.newBuilder().setId(id).setVersion(1).setDisplayName("触须动作")
                .addTissueIds("tendril").addOrganIds("filter").build();
    }

    private static ActionCatalog catalog() {
        // 注册后只可按精确版本启动。
        return new ActionCatalog(List.of(definition("sample:grip"), definition("sample:hold")));
    }

    private static BodySnapshot current(InMemoryBodyStore store) {
        // 查询唯一权威状态，测试不另存动作列表。
        return store.find(new BodyId("creature-1")).orElseThrow();
    }

    private static TimeAdvancePlan plan(long millis) {
        // 模拟时间由可信宿主提供，恢复不使用现实墙钟。
        return TimeAdvancePlan.newBuilder().setTarget(BodyMessages.TimeCursor.newBuilder()
                .setDomain("sample:world").setEpoch("epoch-1").setElapsedMillis(millis))
                .setBiologicalSecondsPerSimulationSecond(0).setMode(TimeAdvanceMode.ACTIVE).build();
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, AtomicLong time, boolean allow, ActionCatalog actions) {
        // 真实处理器验证目录、权限、版本和回执；不设置环境来源。
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller), (caller, command, body) -> allow,
                (caller, body) -> plan(time.get()), store, (caller, receipt, before, after) -> { },
                new OrganRecipeCatalog(List.of()), (caller, body, plan) -> Optional.empty(), actions);
    }

    private static BodyCommand.Builder start(String instance, String id) {
        // 操作意图只引用内容与实例身份。
        return BodyCommand.newBuilder().setStartAction(StartAction.newBuilder().setInstanceId(instance).setActionId(id).setActionVersion(1));
    }

    private static BodyCommand.Builder stop(String instance) {
        // 结束定位实例，不按定义 ID 一次清空同类动作。
        return BodyCommand.newBuilder().setStopAction(StopAction.newBuilder().setInstanceId(instance));
    }

    private static BodyCommand.Builder advance() {
        // 时间意图不含可伪造的休息名单。
        return BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance());
    }

    private static byte[] request(InMemoryBodyStore store, String id, BodyCommand.Builder operation) {
        // 每条新请求引用最新版本，重试用原始字节。
        return MeridianProtocol.encodeRequest(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(id)
                .setBodyCommand(operation.setBodyId("creature-1").setExpectedRevision(current(store).revision())).build());
    }

    private static void committed(BodyCommandHandler<String> handler, InMemoryBodyStore store, String id, BodyCommand.Builder operation) {
        // 回执必须确认提交，不能只看最终数值。
        var response = MeridianProtocol.decodeResponse(handler.handle("owner", request(store, id, operation)));
        assertTrue(response.toString(), response.hasCommandCommitted());
    }

    private static void failure(byte[] response, FailureCode expected) {
        // 错误代码是跨语言公共契约。
        assertEquals(expected, MeridianProtocol.decodeResponse(response).getFailure().getCode());
    }
}
