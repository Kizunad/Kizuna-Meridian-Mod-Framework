package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.protocol.pb.OpeningMessages;
import dev.kizuna.meridian.server.*;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** 整体回归：真实通路引导、连续条件、独立费用、恢复和原子回执。 */
public class OpeningRuntimeTest {
    @Test
    public void sessionChecksOriginalRevisionBeforeSettlingAndReauthorizesRetries() {
        // UI 使用旧区间末尾的快照；入口结时不能制造必然冲突，也不能接受本已过期的版本。
        var store = new InMemoryBodyStore(List.of(body()), List.of());
        var now = new AtomicLong(500);
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var session = new OpeningSession<>("owner", new BodyId("creature-1"), handler(store, now), store,
                (caller, id) -> allowed.get());
        var intent = OpeningMessages.MeridianOpeningRequest.newBuilder().setProtocolVersion(1)
                .setRequestId("ui-open").setBodyId("creature-1").setExpectedRevision(0)
                .setStart(OpeningMessages.StartMeridianOpening.newBuilder().setRuleId("sample:open").setRuleVersion(1))
                .build();
        var response = session.handle(intent);
        assertTrue(response.toString(), response.hasCommandCommitted());
        BodySnapshot started = current(store);
        assertEquals(500, started.settledAt().elapsedMillis());
        assertEquals(0, started.openingTask().heldMillis());
        assertEquals(0, started.organStates().get(0).fatigueJoules(), 0);

        // 重试不再结时或扣费；同身份改意图和跨身体请求都要被拒绝。
        now.set(1500);
        assertEquals(response, session.handle(intent));
        assertEquals(started, current(store));
        assertEquals(FailureCode.REQUEST_ID_CONFLICT, session.handle(intent.toBuilder().clearStart()
                .setPause(OpeningMessages.PauseMeridianOpening.newBuilder().setMeridianId("channel")).build())
                .getFailure().getCode());
        assertEquals(FailureCode.ACCESS_DENIED, session.handle(intent.toBuilder().setBodyId("other").build())
                .getFailure().getCode());
        assertEquals(FailureCode.REVISION_CONFLICT,
                session.handle(intent.toBuilder().setRequestId("stale").build()).getFailure().getCode());
        assertEquals(started, current(store));
        allowed.set(false);
        assertEquals(FailureCode.ACCESS_DENIED, session.handle(intent).getFailure().getCode());
    }

    @Test
    public void missingOrInvalidPolicyCannotStartAndMissingSavedPolicyPausesSafely() {
        // 缺包与非法计划都不能建立临时容量；非人体 provider 只存在于测试 classpath。
        var missing = rule().toBuilder().setPolicyId("removed:opening").build();
        var rejected = assertThrows(dev.kizuna.meridian.error.MeridianErrors.IntakeRejected.class,
                () -> OpeningRuntime.start(body(), OpeningProtobuf.fromRule(missing), ActionProtobuf.fromDefinition(action())));
        assertEquals(FailureCode.OPENING_POLICY_UNAVAILABLE, rejected.code());
        var invalidPlan = rule().toBuilder().setPolicyConfig(SampleOpeningPolicy.configuration(0, false, false)).build();
        assertThrows(IllegalArgumentException.class, () -> OpeningRuntime.start(body(), OpeningProtobuf.fromRule(invalidPlan),
                ActionProtobuf.fromDefinition(action())));

        // 模拟存档恢复时原策略缺失；保留资源、停止成长，撤销占用并释放临时超额。
        var longRule = rule().toBuilder().setPolicyConfig(SampleOpeningPolicy.configuration(10000, false, false)).build();
        BodySnapshot started = OpeningRuntime.start(body(), OpeningProtobuf.fromRule(longRule), ActionProtobuf.fromDefinition(action()));
        BodySnapshot pressurized = BodyTimeAdvancement.apply(started, plan(2100)).body();
        var saved = BodyProtobuf.toMessage(pressurized);
        BodySnapshot unavailable = BodyProtobuf.fromMessage(saved.toBuilder().setOpeningTask(saved.getOpeningTask().toBuilder()
                .setRule(saved.getOpeningTask().getRule().toBuilder().setPolicyId("removed:opening"))).build());
        var paused = BodyTimeAdvancement.apply(unavailable, plan(2200));
        assertEquals(OpeningTask.Status.PAUSED, paused.body().openingTask().status());
        assertTrue(paused.body().actions().isEmpty());
        assertEquals(2, paused.body().structure().meridians().get(0).lumenCapacity(), 0);
        assertEquals(unavailable.totalQi(), paused.body().totalQi() + paused.releasedQi().totalQi(), 1e-9);
    }

