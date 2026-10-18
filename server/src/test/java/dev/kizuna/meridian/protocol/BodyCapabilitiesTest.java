package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.*;
import org.junit.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static dev.kizuna.meridian.core.BodyTimeAdvancement.Mode.ACTIVE;
import static dev.kizuna.meridian.core.BodyTimeAdvancement.Mode.LIFESPAN_ONLY;

/** 保护跨模组可见能力、输入/加工/输出归属以及事务守恒。 */
public class BodyCapabilitiesTest {
    private static final Anatomy.OrganId ORGAN = new Anatomy.OrganId("filter");
    private static final Anatomy.TissueId TISSUE = new Anatomy.TissueId("tendril");

    @Test
    public void transportProcessingAndReturnUseOneLedgerAcrossSaveAndAddonRemoval() {
        // 从经脉发料，经过真实协议入口启动加工，再保存恢复到没有内容包的宿主。
        var initial = body(0);
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var now = new AtomicLong(1000);
        var handler = handler(store, now, catalog(), true);
        committed(handler.handle("host", transport("feed", 0, "channel.organ", qi(4))));
        assertEquals(10, store.find(initial.id()).orElseThrow().totalQi(), 0);
        assertEquals(0, store.find(initial.id()).orElseThrow().organQiTotal(), 0);
        now.set(7000);
        committed(handler.handle("host", advance("arrive", 1)));
        assertEquals(4, store.find(initial.id()).orElseThrow().organStates().get(0).buffer().totalQi(), 0);

        var start = start("start", 2, "sample:fire", 1);
        var response = handler.handle("host", start);
        committed(response);
        var processing = store.find(initial.id()).orElseThrow();
        assertEquals(0, processing.organStates().get(0).buffer().totalQi(), 0);
        assertEquals(4, processing.organQiTotal(), 0);
        assertEquals(9000, BodyTimeAdvancement.nextEventBoundary(processing, 0, ACTIVE).orElseThrow().elapsedMillis());
        var receipt = store.transact(initial.id(), tx -> tx.receipt("host", "start").orElseThrow());
        var restored = new InMemoryBodyStore(List.of(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(processing))),
                List.of(MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt))));
        var withoutAddon = handler(restored, now, new OrganRecipeCatalog(List.of()), true);
        assertArrayEquals(response, withoutAddon.handle("host", start));

        // 已启动配方无需再次查目录；损耗与产物必须在同一事务发布且重试只发一次。
        now.set(9000);
        var finish = advance("finish", 3);
        committed(withoutAddon.handle("host", finish));
        committed(withoutAddon.handle("host", finish));
        assertEquals(1, restored.environmentQi().totalQi(), 0);
        var after = restored.find(initial.id()).orElseThrow();
        assertNull(after.organStates().get(0).process());
        assertEquals(fire(3), after.organStates().get(0).buffer());
        assertEquals(initial.totalQi(), after.totalQi() + restored.environmentQi().totalQi(), 0);
        committed(withoutAddon.handle("host", transport("return", 4, "organ.channel", fire(3))));
        now.set(11_500);
        committed(withoutAddon.handle("host", advance("return-arrive", 5)));
        assertEquals(9, restored.find(initial.id()).orElseThrow().lumenQiTotal(), 0);
        assertEquals(0, restored.find(initial.id()).orElseThrow().organQiTotal(), 0);
    }

    @Test
    public void tissueQueryAndExertionRespectOwnHealthFatigueAndUnits() {
        // 满健康基础值 100 N / 200 W / 1000 J；做功 100 W × 2 s 消耗 200 J。
        var initial = body(0);
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var request = MeridianProtocol.encodeRequest(MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1).setRequestId("capacity").setGetTissueCapability(
                        MeridianMessages.GetTissueCapability.newBuilder().setBodyId(initial.id().value()).setTissueId("tendril")).build());
        var reader = new BodyQueryHandler<String>((context, id) -> true, store);
        assertEquals(1000, MeridianProtocol.decodeResponse(reader.handle("host", request))
                .getTissueCapability().getAvailable().getEnduranceJoules(), 0);
        var denied = new BodyQueryHandler<String>((context, id) -> false, store);
        failure(denied.handle("host", request), MeridianMessages.FailureCode.ACCESS_DENIED);
        var command = command(0).setExertTissue(MeridianMessages.ExertTissue.newBuilder()
                .setTissueId("tendril").setForceNewtons(50).setPowerWatts(100).setDurationSeconds(2));
        var handler = handler(store, new AtomicLong(1000), catalog(), true);
        committed(handler.handle("host", envelope(command, "work")));
        committed(handler.handle("host", envelope(command, "work")));
        var after = store.find(initial.id()).orElseThrow();
        assertEquals(4, after.tissueStates().get(0).fatigue(), 0);
        assertEquals(new Anatomy.TissueCapacity(80, 160, 800), TissueCapabilities.available(after, TISSUE));
        assertEquals(initial.meridianStates(), after.meridianStates());
        assertEquals(initial.settledAt(), after.settledAt());

        // 同一负载在受伤组织上可能不成立；经脉破损不改变肌肉基础力值。
        var message = BodyProtobuf.toMessage(initial);
        var hurt = BodyProtobuf.fromMessage(message.toBuilder().setTissueStates(0,
                message.getTissueStates(0).toBuilder().setHp(25)).build());
        assertEquals(new Anatomy.TissueCapacity(50, 100, 500), TissueCapabilities.available(hurt, TISSUE));
        var tooStrong = new TissueCapabilities.Exertion(TISSUE, 51, 1, 1);
        assertEquals(MeridianErrors.CapabilityRejectionReason.TISSUE_LOAD_EXCEEDED,
                assertThrows(MeridianErrors.CapabilityRejected.class, () -> TissueCapabilities.exert(hurt, tooStrong)).reason());
        var disabled = BodyProtobuf.fromMessage(message.toBuilder().setPartStates(0,
                message.getPartStates(0).toBuilder().setHp(0)).build());
        assertEquals(new Anatomy.TissueCapacity(0, 0, 0), TissueCapabilities.available(disabled, TISSUE));
    }

    @Test
    public void escrowReservesSpaceAndCannotBeTransportedBeforeConversion() {
        // 四元输入搬入加工账户后仍占容量；只有缓存余下的两元允许发走。
        var initial = body(6);
        var message = BodyProtobuf.toMessage(initial);
        initial = BodyProtobuf.fromMessage(message.toBuilder().setStructure(message.getStructure().toBuilder()
                .setOrgans(0, message.getStructure().getOrgans(0).toBuilder().setQiCapacity(8))).build());
        var running = OrganProcessing.start(initial, ORGAN, recipe());
        var fed = QiTransport.start(running, transfer("channel.organ", qi(2)));
        assertEquals(MeridianErrors.TransportRejectionReason.CAPACITY_EXCEEDED,
                assertThrows(MeridianErrors.TransportRejected.class,
                        () -> QiTransport.start(fed, transfer("channel.organ", qi(1)))).reason());
        assertEquals(MeridianErrors.TransportRejectionReason.INSUFFICIENT_QI,
                assertThrows(MeridianErrors.TransportRejected.class,
                        () -> QiTransport.start(running, transfer("organ.channel", qi(3)))).reason());
        var sending = QiTransport.start(running, transfer("organ.channel", qi(2)));
        assertEquals(4, sending.organQiTotal(), 0);
        assertEquals(initial.totalQi(), sending.totalQi(), 0);
    }

    @Test
    public void freezeBlockRecoveryAndDeathKeepTheUnconvertedInput() {
        // 冻结平移完成时刻；阻塞只保留输入，恢复显式事件才完成，不按 tick 重试。
        var running = OrganProcessing.start(body(6), ORGAN, recipe());
        var frozen = BodyTimeAdvancement.apply(running, plan(11_000, LIFESPAN_ONLY)).body();
        assertEquals(13_000, frozen.organStates().get(0).process().completionMillis());
        assertEquals(running.organQiTotal(), frozen.organQiTotal(), 0);
        var message = BodyProtobuf.toMessage(running);
        var damaged = BodyProtobuf.fromMessage(message.toBuilder().setOrganStates(0,
                message.getOrganStates(0).toBuilder().setHp(0)).build());
        var blocked = BodyTimeAdvancement.apply(damaged, plan(3000, ACTIVE)).body();
        assertTrue(blocked.organStates().get(0).process().blocked());
        assertTrue(OrganProcessing.nextCompletionBoundary(blocked).isEmpty());
        var restored = BodyProtobuf.toMessage(blocked);
        var repaired = BodyProtobuf.fromMessage(restored.toBuilder().setOrganStates(0,
                restored.getOrganStates(0).toBuilder().setHp(30)).build());
        var completed = BodyTimeAdvancement.apply(repaired, plan(3000, ACTIVE));
        assertEquals(1, completed.releasedQi().totalQi(), 0);
        assertNull(completed.body().organStates().get(0).process());

        // 死亡早于或恰好等于完成时刻，都只释放原料；较晚死亡先完成加工并释放损耗。
        for (long deathAt : new long[] {2000, 3000, 4000}) {
            var mortal = BodyProtobuf.fromMessage(message.toBuilder().setVitality(message.getVitality().toBuilder()
                    .setLifespanLimitSeconds(15 + (deathAt - 1000) / 1000.0)).build());
            var result = BodyTimeAdvancement.apply(mortal, new BodyTimeAdvancement.Plan(plan(10_000, ACTIVE).target(), 1, ACTIVE));
            assertTrue(result.died());
            assertEquals(0, result.body().totalQi(), 0);
            assertEquals(running.totalQi(), result.releasedQi().totalQi(), 0);
            assertNull(result.body().organStates().get(0).process());
            assertEquals(deathAt > 3000, result.releasedQi().entries().stream().anyMatch(q -> q.attribute().equals(Qi.QiAttribute.FIRE)));
        }
    }

    @Test
    public void longAdvanceMatchesEventByEventWhenCompletionFreesBlockedCapacity() {
        // 模拟结构收缩后已经在路上的货物：先到达却装不下，加工损耗腾出空间后再入账。
        var running = OrganProcessing.start(body(4), ORGAN,
                new OrganRecipe(recipe().id(), 1, recipe().organKind(), qi(4), fire(2), 5000, Realm.TRUE_QI));
        var message = BodyProtobuf.toMessage(running);
        var changed = BodyProtobuf.fromMessage(message.toBuilder().setStructure(message.getStructure().toBuilder()
                        .setOrgans(0, message.getStructure().getOrgans(0).toBuilder().setQiCapacity(4)))
                .addTransits(BodyMessages.QiTransit.newBuilder().setId("imported").setConnectionId("channel.organ")
                        .setCargo(BodyProtobuf.toInventory(qi(2))).setArrivalMillis(3000).setThroughputPerSecond(1)
                        .setStatus(BodyMessages.TransitStatus.MOVING)).build());
        var first = BodyTimeAdvancement.apply(changed, plan(3000, ACTIVE));
        assertEquals(QiTransit.Status.BLOCKED_CAPACITY, first.body().transits().get(0).status());
        var stepped = BodyTimeAdvancement.apply(first.body(), plan(10_000, ACTIVE));
        var batched = BodyTimeAdvancement.apply(changed, plan(10_000, ACTIVE));
        assertEquals(BodyProtobuf.toMessage(stepped.body()).toBuilder().setRevision(batched.body().revision()).build(),
                BodyProtobuf.toMessage(batched.body()));
        assertEquals(stepped.releasedQi(), batched.releasedQi());
        assertTrue(batched.body().transits().isEmpty());
        assertEquals(changed.totalQi(), batched.body().totalQi() + batched.releasedQi().totalQi(), 0);
    }

    @Test
    public void installedRecipesAndPoliciesConstrainCommandsWithoutPartialWrites() {
        // 未安装、未授权、境界不够、失能、错误时刻以及忙碌分别给出稳定原因。
        var initial = body(6);
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var now = new AtomicLong(1000);
        failure(handler(store, now, catalog(), false).handle("host", start("denied", 0, "sample:fire", 1)),
                MeridianMessages.FailureCode.ACCESS_DENIED);
        var handler = handler(store, now, catalog(), true);
        failure(handler.handle("host", start("missing", 0, "sample:fire", 2)), MeridianMessages.FailureCode.RECIPE_NOT_FOUND);
        now.set(2000);
        failure(handler.handle("host", start("stale-time", 0, "sample:fire", 1)), MeridianMessages.FailureCode.TIME_REQUIRES_SETTLEMENT);
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        now.set(1000);
        committed(handler.handle("host", start("start", 0, "sample:fire", 1)));
        failure(handler.handle("host", start("busy", 1, "sample:fire", 1)), MeridianMessages.FailureCode.ORGAN_BUSY);
        var young = BodyProtobuf.fromMessage(BodyProtobuf.toMessage(initial).toBuilder().setRealm(BodyMessages.Realm.WANDERING_QI).build());
        assertEquals(MeridianErrors.CapabilityRejectionReason.RECIPE_INCOMPATIBLE,
                assertThrows(MeridianErrors.CapabilityRejected.class, () -> OrganProcessing.start(young, ORGAN, recipe())).reason());
        var wrong = new OrganRecipe(recipe().id(), 1, new DefinitionId("other:organ"), qi(4), fire(3), 1, Realm.TRUE_QI);
        assertThrows(MeridianErrors.CapabilityRejected.class, () -> OrganProcessing.start(initial, ORGAN, wrong));
    }

    @Test
    public void protocolAndCatalogRejectInvalidDefinitionsAndMismatchedFacts() {
        // 配方字节往返、缺字段、非法版本/剂量/时长与重复注册均覆盖公开边界。
        var valid = CapabilityProtobuf.toRecipe(recipe());
        assertEquals(valid, CapabilityProtobuf.decodeRecipe(CapabilityProtobuf.encodeRecipe(valid)));
        for (var invalid : List.of(valid.toBuilder().setVersion(0).build(), valid.toBuilder().setDurationMillis(0).build(),
                valid.toBuilder().clearInput().build(), valid.toBuilder().setOutput(BodyProtobuf.toInventory(fire(5))).build(),
                valid.toBuilder().setMinimumRealmValue(999).build())) {
            assertThrows(IllegalArgumentException.class, () -> CapabilityProtobuf.decodeRecipe(invalid.toByteArray()));
            assertThrows(IllegalArgumentException.class, () -> new OrganRecipeCatalog(List.of(invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> new OrganRecipeCatalog(List.of(valid, valid)));

        // 固定 wire 样例保护加工命令字段号；命令不包含可伪造的配方输出。
        var bytes = HexFormat.of().parseHex("08011201715a230a016210006a1c0a016f121573616d706c653a666972655f636f6e7665727465721801");
        var command = MeridianMessages.BodyCommand.newBuilder().setBodyId("b").setExpectedRevision(0)
                .setStartOrganRecipe(MeridianMessages.StartOrganRecipe.newBuilder().setOrganId("o")
                        .setRecipeId("sample:fire_converter").setRecipeVersion(1));
        assertArrayEquals(bytes, envelope(command, "q"));
        assertEquals(command.build(), MeridianProtocol.decodeRequest(bytes).getBodyCommand());

        var store = new InMemoryBodyStore(List.of(body(6)), List.of());
        committed(handler(store, new AtomicLong(1000), catalog(), true).handle("host", start("start", 0, "sample:fire", 1)));
        var receipt = store.transact(new BodyId("creature-1"), tx -> tx.receipt("host", "start").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeReceipt(receipt.toBuilder().clearOrganRecipe().build()));
        assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.encodeReceipt(receipt.toBuilder()
                .setOrganRecipe(valid.toBuilder().setVersion(2)).build()));
    }

    @Test
    public void processingCommitAndEnvironmentReleaseRollBackAsOneUnit() {
        // 提交前故障不能留下已扣原料；完成时伪造损耗也不能部分入环境账。
        var initial = body(6);
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, new AtomicLong(1000), catalog(), true);
        committed(handler.handle("host", start("start", 0, "sample:fire", 1)));
        var running = store.find(initial.id()).orElseThrow();
        var result = BodyTimeAdvancement.apply(running, plan(3000, ACTIVE));
        var forged = MeridianMessages.CommandReceipt.newBuilder().setCallerId("host").setRequestId("forged")
                .setCommand(MeridianProtocol.decodeRequest(advance("forged", 1)).getBodyCommand())
                .setCommitted(MeridianMessages.CommandCommitted.newBuilder().setBodyId(initial.id().value()).setPreviousRevision(1).setRevision(2))
                .setTimeAdvance(TimeProtobuf.toMessage(TimeProtobuf.toMessage(plan(3000, ACTIVE)), result).toBuilder()
                        .setReleasedQi(BodyProtobuf.toInventory(qi(2)))).build();
        assertThrows(IllegalStateException.class, () -> store.transact(initial.id(), tx -> {
            // 通过真实 stage 验证完整事实，而非单独测试纯算子计算。
            tx.stage(result.body(), forged);
            return null;
        }));
        assertEquals(running, store.find(initial.id()).orElseThrow());
        assertEquals(QiInventory.empty(), store.environmentQi());
        var valid = forged.toBuilder().setTimeAdvance(TimeProtobuf.toMessage(TimeProtobuf.toMessage(plan(3000, ACTIVE)), result)).build();
        assertThrows(IllegalStateException.class, () -> store.transact(initial.id(), tx -> {
            // 暂存成功后宿主失败，身体、回执与环境仍必须一起回滚。
            tx.stage(result.body(), valid);
            throw new IllegalStateException("before-publish");
        }));
        assertEquals(running, store.find(initial.id()).orElseThrow());
        assertEquals(QiInventory.empty(), store.environmentQi());
    }

    private static BodySnapshot body(double buffer) {
        // 非人构型保持原端口图，独立赋值肌肉能力、器官余额与经脉状态。
        var message = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI));
        return BodyProtobuf.fromMessage(message.toBuilder().setRevision(0).clearTransits()
                .setSettledAt(message.getSettledAt().toBuilder().setElapsedMillis(1000))
                .setPartStates(0, message.getPartStates(0).toBuilder().setHp(100))
                .setTissueStates(0, message.getTissueStates(0).toBuilder().setHp(50).setFatigue(0))
                .setOrganStates(0, message.getOrganStates(0).toBuilder().setHp(30).setBuffer(BodyProtobuf.toInventory(qi(buffer))))
                .setMeridianStates(0, message.getMeridianStates(0).toBuilder().setLumenQi(BodyProtobuf.toInventory(qi(10)))
                        .setWallQi(BodyProtobuf.toInventory(QiInventory.empty())).setCondition(BodyMessages.MeridianCondition.INTACT)).build());
    }

    private static OrganRecipe recipe() {
        // 测试配方只占测试命名空间，核心没有固定五行器官或功法 ID。
        return new OrganRecipe(new DefinitionId("sample:fire"), 1, new DefinitionId("sample:filter"),
                qi(4), fire(3), 2000, Realm.TRUE_QI);
    }

    private static OrganRecipeCatalog catalog() {
        // 正式注册接口只接收 protoc 消息，与附属 JAR 的使用方式相同。
        return new OrganRecipeCatalog(List.of(CapabilityProtobuf.toRecipe(recipe())));
    }

    private static QiInventory qi(double amount) {
        // 空余额使用空账户，不制造零数量明细。
        return amount == 0 ? QiInventory.empty() : new QiInventory(List.of(new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, amount)));
    }

    private static QiInventory fire(double amount) {
        // 属性产物仍是可输送的真元，测试不生成世界中的火焰实体。
        return new QiInventory(List.of(new Qi(Qi.QiPhase.GAS, Qi.QiAttribute.FIRE, amount)));
    }

    private static BodyTimeAdvancement.Plan plan(long time, BodyTimeAdvancement.Mode mode) {
        // 默认关闭老化以隔离加工时序；寿尽用例显式覆盖倍率。
        return new BodyTimeAdvancement.Plan(new TimeCursor(new DefinitionId("sample:world"), "epoch-1", time), 0, mode);
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, AtomicLong now, OrganRecipeCatalog recipes, boolean allowed) {
        // 私有策略可拒绝已通过基础业务校验的意图，且不得产生暂存副作用。
        return new BodyCommandHandler<>((context, command) -> Optional.of(context), (context, command, body) -> allowed,
                (context, body) -> TimeProtobuf.toMessage(plan(now.get(), ACTIVE)), store,
                (context, receipt, before, after) -> { }, recipes);
    }

    private static QiTransport.Request transfer(String connection, QiInventory cargo) {
        // 所有测试搬运显式预约两元每秒，不从缓存量猜测速率。
        return new QiTransport.Request(new Anatomy.ConnectionId(connection), cargo, 2);
    }

    private static byte[] transport(String id, long revision, String connection, QiInventory cargo) {
        // 通过 protoc 声明货物，不上传目标余额或到达时间。
        return envelope(command(revision).setTransportQi(MeridianMessages.TransportQi.newBuilder()
                .setConnectionId(connection).setCargo(BodyProtobuf.toInventory(cargo)).setThroughputPerSecond(2)), id);
    }

    private static byte[] start(String id, long revision, String recipe, int version) {
        // 调用方只指定实际器官与已安装配方版本。
        return envelope(command(revision).setStartOrganRecipe(MeridianMessages.StartOrganRecipe.newBuilder()
                .setOrganId("filter").setRecipeId(recipe).setRecipeVersion(version)), id);
    }

    private static byte[] advance(String id, long revision) {
        // 推进请求不包含可信时钟参数。
        return envelope(command(revision).setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance()), id);
    }

    private static MeridianMessages.BodyCommand.Builder command(long revision) {
        // 所有测试命令定位同一个独立身体，版本由每个用例明确给出。
        return MeridianMessages.BodyCommand.newBuilder().setBodyId("creature-1").setExpectedRevision(revision);
    }

    private static byte[] envelope(MeridianMessages.BodyCommand.Builder command, String id) {
        // 使用正式边界校验发送载荷，以覆盖命令 oneof 与必填字段。
        return MeridianProtocol.encodeRequest(MeridianMessages.RequestEnvelope.newBuilder().setProtocolVersion(1)
                .setRequestId(id).setBodyCommand(command).build());
    }

    private static void committed(byte[] response) {
        // 验证线协议结果，不以内部快照发生变化代替成功回执。
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
    }

    private static void failure(byte[] response, MeridianMessages.FailureCode code) {
        // 对调用方只承诺固定失败码，内部错误文案不构成接口。
        assertEquals(code, MeridianProtocol.decodeResponse(response).getFailure().getCode());
    }
}
