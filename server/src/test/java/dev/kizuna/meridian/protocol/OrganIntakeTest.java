package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.*;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/** 整体回归：公开摄入契约、共享来源、恢复与两本账的原子性。 */
public class OrganIntakeTest {
    @Test
    public void intakeLoadsPreserveOtherTissuesRecoveryAndRollbackTogether() {
        // 同一段摄入里，实际做功组织只承担代价，另一块休息组织必须保留恢复结果。
        var message = BodyProtobuf.toMessage(body("a", 0));
        var working = message.getStructure().getTissues(0).toBuilder().setRecoveryHalfLifeSeconds(1);
        var resting = working.clone().setId("resting").clearPorts();
        BodySnapshot initial = BodyProtobuf.fromMessage(message.toBuilder()
                .setStructure(message.getStructure().toBuilder().setTissues(0, working).addTissues(resting))
                .addTissueStates(message.getTissueStates(0).toBuilder().setId("resting")).build());
        var store = store(List.of(initial));
        var intake = MeridianProtocol.decodeRequest(request("a", 0, "work-intake", wood(1))).toBuilder();
        intake.setBodyCommand(intake.getBodyCommand().toBuilder().setIntakeQi(intake.getBodyCommand().getIntakeQi().toBuilder()
                .addLoads(ExertTissue.newBuilder().setTissueId("tendril").setForceNewtons(0)
                        .setPowerWatts(40).setDurationSeconds(1))));
        byte[] payload = MeridianProtocol.encodeRequest(intake.build());
        BodyTimeSource<String> time = (context, body) -> plan(1000);
        var handler = new BodyCommandHandler<String>((context, command) -> Optional.of(context),
                (context, command, body) -> true, time, store, (context, receipt, before, after) -> { },
                new OrganRecipeCatalog(List.of()), (context, body, plan) -> Optional.of("region"));
        committed(handler.handle("owner", payload));
        BodySnapshot after = store.find(initial.id()).orElseThrow();
        assertEquals(3, after.tissueStates().get(0).fatigue(), 1e-10);
        assertEquals(1, after.tissueStates().get(1).fatigue(), 0);
        assertEquals(plan(1000), receipt(store, "a", "work-intake").getOrganIntake().getPlan());
        committed(handler.handle("owner", payload));
        assertEquals(after, store.find(initial.id()).orElseThrow());
        assertEquals(100, after.totalQi() + sourceTotal(store), 0);

        // 超额负载使整笔回滚，包括本来能够恢复的组织和已算出的环境摄入。
        var failedStore = store(List.of(initial));
        var badIntake = intake.clone();
        badIntake.setBodyCommand(badIntake.getBodyCommand().toBuilder().setIntakeQi(
                badIntake.getBodyCommand().getIntakeQi().toBuilder().setLoads(0,
                        ExertTissue.newBuilder().setTissueId("tendril").setForceNewtons(0)
                                .setPowerWatts(1000).setDurationSeconds(1))));
        var failedHandler = new BodyCommandHandler<String>((context, command) -> Optional.of(context),
                (context, command, body) -> true, time, failedStore, (context, receipt, before, next) -> { },
                new OrganRecipeCatalog(List.of()), (context, body, plan) -> Optional.of("region"));
        failure(failedHandler.handle("owner", MeridianProtocol.encodeRequest(badIntake.build())), FailureCode.TISSUE_LOAD_EXCEEDED);
        assertEquals(initial, failedStore.find(initial.id()).orElseThrow());
        assertEquals(source(), source(failedStore));
    }

    @Test
    public void intakeWithoutMeridiansPreservesKindsAndReplaysAfterRestore() {
        // 非人且没有经脉的构型也能摄入；快照往返保存附属包声明的能力。
        BodySnapshot initial = body("a", 0);
        assertEquals(initial, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(initial)));
        var store = store(List.of(initial));
        QiInventory incoming = new QiInventory(List.of(qi(Qi.QiAttribute.WOOD, 1), qi(Qi.QiAttribute.EARTH, 1)));
        byte[] request = request("a", 0, "first", incoming);
        byte[] response = handler(store, 1000, true).handle("owner", request);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        BodySnapshot after = store.find(initial.id()).orElseThrow();
        assertEquals(incoming, after.organStates().get(0).buffer());
        assertEquals(initial.vitality().ageSeconds() + 1, after.vitality().ageSeconds(), 0);
        assertEquals(1000, after.settledAt().elapsedMillis());
        assertEquals(1, after.revision());
        assertEquals(100, after.totalQi() + sourceTotal(store), 0);
        assertEquals(QiInventory.empty(), store.environmentQi());