    @Test
    public void strictHoldingDrainsAndRefillsAcrossSaveAndDifferentAdvanceIntervals() {
        // 连续费用必须真实扣出目标内腔；引导补到门槛以上，保存恢复不能漏扣或重扣。
        var consumingRule = rule().toBuilder().setPolicyConfig(SampleOpeningPolicy.configuration(10000, true, false))
                .setHoldQiPerSecond(.1).setGuidanceBufferQi(.2).build();
        BodySnapshot started = OpeningRuntime.start(body(), OpeningProtobuf.fromRule(consumingRule),
                ActionProtobuf.fromDefinition(action()));
        var whole = BodyTimeAdvancement.apply(started, plan(15000));
        assertEquals(OpeningTask.Status.COMPLETED, whole.body().openingTask().status());
        assertEquals(started.totalQi(), whole.body().totalQi() + whole.releasedQi().totalQi(), 1e-8);
        // 稳定目标保留 3 元，完成时至多释放 3.2 元，剩余至少 1 元来自维持费用。
        assertTrue(whole.releasedQi().totalQi() >= 4 - 1e-8);

        BodySnapshot split = started;
        double releasedQi = 0;
        for (long millis = 137; millis < 15000; millis += 137) {
            var advanced = BodyTimeAdvancement.apply(split, plan(millis));
            split = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(advanced.body()));
            releasedQi += advanced.releasedQi().totalQi();
        }
        var last = BodyTimeAdvancement.apply(split, plan(15000));
        assertEquals(whole.body().openingTask(), last.body().openingTask());
        assertEquals(whole.releasedQi().totalQi(), releasedQi + last.releasedQi().totalQi(), 1e-8);
        assertEquals(whole.body().totalQi(), last.body().totalQi(), 1e-8);
        assertEquals(whole.body().organStates().get(0).fatigueJoules(),
                last.body().organStates().get(0).fatigueJoules(), 1e-8);
    }

    @Test
    public void thresholdDepletionResetsBeforeCompletionAndStopsChargingWhilePreparing() {
        // 起点已有 2 元，门槛 1 元；一秒恰好跌至门槛，不能同毫秒完成。
        var consumingRule = rule().toBuilder().setMinimumLumenQi(BodyProtobuf.toInventory(free(1)))
                .setGuidanceQiPerSecond(0).setGuidanceBatchQi(0)
                .setPolicyConfig(SampleOpeningPolicy.configuration(1000, true, false)).setHoldQiPerSecond(1).build();
        BodySnapshot started = OpeningRuntime.start(body(), OpeningProtobuf.fromRule(consumingRule),
                ActionProtobuf.fromDefinition(action()));
        var whole = BodyTimeAdvancement.apply(started, plan(5000));
        assertEquals(OpeningTask.Status.PREPARING, whole.body().openingTask().status());
        assertEquals(0, whole.body().openingTask().heldMillis());
        assertEquals(1, whole.body().meridianStates().get(0).lumenQi().totalQi(), 1e-9);
        assertEquals(1, whole.releasedQi().totalQi(), 1e-9);
        assertEquals(started.totalQi(), whole.body().totalQi() + whole.releasedQi().totalQi(), 1e-9);
        var first = BodyTimeAdvancement.apply(started, plan(500));
        assertEquals(500, first.body().openingTask().heldMillis());
        var second = BodyTimeAdvancement.apply(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(first.body())), plan(5000));
        assertEquals(whole.body().openingTask(), second.body().openingTask());
        assertEquals(1, first.releasedQi().totalQi() + second.releasedQi().totalQi(), 1e-9);

        // 恰好等于门槛不开始计时，也不会消耗准备期真元。
        var exact = consumingRule.toBuilder().setMinimumLumenQi(BodyProtobuf.toInventory(free(2))).build();
        var waiting = OpeningRuntime.start(body(), OpeningProtobuf.fromRule(exact), ActionProtobuf.fromDefinition(action()));
        var unchanged = BodyTimeAdvancement.apply(waiting, plan(5000));
        assertEquals(0, unchanged.body().openingTask().heldMillis());
        assertEquals(0, unchanged.releasedQi().totalQi(), 0);
    }

    @Test
    public void commandGuidanceRestoreCompletionAndReplayConserveQi() {
        // 非人环路的未开经脉能够经专用任务汇集资源，普通主动运输仍受开拓门槛限制。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var now = new AtomicLong();
        var handler = handler(store, now);
        byte[] start = request(store, "start", BodyCommand.newBuilder().setStartMeridianOpening(
                OpeningMessages.StartMeridianOpening.newBuilder().setRuleId("sample:open").setRuleVersion(1)));
        byte[] response = handler.handle("owner", start);
        assertTrue(MeridianProtocol.decodeResponse(response).toString(), MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        assertArrayEquals(response, handler.handle("owner", start));
        now.set(800);
        committed(handler, store, "advance-a", BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance()));
        BodySnapshot during = current(store);
        assertTrue(during.openingTask().active());
        assertEquals(initial.totalQi(), during.totalQi(), 1e-9);
        assertTrue(during.organStates().get(0).fatigueJoules() > 0);
        assertTrue(during.tissueStates().get(0).fatigue() > 0);
        assertThrows(dev.kizuna.meridian.error.MeridianErrors.IntakeRejected.class,
                () -> BodyActions.stop(during, during.openingTask().actionInstanceId()));

        // 固定任务可经过 Protobuf 保存恢复，完成和释放在同一事务中发布。
        var restored = new InMemoryBodyStore(List.of(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(during))),
                store.receipts(), store.environmentQi());
        var resumed = handler(restored, now);
        now.set(10000);
        byte[] advance = request(restored, "finish", BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance()));
        byte[] done = resumed.handle("owner", advance);
        assertTrue(MeridianProtocol.decodeResponse(done).toString(), MeridianProtocol.decodeResponse(done).hasCommandCommitted());
        BodySnapshot completed = current(restored);
        assertEquals(OpeningTask.Status.COMPLETED, completed.openingTask().status());
        assertEquals(MeridianOpening.Stage.OPENED, completed.meridianStates().get(0).opening().stage());
        assertEquals(3, completed.structure().meridians().get(0).lumenCapacity(), 0);
        assertEquals(initial.totalQi(), completed.totalQi() + restored.environmentQi().totalQi(), 1e-9);
        assertEquals(3, restored.environmentQi().totalQi(), 1e-9);
        assertTrue(completed.actions().isEmpty());
        assertArrayEquals(done, resumed.handle("owner", advance));
        assertArrayEquals(response, resumed.handle("owner", start));
        assertEquals(completed, current(restored));

        // 分段次数不改变负荷、完成时刻和释放量。
        BodySnapshot started = OpeningRuntime.start(initial, OpeningProtobuf.fromRule(rule()), ActionProtobuf.fromDefinition(action()));
        var whole = BodyTimeAdvancement.apply(started, plan(10000));
        assertEquals(completed.structure(), whole.body().structure());
        assertEquals(completed.meridianStates(), whole.body().meridianStates());
        assertEquals(completed.organStates().get(0).fatigueJoules(), whole.body().organStates().get(0).fatigueJoules(), 1e-9);
        assertEquals(3, whole.releasedQi().totalQi(), 1e-9);
        assertThrows(dev.kizuna.meridian.error.MeridianErrors.IntakeRejected.class,
                () -> OpeningRuntime.start(completed, OpeningProtobuf.fromRule(rule()), ActionProtobuf.fromDefinition(action())));
    }

    @Test
    public void supplyWindowRequiresActualIntakeAndResetsInterruptedHold() {
        // 器官已有缓存不算新摄入；只用成功环境摄入算子的证据启动连续计时。
        var supplyRule = rule().toBuilder().setPolicyConfig(SampleOpeningPolicy.configuration(2000, false, true)).build();
        BodySnapshot started = OpeningRuntime.start(body(), OpeningProtobuf.fromRule(supplyRule), ActionProtobuf.fromDefinition(action()));
        BodySnapshot waiting = BodyTimeAdvancement.apply(started, plan(4000)).body();
        assertEquals(0, waiting.openingTask().heldMillis());
        assertEquals(OpeningTask.Status.PREPARING, waiting.openingTask().status());
        BodySnapshot supplied = OrganIntake.apply(waiting, new Anatomy.OrganId("filter"), free(.2), plan(4100)).body();
        BodySnapshot held = BodyTimeAdvancement.apply(supplied, plan(4500)).body();
        assertEquals(400, held.openingTask().heldMillis());
        BodySnapshot expired = BodyTimeAdvancement.apply(held, plan(5100)).body();
        assertEquals(0, expired.openingTask().heldMillis());
        assertEquals(OpeningTask.Status.PREPARING, expired.openingTask().status());
        assertTrue(expired.organStates().get(0).buffer().totalQi() > 0);

        // 暂停清理临时超压，停止扣费；独立器官耐力随后按指数恢复。
        var paused = OpeningRuntime.pause(expired, new Anatomy.MeridianId("channel"));
        assertEquals(OpeningTask.Status.PAUSED, paused.body().openingTask().status());
        assertEquals(expired.totalQi(), paused.body().totalQi() + paused.releasedQi().totalQi(), 1e-9);
        var rested = BodyTimeAdvancement.apply(paused.body(), plan(15100)).body();
        assertEquals(paused.body().organStates().get(0).fatigueJoules() / 2, rested.organStates().get(0).fatigueJoules(), 1e-9);
    }

    @Test
    public void exhaustionFreezeAndDeathCannotGrantFreeProgress() {
        // 长时间推进准确停在预算边界，不能用超长区间透支耐力后宣告成功。
        var longRule = OpeningProtobuf.fromRule(rule().toBuilder()
                .setPolicyConfig(SampleOpeningPolicy.configuration(100000, false, false)).build());
        BodySnapshot started = OpeningRuntime.start(body(), longRule, ActionProtobuf.fromDefinition(action()));
        var exhausted = BodyTimeAdvancement.apply(started, plan(100000));
        assertEquals(OpeningTask.Status.EXHAUSTED, exhausted.body().openingTask().status());
        assertEquals(2, exhausted.body().structure().meridians().get(0).lumenCapacity(), 0);
        assertEquals(started.totalQi(), exhausted.body().totalQi() + exhausted.releasedQi().totalQi(), 1e-9);
        var frozen = BodyTimeAdvancement.apply(started, new BodyTimeAdvancement.Plan(plan(100000).target(), 0,
                BodyTimeAdvancement.Mode.LIFESPAN_ONLY));
        assertEquals(OpeningTask.Status.PAUSED, frozen.body().openingTask().status());
        assertEquals(0, frozen.body().openingTask().heldMillis());
        assertEquals(0, frozen.body().organStates().get(0).fatigueJoules(), 0);

        // 死亡优先于同毫秒的适应完成，并释放所有在途、器官、内腔与外壁明细。
        var message = BodyProtobuf.toMessage(started);
        BodySnapshot mortal = BodyProtobuf.fromMessage(message.toBuilder()
                .setVitality(message.getVitality().toBuilder().setLifespanLimitSeconds(1)).build());
        var death = BodyTimeAdvancement.apply(mortal, new BodyTimeAdvancement.Plan(plan(100000).target(), 1, BodyTimeAdvancement.Mode.ACTIVE));
        assertTrue(death.died());
        assertEquals(0, death.body().totalQi(), 0);
        assertEquals(started.totalQi(), death.releasedQi().totalQi(), 1e-9);
        assertTrue(death.body().actions().isEmpty());
        assertNull(death.body().openingTask());
    }

    private static BodySnapshot body() {
        // 两条经脉组成有向循环；输入器官和控制组织使用非人体身份。
        var base = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI)).toBuilder();
        var structure = base.getStructure().toBuilder().clearConnections().clearMeridians();
        for (String id : List.of("channel", "supply")) {
            structure.addMeridians(BodyMessages.MeridianDefinition.newBuilder().setId(id).setDisplayName(id)
                    .addPartIds("body").setLumenCapacity(id.equals("channel") ? 2 : 10).setWallCapacity(0)
                    .setLumenLiquidReference(100).setWallLiquidReference(0).setThroughputPerSecond(4)
                    .setResilience(1).setFatigueCapacity(10)
                    .addPorts(BodyMessages.Port.newBuilder().setId(id + ".port").setDirection(BodyMessages.PortDirection.BIDIRECTIONAL)));
        }
        structure.addConnections(BodyMessages.Connection.newBuilder().setId("supply.channel").setFrom("supply.port")
                .setTo("channel.port").setResistance(0).setDistance(.01));
        structure.addConnections(BodyMessages.Connection.newBuilder().setId("channel.supply").setFrom("channel.port")
                .setTo("supply.port").setResistance(0).setDistance(.01));
        structure.setOrgans(0, structure.getOrgans(0).toBuilder()
                .setEndurance(BodyMessages.OrganEndurance.newBuilder().setEnduranceJoules(100).setMaximumWatts(20).setRecoveryHalfLifeSeconds(10))
                .setIntake(BodyMessages.OrganIntakeCapability.newBuilder().setMaxQiPerSecond(10)));
        base.setStructure(structure).clearTransits().clearMeridianStates().setRevision(0)
                .setSettledAt(base.getSettledAt().toBuilder().setElapsedMillis(0))
                .setVitality(base.getVitality().toBuilder().setAgeSeconds(0).setSpentLifespanSeconds(0));
        for (String id : List.of("channel", "supply")) {
            base.addMeridianStates(BodyMessages.MeridianState.newBuilder().setId(id).setFatigue(0)
                    .setLumenQi(BodyProtobuf.toInventory(free(id.equals("channel") ? 2 : 8)))
                    .setWallQi(BodyMessages.QiInventory.getDefaultInstance()).setCondition(BodyMessages.MeridianCondition.INTACT)
                    .setOpening(BodyMessages.MeridianOpening.newBuilder().setStage(BodyMessages.MeridianOpeningStage.UNOPENED).setProgress(0)));
        }
        base.setPartStates(0, base.getPartStates(0).toBuilder().setHp(100));
        base.setTissueStates(0, base.getTissueStates(0).toBuilder().setHp(50).setFatigue(0));
        base.setOrganStates(0, base.getOrganStates(0).toBuilder().setHp(30));
        return BodyProtobuf.fromMessage(base.build());
    }

    private static BodyMessages.MeridianOpeningRule rule() {
        // 小型规则覆盖超压和永久扩容，产品数值由附属配置。
        return BodyMessages.MeridianOpeningRule.newBuilder().setId("sample:open").setVersion(1).setDisplayName("环路开拓")
                .setTargetMeridianId("channel").setMinimumLumenQi(BodyProtobuf.toInventory(free(6)))
                .setPolicyId("sample:opening").setPolicyConfig(SampleOpeningPolicy.configuration(1000, false, false))
                .setActionId("sample:focus").setActionVersion(1)
                .setContinuousHold(true).setLumenCapacityGain(1)
                .setGuidanceQiPerSecond(2).setGuidanceBatchQi(1)
                .addEfforts(BodyMessages.OpeningEffort.newBuilder().setOrganId("filter").setWatts(2))
                .addEfforts(BodyMessages.OpeningEffort.newBuilder().setTissueId("tendril").setWatts(1)).build();
    }

    private static BodyMessages.ActionDefinition action() {
        // 摄入和独立成本都必须包含在可信动作的占用声明里。
        return BodyMessages.ActionDefinition.newBuilder().setId("sample:focus").setVersion(1).setDisplayName("引导")
                .addOrganIds("filter").addTissueIds("tendril").build();
    }

    private static QiInventory free(double amount) {
        // 测试资源使用唯一属性，数量仍由真实账户搬运。
        return new QiInventory(List.of(new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, amount)));
    }

    private static BodyTimeAdvancement.Plan plan(long millis) {
        // 使用同一模拟时钟，关闭老化仅便于隔离运行成本。
        return new BodyTimeAdvancement.Plan(new TimeCursor(new DefinitionId("sample:world"), "epoch-1", millis), 0,
                BodyTimeAdvancement.Mode.ACTIVE);
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, AtomicLong now) {
        // 真实命令入口、不可变内容目录和事务复算共用这一组测试宿主依赖。
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller), (caller, command, body) -> true,
                (caller, body) -> TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(plan(now.get()).target()))
                        .setBiologicalSecondsPerSimulationSecond(0).setMode(TimeAdvanceMode.ACTIVE).build(), store,
                (caller, receipt, before, after) -> { /* 原子结果由测试读取。 */ }, new OrganRecipeCatalog(List.of()),
                (caller, body, plan) -> Optional.empty(), new ActionCatalog(List.of(action())), new MeridianOpeningCatalog(List.of(rule())));
    }

    private static BodySnapshot current(InMemoryBodyStore store) {
        // 查询当前唯一权威版本。
        return store.find(new BodyId("creature-1")).orElseThrow();
    }

    private static byte[] request(InMemoryBodyStore store, String id, BodyCommand.Builder command) {
        // 新请求引用当前 revision；重试复用原字节以保护幂等契约。
        return MeridianProtocol.encodeRequest(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(id)
                .setBodyCommand(command.setBodyId("creature-1").setExpectedRevision(current(store).revision())).build());
    }

    private static void committed(BodyCommandHandler<String> handler, InMemoryBodyStore store, String id, BodyCommand.Builder command) {
        // 业务拒绝必须让测试失败，不能把没有异常视作成功执行。
        var response = MeridianProtocol.decodeResponse(handler.handle("owner", request(store, id, command)));
        assertTrue(response.toString(), response.hasCommandCommitted());
    }
}