        // 新 ID 不能重用同一身体时间；已成功 ID 在源窗口过期后仍直接返回原提交。
        failure(handler(store, 1000, true).handle("owner", request("a", 1, "again", wood(1))), FailureCode.TIME_NOT_ADVANCED);
        var receipt = receipt(store, "a", "first");
        var savedSource = EnvironmentProtobuf.decodeSource(EnvironmentProtobuf.encodeSource(source(store)));
        var savedReceipt = MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt));
        var restored = new InMemoryBodyStore(List.of(after), List.of(savedReceipt), QiInventory.empty(), List.of(savedSource));
        assertArrayEquals(response, handler(restored, 50_000, true).handle("owner", request));
        assertEquals(savedSource, source(restored));
        assertEquals(after, restored.find(initial.id()).orElseThrow());
        failure(handler(restored, 1000, true).handle("owner", request("a", 0, "first", wood(1))), FailureCode.REQUEST_ID_CONFLICT);
        assertThrows(IllegalArgumentException.class, () -> new InMemoryBodyStore(List.of(after), List.of(savedReceipt)));
    }

    @Test
    public void competingBodiesShareOverlappingTimeBudgetAndRenewWithoutRefill() throws Exception {
        // 两个身体并发申请各三元，而同一秒只有四元带宽，只允许一个整笔成功。
        var store = store(List.of(body("a", 0), body("b", 0), body("c", 500)));
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                // 同步起跑让两条请求真正争用同一来源锁。
                start.await();
                return handler(store, 1000, true).handle("owner", request("a", 0, "a", wood(3)));
            });
            var second = executor.submit(() -> {
                // 身体身份不同，不能依赖同身体 revision 冲突来保护环境。
                start.await();
                return handler(store, 1000, true).handle("owner", request("b", 0, "b", wood(3)));
            });
            start.countDown();
            var firstResult = MeridianProtocol.decodeResponse(first.get());
            var secondResult = MeridianProtocol.decodeResponse(second.get());
            assertNotEquals(firstResult.hasCommandCommitted(), secondResult.hasCommandCommitted());
            var failed = firstResult.hasFailure() ? firstResult : secondResult;
            assertEquals(FailureCode.ENVIRONMENT_BUDGET_EXCEEDED, failed.getFailure().getCode());
            String winner = firstResult.hasCommandCommitted() ? "a" : "b";
            String loser = winner.equals("a") ? "b" : "a";
            committed(handler(store, 1000, true).handle("owner", request(loser, 0, "remaining", wood(1))));
            assertEquals(4, source(store).getUsage(0).getQiPerSecond(), 0);
            assertEquals(96, sourceTotal(store), 0);

            // [0.5,1.5) 虽然只有半秒重叠，也不能占用已满的 [0.5,1) 带宽。
            failure(handler(store, 1500, true).handle("owner", request("c", 0, "overlap", wood(1))),
                    FailureCode.ENVIRONMENT_BUDGET_EXCEEDED);
            committed(handler(store, 2000, true).handle("owner", request(winner, 1, "later", wood(4))));
            assertEquals(1, source(store).getUsageCount());
            assertEquals(2000, source(store).getUsage(0).getEndedMillis());

            // 续窗必须越过旧窗口末端，保留库存；新速率由新浓度重新计算。
            EnvironmentQiSource previous = source(store);
            assertThrows(MeridianErrors.IntakeRejected.class, () -> store.renewEnvironmentWindow("region", previous.getRevision(),
                    time(1000), time(3000), rules()));
            store.renewEnvironmentWindow("region", previous.getRevision(), time(2000), time(4000), rules());
            assertEquals(92, sourceTotal(store), 0);
            assertEquals(92 / 25.0, source(store).getMaxAbsorptionPerSecond(), 0);
            assertEquals(0, source(store).getUsageCount());
            failure(handler(store, 3000, true).handle("owner", request("c", 0, "expired", wood(1))),
                    FailureCode.ENVIRONMENT_INTERVAL_INVALID);
        } finally {
            // 测试线程不延续到其他整体回归。
            executor.shutdownNow();
        }
    }

    @Test
    public void invalidCapabilityCapacityTimeAndHostPolicyLeaveBothAccountsUntouched() {
        // 每个拒绝都通过真实处理器，不只验证纯算子的异常。
        var initial = BodyProtobuf.toMessage(body("a", 0));
        var missingCapability = initial.toBuilder().setStructure(initial.getStructure().toBuilder()
                .setOrgans(0, initial.getStructure().getOrgans(0).toBuilder().clearIntake())).build();
        assertRejected(BodyProtobuf.fromMessage(missingCapability), wood(1), FailureCode.INTAKE_UNSUPPORTED);
        var damaged = initial.toBuilder().setOrganStates(0, initial.getOrganStates(0).toBuilder().setHp(0)).build();
        assertRejected(BodyProtobuf.fromMessage(damaged), wood(1), FailureCode.MODULE_UNAVAILABLE);
        var full = initial.toBuilder().setOrganStates(0, initial.getOrganStates(0).toBuilder()
                .setBuffer(BodyProtobuf.toInventory(wood(20)))).build();
        assertRejected(BodyProtobuf.fromMessage(full), wood(1), FailureCode.CAPACITY_EXCEEDED);
        assertRejected(body("a", 0), wood(5), FailureCode.THROUGHPUT_EXCEEDED);
        assertRejected(body("a", 0), new QiInventory(List.of(qi(Qi.QiAttribute.FIRE, 1))), FailureCode.INSUFFICIENT_QI);

        var store = store(List.of(body("a", 0)));
        failure(handler(store, 1000, false).handle("owner", request("a", 0, "denied", wood(1))), FailureCode.ACCESS_DENIED);
        var frozen = new BodyCommandHandler<String>((context, command) -> Optional.of(context), (context, command, body) -> true,
                (context, body) -> plan(1000).toBuilder().setMode(TimeAdvanceMode.LIFESPAN_ONLY).build(), store,
                (context, receipt, before, after) -> { }, new OrganRecipeCatalog(List.of()),
                (context, body, plan) -> Optional.of("region"));
        failure(frozen.handle("owner", request("a", 0, "frozen", wood(1))), FailureCode.TRANSPORT_FROZEN);
        var noSource = new BodyCommandHandler<String>((context, command) -> Optional.of(context), (context, command, body) -> true,
                (context, body) -> plan(1000), store, (context, receipt, before, after) -> { });
        failure(noSource.handle("owner", request("a", 0, "no-source", wood(1))), FailureCode.ENVIRONMENT_UNAVAILABLE);
        assertEquals(body("a", 0), store.find(new BodyId("a")).orElseThrow());
        assertEquals(source(), source(store));
    }

    @Test
    public void endBoundaryProcessingReleasesToSelectedSourceOnly() {
        // 固定配方在摄入区间末端完成，损耗与摄入共用一次环境提交，不再进入默认释放账户。
        var message = BodyProtobuf.toMessage(body("a", 0));
        BodySnapshot supplied = BodyProtobuf.fromMessage(message.toBuilder().setOrganStates(0,
                message.getOrganStates(0).toBuilder().setBuffer(BodyProtobuf.toInventory(wood(2)))).build());
        var recipe = new OrganRecipe(new DefinitionId("sample:refine"), 1, new DefinitionId("sample:filter"),
                wood(2), new QiInventory(List.of(qi(Qi.QiAttribute.NONE, 1))), 1000, Realm.WANDERING_QI);
        BodySnapshot processing = OrganProcessing.start(supplied, new Anatomy.OrganId("filter"), recipe);
        var store = store(List.of(processing));
        failure(handler(store, 1500, true).handle("owner", request("a", 1, "cross-event", wood(1))), FailureCode.PENDING_TIME_EVENT);
        committed(handler(store, 1000, true).handle("owner", request("a", 1, "end-event", wood(1))));
        BodySnapshot after = store.find(processing.id()).orElseThrow();
        assertNull(after.organStates().get(0).process());
        assertEquals(2, after.organQiTotal(), 0);
        assertEquals(100, sourceTotal(store), 0);
        assertEquals(QiInventory.empty(), store.environmentQi());
        var receipt = receipt(store, "a", "end-event");
        assertFalse(receipt.hasTimeAdvance());
        assertEquals(1, BodyProtobuf.fromInventory(receipt.getOrganIntake().getEnvironment().getExchange().getReleasedQi()).totalQi(), 0);

        // 寿尽优先于摄入，调用者必须先执行时间死亡结算。
        var mortal = BodyProtobuf.fromMessage(message.toBuilder().setVitality(message.getVitality().toBuilder()
                .setLifespanLimitSeconds(16)).build());
        assertRejected(mortal, wood(1), FailureCode.PENDING_TIME_EVENT);
    }

    @Test
    public void reservedCargoAndProcessingInputCannotBeOverwrittenByIntake() {
        // 经脉尚未开拓也不妨碍器官摄入；已预约的到达与加工原料仍占器官空间。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var base = BodyProtobuf.toMessage(body("a", 0));
        var structure = original.getStructure().toBuilder().setOrgans(0,
                base.getStructure().getOrgans(0).toBuilder().setQiCapacity(4));
        var meridian = original.getMeridianStates(0).toBuilder()
                .setCondition(BodyMessages.MeridianCondition.INTACT)
                .setOpening(BodyMessages.MeridianOpening.newBuilder().setStage(BodyMessages.MeridianOpeningStage.UNOPENED).setProgress(0));
        var routed = BodyProtobuf.fromMessage(base.toBuilder().setStructure(structure).addMeridianStates(meridian)
                .addTransits(BodyMessages.QiTransit.newBuilder().setId("reserved").setConnectionId("channel.organ")
                        .setCargo(BodyProtobuf.toInventory(wood(3))).setThroughputPerSecond(1).setArrivalMillis(1500)
                        .setStatus(BodyMessages.TransitStatus.MOVING)).build());
        assertRejected(routed, wood(2), FailureCode.CAPACITY_EXCEEDED);
        var store = store(List.of(routed));
        committed(handler(store, 1000, true).handle("owner", request("a", 0, "open-independent", wood(1))));
        assertEquals(MeridianOpening.Stage.UNOPENED, store.find(routed.id()).orElseThrow().meridianStates().get(0).opening().stage());
        assertEquals(routed.transits(), store.find(routed.id()).orElseThrow().transits());

        // 摄入跨越内部运输到达时仍一次消费整个区间，预约由实际到货接替，双方账本保持守恒。
        var crossing = store(List.of(routed));
        committed(handler(crossing, 2000, true).handle("owner", request("a", 0, "cross-arrival", wood(1))));
        BodySnapshot received = crossing.find(routed.id()).orElseThrow();
        assertTrue(received.transits().isEmpty());
        assertEquals(4, received.organQiTotal(), 0);
        assertEquals(routed.totalQi() + 1, received.totalQi(), 1e-12);
        assertEquals(99, sourceTotal(crossing), 0);
        assertEquals(2000, received.settledAt().elapsedMillis());

        // 已进入加工账户的原料不能被摄入视作腾出的缓存空间。
        var withInput = base.toBuilder().setStructure(base.getStructure().toBuilder().setOrgans(0,
                base.getStructure().getOrgans(0).toBuilder().setQiCapacity(4)))
                .setOrganStates(0, base.getOrganStates(0).toBuilder().setBuffer(BodyProtobuf.toInventory(wood(3)))).build();
        var recipe = new OrganRecipe(new DefinitionId("sample:slow"), 1, new DefinitionId("sample:filter"),
                wood(3), wood(2), 2000, Realm.WANDERING_QI);
        BodySnapshot processing = OrganProcessing.start(BodyProtobuf.fromMessage(withInput), new Anatomy.OrganId("filter"), recipe);
        assertRejected(processing, wood(2), FailureCode.CAPACITY_EXCEEDED);
    }

    @Test
    public void segmentedIntakeMatchesOneBatchAndPersistsSharedUsage() {
        // 同一固定窗口内按同速率分段不改变资源与年龄结果，只多产生一次提交版本。
        BodySnapshot initial = body("a", 0);
        var single = store(List.of(initial));
        var split = store(List.of(initial));
        committed(handler(single, 2000, true).handle("owner", request("a", 0, "single", wood(4))));
        committed(handler(split, 1000, true).handle("owner", request("a", 0, "first", wood(2))));
        var firstReceipt = receipt(split, "a", "first");
        var restored = new InMemoryBodyStore(List.of(split.find(initial.id()).orElseThrow(), body("b", 0)),
                List.of(firstReceipt), QiInventory.empty(), List.of(EnvironmentProtobuf.decodeSource(EnvironmentProtobuf.encodeSource(source(split)))));
        // 恢复后不能丢掉上一秒已有的两元带宽，即使另一个身体尚未推进。
        failure(handler(restored, 1000, true).handle("owner", request("b", 0, "over", wood(3))), FailureCode.ENVIRONMENT_BUDGET_EXCEEDED);
        committed(handler(restored, 2000, true).handle("owner", request("a", 1, "second", wood(2))));
        var expectedBody = BodyProtobuf.toMessage(single.find(initial.id()).orElseThrow());
        var actualBody = BodyProtobuf.toMessage(restored.find(initial.id()).orElseThrow()).toBuilder().setRevision(1).build();
        assertEquals(expectedBody, actualBody);
        assertEquals(source(single), source(restored).toBuilder().setRevision(1).build());
    }

    @Test
    public void stagedFailureAndForgedReceiptsCannotPartiallyPublish() {
        // 从正常提交取得一份真实候选，在新存储上验证暂存后失败的回滚边界。
        BodySnapshot initial = body("a", 0);
        var completed = store(List.of(initial));
        committed(handler(completed, 1000, true).handle("owner", request("a", 0, "ok", wood(2))));
        BodySnapshot after = completed.find(initial.id()).orElseThrow();
        var receipt = receipt(completed, "a", "ok");
        var store = store(List.of(initial));
        assertThrows(IllegalStateException.class, () -> store.transact(initial.id(), tx -> {
            // 回调抛错时暂存的身体、来源库存、预算和回执应一起丢弃。
            tx.stage(after, receipt);
            throw new IllegalStateException("before publish");
        }));
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertEquals(source(), source(store));
        assertTrue(store.transact(initial.id(), tx -> tx.receipt("owner", "ok").isEmpty()));

        // 伪造环境扣账或未来来源版本时，不得只凭新身体看起来合法就提交。
        var fact = receipt.getOrganIntake();
        var exchange = fact.getEnvironment().getExchange();
        var forged = receipt.toBuilder().setOrganIntake(fact.toBuilder().setEnvironment(fact.getEnvironment().toBuilder()
                .setSourceRevision(5).setExchange(exchange.toBuilder().setOffer(exchange.getOffer().toBuilder().setSourceRevision(4))))).build();
        assertThrows(IllegalStateException.class, () -> store.transact(initial.id(), tx -> {
            // 此回执自身字段关系合法，但与事务锁定的来源不符。
            tx.stage(after, forged);
            return null;
        }));
        assertEquals(source(), source(store));
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeReceipt(receipt.toBuilder().clearOrganIntake().build()));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentProtobuf.decodeSource(source().toBuilder().clearRevision().build().toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> request("a", 0, "empty", QiInventory.empty()));
    }

    private static void assertRejected(BodySnapshot body, QiInventory incoming, FailureCode code) {
        // 失败必须既不改变身体，也不消费区域库存、区间预算或成功回执。
        var store = store(List.of(body));
        failure(handler(store, 1000, true).handle("owner", request(body.id().value(), body.revision(), "rejected", incoming)), code);
        assertEquals(body, store.find(body.id()).orElseThrow());
        assertEquals(source(), source(store));
        assertTrue(store.transact(body.id(), tx -> tx.receipt("owner", "rejected").isEmpty()));
    }

    private static BodySnapshot body(String id, long startMillis) {
        // 合成构型不安装人体内容，也没有经脉；所有能力显式声明。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var organ = original.getStructure().getOrgans(0).toBuilder()
                .setIntake(BodyMessages.OrganIntakeCapability.newBuilder().setMaxQiPerSecond(4));
        return BodyProtobuf.fromMessage(original.toBuilder().setId(id).setRevision(0).setSettledAt(time(startMillis))
                .clearTransits().clearMeridianStates().setStructure(original.getStructure().toBuilder()
                        .clearMeridians().clearConnections().setOrgans(0, organ)).build());
    }

    private static EnvironmentQiRules rules() {
        // 默认采集体积一格每秒，测试区域浓度四元每格。
        return new EnvironmentQiRules(EnvironmentConfigLoader.defaults());
    }

    private static EnvironmentQiSource source() {
        // 初始库存只创建一次，供给速率由浓度推导，窗口内不重抽也不补满。
        var space = EnvironmentSpace.newBuilder().setProtocolVersion(1).setSourceId("region")
                .setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(25).build();
        var inventory = new QiInventory(List.of(qi(Qi.QiAttribute.WOOD, 60), qi(Qi.QiAttribute.EARTH, 40)));
        return EnvironmentAbsorption.initialize(space, inventory, time(0), time(2000), rules());
    }

    private static InMemoryBodyStore store(List<BodySnapshot> bodies) {
        // 存储同时恢复来源与身体，避免用 offer 代替真实库存。
        return new InMemoryBodyStore(bodies, List.of(), QiInventory.empty(), List.of(source()));
    }

    private static EnvironmentQiSource source(InMemoryBodyStore store) {
        // 查询完整状态才能同时断言库存、版本和预算。
        return store.environmentSource("region").orElseThrow();
    }

    private static double sourceTotal(InMemoryBodyStore store) {
        // 从唯一明细派生总量，不另存环境余额。
        return BodyProtobuf.fromInventory(source(store).getInventory()).totalQi();
    }

    private static CommandReceipt receipt(InMemoryBodyStore store, String bodyId, String requestId) {
        // 回执使用可信调用者、身体和请求 ID 共同定位。
        return store.transact(new BodyId(bodyId), tx -> tx.receipt("owner", requestId).orElseThrow());
    }

    private static Qi qi(Qi.QiAttribute attribute, double amount) {
        // 摄入不会把属性自动抹成无属性，测试保持游离态五行原料。
        return new Qi(Qi.QiPhase.FREE, attribute, amount);
    }

    private static QiInventory wood(double amount) {
        // 为断言提供单类原料，混合原料在端到端用例显式构造。
        return new QiInventory(List.of(qi(Qi.QiAttribute.WOOD, amount)));
    }

    private static BodyMessages.TimeCursor time(long millis) {
        // 所有主体与来源共享同一可信时钟世代。
        return BodyMessages.TimeCursor.newBuilder().setDomain("sample:world").setEpoch("epoch-1").setElapsedMillis(millis).build();
    }

    private static TimeAdvancePlan plan(long millis) {
        // 老化倍率设为一，保证摄入也实际推进寿命账。
        return TimeAdvancePlan.newBuilder().setTarget(time(millis)).setBiologicalSecondsPerSimulationSecond(1)
                .setMode(TimeAdvanceMode.ACTIVE).build();
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, long millis, boolean allow) {
        // 宿主确认整个稳定区间的来源，客户端无法选择来源、时间或库存。
        return new BodyCommandHandler<>((context, command) -> Optional.of(context), (context, command, body) -> allow,
                (context, body) -> plan(millis), store, (context, receipt, before, after) -> { },
                new OrganRecipeCatalog(List.of()), (context, body, plan) -> Optional.of("region"));
    }

    private static byte[] request(String body, long revision, String id, QiInventory incoming) {
        // 经过公开编码器，所有测试同时覆盖新增命令分支和剂量校验。
        return MeridianProtocol.encodeRequest(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(id)
                .setBodyCommand(BodyCommand.newBuilder().setBodyId(body).setExpectedRevision(revision)
                        .setIntakeQi(IntakeQi.newBuilder().setOrganId("filter").setQi(BodyProtobuf.toInventory(incoming)))).build());
    }

    private static void committed(byte[] response) {
        // 成功必须由协议回执确认，不能仅依赖查询到的余额。
        assertTrue(MeridianProtocol.decodeResponse(response).toString(), MeridianProtocol.decodeResponse(response).hasCommandCommitted());
    }

    private static void failure(byte[] response, FailureCode code) {
        // 公开失败码是对接契约，测试不依赖内部诊断文本。
        assertEquals(code, MeridianProtocol.decodeResponse(response).getFailure().getCode());
    }
}
