package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.HealthMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.*;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 整体回归：附属伤害裁决、独立账户、主动恢复与原子清账共用正式协议。 */
public class BodyHealthTest {
    @Test
    public void deathProtectionAndTemporaryLimitsCommitAtomicallyAndReplay() {
        // 致死伤害与保命下限一次提交；不先死亡清账，也不在重试时重新消费效果。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> resolution(true, loss(part(), 1000)), true);
        var protectedDamage = damage();
        protectedDamage.setDamageBody(protectedDamage.getDamageBody().toBuilder().setDeathProtection(
                DeathProtection.newBuilder().setSourceId("sample:totem")
                        .addMinimumHp(Healing.newBuilder().setTarget(part()).setHp(25))));
        byte[] request = request("totem", 0, protectedDamage);
        byte[] response = handler.handleHealthEffect("host", request);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        BodySnapshot saved = store.find(initial.id()).orElseThrow();
        assertFalse(saved.vitality().dead());
        assertEquals(25, saved.partStates().get(0).hp(), 0);
        assertEquals(initial.totalQi(), saved.totalQi(), 0);
        assertEquals(0, store.environmentQi().totalQi(), 0);
        assertTrue(store.receipts().get(0).getHealth().getDeathPrevented());
        var protectedCommand = MeridianProtocol.decodeRequest(request).getBodyCommand();
        var protectedFact = store.receipts().get(0).getHealth();
        assertThrows(IllegalArgumentException.class, () -> HealthProtobuf.validateResult(
                protectedCommand, protectedFact.toBuilder().setDeathPrevented(false).build()));
        assertThrows(IllegalArgumentException.class, () -> HealthHostProtobuf.encodeResponse(
                HealthHostResponse.newBuilder().setProtocolVersion(1).setRequestId("bad-fact")
                        .setUnbound(true).setHealth(protectedFact).build()));
        assertArrayEquals(response, handler.handleHealthEffect("host", request));
        assertEquals(1, store.receipts().size());

        // 提高部位上限不送 HP，撤销上限裁剪存量；非法批次完整回滚。
        var limits = BodyCommand.newBuilder().setSetPartHealthLimits(SetPartHealthLimits.newBuilder()
                .setSourceId("sample:boost").addLimits(PartHealthLimit.newBuilder().setPartId(part().getPartId()).setMaxHp(200)));
        assertTrue(MeridianProtocol.decodeResponse(handler.handleHealthEffect("host", request("boost", 1, limits)))
                .hasCommandCommitted());
        assertEquals(25, store.find(initial.id()).orElseThrow().partStates().get(0).hp(), 0);
        assertEquals(200, store.find(initial.id()).orElseThrow().structure().parts().get(0).maxHp(), 0);
        limits.setSetPartHealthLimits(limits.getSetPartHealthLimits().toBuilder()
                .setLimits(0, PartHealthLimit.newBuilder().setPartId(part().getPartId()).setMaxHp(20)));
        assertTrue(MeridianProtocol.decodeResponse(handler.handleHealthEffect("host", request("remove", 2, limits)))
                .hasCommandCommitted());
        assertEquals(20, store.find(initial.id()).orElseThrow().partStates().get(0).hp(), 0);
        BodySnapshot beforeInvalid = store.find(initial.id()).orElseThrow();
        limits.setSetPartHealthLimits(limits.getSetPartHealthLimits().toBuilder()
                .addLimits(PartHealthLimit.newBuilder().setPartId("missing").setMaxHp(10)));
        failure(handler.handleHealthEffect("host", request("invalid-limits", 3, limits)), FailureCode.MODULE_NOT_FOUND);
        assertEquals(beforeInvalid, store.find(initial.id()).orElseThrow());
    }

    @Test
    public void externalDeathKeepsInjuriesAndReleasesQiOnlyOnceAcrossHostProtocol() {
        // 原版死亡不是另一次身体伤害，局部 HP 必须原样保存且只清算一次资源。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> { throw new AssertionError("死亡确认不调用伤害策略"); }, true);
        var effect = MeridianProtocol.decodeRequest(request("external-death", 0, BodyCommand.newBuilder()
                .setConfirmBodyDeath(ConfirmBodyDeath.newBuilder().setSourceId("sample:external"))));
        var hostRequest = HealthHostRequest.newBuilder()
                .setProtocolVersion(1).setRequestId(effect.getRequestId()).setSubjectId("subject")
                .setEffect(effect).build();
        assertEquals(hostRequest, HealthHostProtobuf.decodeRequest(HealthHostProtobuf.encodeRequest(hostRequest)));
        failure(handler.handle("host", effect.toByteArray()), FailureCode.ACCESS_DENIED);
        byte[] response = handler.handleHealthEffect("host", effect.toByteArray());
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        BodySnapshot dead = store.find(initial.id()).orElseThrow();
        assertTrue(dead.vitality().dead());
        assertEquals(initial.partStates(), dead.partStates());
        assertEquals(initial.tissueStates(), dead.tissueStates());
        assertEquals(initial.organStates().get(0).hp(), dead.organStates().get(0).hp(), 0);
        assertEquals(0, dead.totalQi(), 0);
        assertEquals(initial.totalQi(), store.environmentQi().totalQi(), 0);

        var restored = new InMemoryBodyStore(List.of(dead), store.receipts(), store.environmentQi());
        assertArrayEquals(response, defaultHandler(restored).handleHealthEffect("host", effect.toByteArray()));
        assertEquals(store.environmentQi(), restored.environmentQi());

        // 宿主交换只开放健康操作；身份错配、非健康命令和假未绑定都应在边界被拒绝。
        assertThrows(IllegalArgumentException.class, () -> HealthHostProtobuf.encodeRequest(
                hostRequest.toBuilder().setRequestId("another").build()));
        assertThrows(IllegalArgumentException.class, () -> HealthHostProtobuf.encodeRequest(
                hostRequest.toBuilder().setEffect(effect.toBuilder().setBodyCommand(effect.getBodyCommand().toBuilder()
                        .setAdvanceTime(AdvanceTime.getDefaultInstance()))).build()));
        assertThrows(IllegalArgumentException.class, () -> HealthHostProtobuf.encodeResponse(
                HealthHostResponse.newBuilder()
                        .setProtocolVersion(1).setRequestId("query").setUnbound(false).build()));
        var hostResponse = HealthHostResponse.newBuilder()
                .setProtocolVersion(1).setRequestId("query").setBody(BodyProtobuf.toMessage(dead)).build();
        assertEquals(hostResponse, HealthHostProtobuf.decodeResponse(HealthHostProtobuf.encodeResponse(hostResponse)));
    }

    @Test
    public void addonDecidesLossesAndDeathWhileNetworkAndPrivatePolicyRemainClosed() {
        // 同样的命中部位由插件决定实际损失；框架不能暗中扣部位 HP 或按部位归零判死。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var calls = new AtomicInteger();
        var handler = handler(store, payload -> {
            // 验证收到的是当前权威身体，而不是客户端可覆盖的构型。
            assertEquals(initial, BodyProtobuf.fromMessage(HealthProtobuf.decodeContext(payload).getBody()));
            calls.incrementAndGet();
            return resolution(false, loss(tissue(), 15));
        }, true);
        byte[] request = request("hurt", 0, damage());
        failure(handler.handle("host", request), FailureCode.ACCESS_DENIED);
        failure(handler(store, payload -> { throw new AssertionError(); }, false)
                .handleHealthEffect("host", request), FailureCode.ACCESS_DENIED);
        assertEquals(initial, store.find(initial.id()).orElseThrow());

        byte[] response = handler.handleHealthEffect("host", request);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        BodySnapshot hurt = store.find(initial.id()).orElseThrow();
        assertEquals(25, hurt.tissueStates().get(0).hp(), 0);
        assertEquals(initial.partStates(), hurt.partStates());
        assertEquals(initial.organStates(), hurt.organStates());
        assertFalse(hurt.vitality().dead());
        assertArrayEquals(response, handler.handleHealthEffect("host", request));
        assertEquals(1, calls.get());

        // 插件即使扣空所有部位但明确判为存活，框架仍只执行方案，不引入生物死亡规则。
        var independent = new InMemoryBodyStore(List.of(initial), List.of());
        var noDeath = handler(independent, payload -> resolution(false, loss(part(), 1000)), true);
        assertTrue(MeridianProtocol.decodeResponse(noDeath.handleHealthEffect("host", request)).hasCommandCommitted());
        assertEquals(0, independent.find(initial.id()).orElseThrow().partStates().get(0).hp(), 0);
        assertFalse(independent.find(initial.id()).orElseThrow().vitality().dead());
    }

    @Test
    public void healingAndActiveReliefAreIndependentBoundedAndAtomicDuringActions() {
        // 主动恢复可以在使用组织或器官时生效，且不删除动作、不改真元或旁边的 HP。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> resolution(false), true);
        var restore = BodyCommand.newBuilder().setRestoreFatigue(RestoreFatigue.newBuilder().setSourceId("sample:tonic")
                .addDoses(FatigueRelief.newBuilder().setTissueId("tendril").setAmount(1))
                .addDoses(FatigueRelief.newBuilder().setOrganId("filter").setAmount(3))
                .addDoses(FatigueRelief.newBuilder().setMeridianId("channel").setAmount(100)));
        assertTrue(MeridianProtocol.decodeResponse(handler.handleHealthEffect("host", request("restore", 0, restore)))
                .hasCommandCommitted());
        BodySnapshot rested = store.find(initial.id()).orElseThrow();
        assertEquals(1, rested.tissueStates().get(0).fatigue(), 0);
        assertEquals(5, rested.organStates().get(0).fatigueJoules(), 0);
        assertEquals(0, rested.meridianStates().get(0).fatigue(), 0);
        assertEquals(initial.actions(), rested.actions());
        assertEquals(initial.totalQi(), rested.totalQi(), 0);

        // 任一目标错误整批回滚，前面的合法剂量不能先扣疲劳。
        var invalid = restore.clone().setRestoreFatigue(restore.getRestoreFatigue().toBuilder()
                .addDoses(FatigueRelief.newBuilder().setTissueId("missing").setAmount(1)));
        failure(handler.handleHealthEffect("host", request("bad", 1, invalid)), FailureCode.MODULE_NOT_FOUND);
        assertEquals(rested, store.find(initial.id()).orElseThrow());
        assertEquals(1, store.receipts().size());

        var heal = BodyCommand.newBuilder().setHealBody(HealBody.newBuilder().setSourceId("sample:medicine")
                .addDoses(Healing.newBuilder().setTarget(tissue()).setHp(1000))
                .addDoses(Healing.newBuilder().setTarget(organ()).setHp(2)));
        assertTrue(MeridianProtocol.decodeResponse(handler.handleHealthEffect("host", request("heal", 1, heal)))
                .hasCommandCommitted());
        BodySnapshot healed = store.find(initial.id()).orElseThrow();
        assertEquals(50, healed.tissueStates().get(0).hp(), 0);
        assertEquals(22, healed.organStates().get(0).hp(), 0);
        assertEquals(initial.partStates(), healed.partStates());
        assertEquals(rested.actions(), healed.actions());
        assertEquals(rested.tissueStates().get(0).fatigue(), healed.tissueStates().get(0).fatigue(), 0);
    }

    @Test
    public void lethalResolutionReleasesEveryAccountOnceAndReplaysWithoutAddon() {
        // 加工输入、缓存、在途、内腔与外壁保留相态属性，并且每份只释放一次。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> resolution(true), true);
        byte[] request = request("death", 0, damage());
        byte[] response = handler.handleHealthEffect("host", request);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        BodySnapshot dead = store.find(initial.id()).orElseThrow();
        assertTrue(dead.vitality().dead());
        assertEquals(0, dead.totalQi(), 0);
        assertTrue(dead.actions().isEmpty());
        assertNull(dead.organStates().get(0).process());
        QiInventory expected = QiInventory.combine(List.of(initial.meridianStates().get(0).lumenQi(),
                initial.meridianStates().get(0).wallQi(), initial.transits().get(0).cargo(),
                initial.organStates().get(0).buffer(), initial.organStates().get(0).process().recipe().input()));
        assertEquals(expected, store.environmentQi());
        assertEquals(initial.totalQi(), store.environmentQi().totalQi(), 0);

        var receipt = MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(store.receipts().get(0)));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryBodyStore(List.of(dead), List.of(receipt)));
        var restored = new InMemoryBodyStore(List.of(BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(dead))),
                List.of(receipt), store.environmentQi());
        var noAddon = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, current) -> true,
                (caller, current) -> { throw new AssertionError("重试不得重新读取时间"); }, restored,
                (caller, fact, before, after) -> { throw new AssertionError("重试不得重新通知审计"); });
        assertArrayEquals(response, noAddon.handleHealthEffect("host", request));
        assertEquals(expected, restored.environmentQi());

        var heal = BodyCommand.newBuilder().setHealBody(HealBody.newBuilder().setSourceId("sample:medicine")
                .addDoses(Healing.newBuilder().setTarget(part()).setHp(5)));
        failure(handler.handleHealthEffect("host", request("revive", 1, heal)), FailureCode.BODY_DEAD);
        assertEquals(dead, store.find(initial.id()).orElseThrow());
    }

    @Test
    public void invalidAndForgedResolutionsCannotCommitOrFallBack() {
        // 已装配策略的明确拒绝不能静默改用随机伤害，也不能提交部分合法损失。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var unavailable = handler(store, payload -> {
            throw dev.kizuna.meridian.error.MeridianErrors.rejectHealth(FailureCode.DAMAGE_HANDLER_UNAVAILABLE);
        }, true);
        failure(unavailable.handleHealthEffect("host", request("missing", 0, damage())), FailureCode.DAMAGE_HANDLER_UNAVAILABLE);
        var invalid = handler(store, payload -> resolution(false, loss(tissue(), 5),
                loss(HealthTarget.newBuilder().setOrganId("missing").build(), 1)), true);
        failure(invalid.handleHealthEffect("host", request("invalid", 0, damage())), FailureCode.MODULE_NOT_FOUND);
        assertEquals(initial, store.find(initial.id()).orElseThrow());

        // 真实回执固定插件方案，存储必须拒绝有人另改 HP 或释放量的候选。
        var valid = handler(store, payload -> resolution(false, loss(tissue(), 5)), true);
        assertTrue(MeridianProtocol.decodeResponse(valid.handleHealthEffect("host", request("valid", 0, damage())))
                .hasCommandCommitted());
        var fact = store.receipts().get(0);
        BodySnapshot committed = store.find(initial.id()).orElseThrow();
        var forgedStore = new InMemoryBodyStore(List.of(initial), List.of());
        var forged = BodyProtobuf.toMessage(committed).toBuilder();
        forged.setPartStates(0, forged.getPartStates(0).toBuilder().setHp(1));
        assertThrows(IllegalStateException.class, () -> forgedStore.transact(initial.id(), transaction -> {
            // 绕过处理器直接误用 stage 也必须失败。
            transaction.stage(BodyProtobuf.fromMessage(forged.build()), fact);
            return null;
        }));
        assertEquals(initial, forgedStore.find(initial.id()).orElseThrow());
        assertTrue(forgedStore.receipts().isEmpty());

        var duplicate = HealBody.newBuilder().setSourceId("sample:medicine")
                .addDoses(Healing.newBuilder().setTarget(tissue()).setHp(1))
                .addDoses(Healing.newBuilder().setTarget(tissue()).setHp(2));
        assertThrows(IllegalArgumentException.class, () -> request("duplicate", 0,
                BodyCommand.newBuilder().setHealBody(duplicate)));
        assertThrows(IllegalArgumentException.class, () -> HealthProtobuf.encodeResolution(DamageResolution.newBuilder()
                .addLosses(loss(tissue(), Double.NaN)).setLethal(false).build()));
        assertThrows(IllegalArgumentException.class, () -> HealthProtobuf.encodeResolution(DamageResolution.getDefaultInstance()));
    }

    @Test
    public void defaultDamageSelectsOnlyLivingPartsAndPersistsTheRandomResult() {
        // 多部位非人构型保留一个已毁坏部位，证明随机选点只读取实际存活账户。
        var message = BodyProtobuf.toMessage(body()).toBuilder();
        message.setStructure(message.getStructure().toBuilder()
                .addParts(BodyMessages.PartDefinition.newBuilder().setId("fin").setDisplayName("鳍").setMaxHp(10))
                .addParts(BodyMessages.PartDefinition.newBuilder().setId("shell").setDisplayName("外壳").setMaxHp(10)));
        message.addPartStates(BodyMessages.PartState.newBuilder().setId("fin").setHp(10));
        message.addPartStates(BodyMessages.PartState.newBuilder().setId("shell").setHp(0));
        BodySnapshot initial = BodyProtobuf.fromMessage(message.build());
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = defaultHandler(store);
        var random = damage().setDamageBody(damage().getDamageBody().toBuilder().clearHit().setDamageHp(1));
        byte[] request = request("random", 0, random);

        // 普通网络入口仍拒绝；成功后回执固定一个目标，其他部位及组织保持原值。
        failure(handler.handle("host", request), FailureCode.ACCESS_DENIED);
        byte[] response = handler.handleHealthEffect("host", request);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        var receipt = store.receipts().get(0);
        var losses = receipt.getHealth().getDamageResolution().getLossesList();
        assertEquals(1, losses.size());
        String selectedId = losses.get(0).getTarget().getPartId();
        assertTrue(List.of("body", "fin").contains(selectedId));
        BodySnapshot hurt = store.find(initial.id()).orElseThrow();
        for (var part : initial.partStates()) {
            double expectedHp = part.hp();
            if (part.id().value().equals(selectedId)) {
                expectedHp -= 1;
            }
            assertEquals(expectedHp, hurt.partStates().stream().filter(state -> state.id().equals(part.id()))
                    .findFirst().orElseThrow().hp(), 0);
        }
        assertEquals(initial.tissueStates(), hurt.tissueStates());
        assertEquals(initial.organStates(), hurt.organStates());
        assertArrayEquals(response, handler.handleHealthEffect("host", request));
        var restored = new InMemoryBodyStore(List.of(hurt), List.of(MeridianProtocol
                .decodeReceipt(MeridianProtocol.encodeReceipt(receipt))));
        assertArrayEquals(response, defaultHandler(restored).handleHealthEffect("host", request));

        // 显式目标不再随机；兜底仅在最后一个部位也归零时判死并清账。
        var directStore = new InMemoryBodyStore(List.of(body()), List.of());
        var direct = defaultHandler(directStore);
        var hitOrgan = damage().setDamageBody(damage().getDamageBody().toBuilder()
                .setHit(organ()).setDamageHp(1).setKind(DamageKind.CUSTOM));
        assertTrue(MeridianProtocol.decodeResponse(direct.handleHealthEffect("host", request("organ", 0, hitOrgan)))
                .hasCommandCommitted());
        assertEquals(body().partStates(), directStore.find(initial.id()).orElseThrow().partStates());
        var lethal = damage().setDamageBody(damage().getDamageBody().toBuilder().clearHit().setDamageHp(1000));
        assertTrue(MeridianProtocol.decodeResponse(direct.handleHealthEffect("host", request("fatal", 1, lethal)))
                .hasCommandCommitted());
        assertTrue(directStore.find(initial.id()).orElseThrow().vitality().dead());
        failure(direct.handleHealthEffect("host", request("dead", 2, lethal)), FailureCode.BODY_DEAD);

        // 分类和目标存在性属于公开契约：未知分类与显式空目标不能伪装成随机命中。
        assertThrows(IllegalArgumentException.class, () -> request("unknown", 0, damage()
                .setDamageBody(damage().getDamageBody().toBuilder().setKindValue(100))));
        assertThrows(IllegalArgumentException.class, () -> request("unclassified", 0, damage()
                .setDamageBody(damage().getDamageBody().toBuilder().clearKind())));
        assertThrows(IllegalArgumentException.class, () -> request("empty-hit", 0, damage()
                .setDamageBody(damage().getDamageBody().toBuilder().setHit(HealthTarget.getDefaultInstance()))));
    }

    private static BodyCommandHandler<String> defaultHandler(InMemoryBodyStore store) {
        // 旧构造器现在装配通用兜底，仍使用与附属策略相同的授权和事务入口。
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller),
                (caller, command, current) -> true, (caller, current) -> plan(current), store,
                (caller, fact, before, after) -> { /* 测试直接读取已提交状态。 */ });
    }

    private static BodySnapshot body() {
        // 非人构型覆盖所有资源账户和活动占用，避免测试依赖 Human 包。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var recipe = new OrganRecipe(new DefinitionId("sample:process"), 1, new DefinitionId("sample:filter"),
                inventory(1), inventory(0.5), 1000, Realm.WANDERING_QI);
        var message = original.toBuilder().setRevision(0)
                .setSettledAt(original.getSettledAt().toBuilder().setElapsedMillis(0))
                .setStructure(original.getStructure().toBuilder().setOrgans(0, original.getStructure().getOrgans(0).toBuilder()
                        .setEndurance(BodyMessages.OrganEndurance.newBuilder().setEnduranceJoules(100).setMaximumWatts(10)
                                .setRecoveryHalfLifeSeconds(20))))
                .setOrganStates(0, original.getOrganStates(0).toBuilder().setFatigueJoules(8)
                        .setBuffer(BodyProtobuf.toInventory(inventory(2)))
                        .setProcess(BodyMessages.OrganProcess.newBuilder().setRecipe(CapabilityProtobuf.toRecipe(recipe))
                                .setCompletionMillis(1000).setBlocked(false)))
                .addActions(BodyMessages.ActiveAction.newBuilder().setInstanceId("use")
                        .setStartedMillis(0).setDefinition(BodyMessages.ActionDefinition.newBuilder()
                                .setId("sample:use").setVersion(1).setDisplayName("活动")
                                .addTissueIds("tendril").addOrganIds("filter")));
        return BodyProtobuf.fromMessage(message.build());
    }

    private static QiInventory inventory(double amount) {
        // 未完成加工使用可区分的原料属性，以检查死亡不会预发预计产物。
        return new QiInventory(List.of(new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.WOOD, amount)));
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, BodyDamageHandler damage, boolean allow) {
        // 可信效果依旧通过通用访问策略、时间源、事务与固定回执。
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller),
                (caller, command, current) -> allow, (caller, current) -> plan(current), store,
                (caller, receipt, before, after) -> { /* 测试不产生外部审计。 */ }, new OrganRecipeCatalog(List.of()),
                (caller, current, time) -> Optional.empty(), new ActionCatalog(List.of()),
                new MeridianOpeningCatalog(List.of()), caller -> false, damage);
    }

    private static TimeAdvancePlan plan(BodySnapshot body) {
        // 效果发生在当前已结清的事件边界。
        return TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(body.settledAt()))
                .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(1).build();
    }

    private static BodyCommand.Builder damage() {
        // 命中部位不意味着插件必须将损失分配给该部位。
        return BodyCommand.newBuilder().setDamageBody(DamageBody.newBuilder().setSourceId("sample:attack")
                .setDamageType("sample:impact").setKind(DamageKind.BLUNT).setHit(part()).setDamageHp(15));
    }

    private static byte[] request(String id, long revision, BodyCommand.Builder command) {
        // 请求往返经过正式边界的必填、剂量与唯一目标校验。
        return MeridianProtocol.encodeRequest(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(id)
                .setBodyCommand(command.clone().setBodyId("creature-1").setExpectedRevision(revision)).build());
    }

    private static byte[] resolution(boolean lethal, HpLoss... losses) {
        // 显式编码 false，以区分附属未作判断与明确存活。
        return HealthProtobuf.encodeResolution(DamageResolution.newBuilder().setLethal(lethal)
                .addAllLosses(List.of(losses)).build());
    }

    private static HpLoss loss(HealthTarget target, double hp) {
        // 局部意图不包含账本或构型覆盖。
        return HpLoss.newBuilder().setTarget(target).setHp(hp).build();
    }

    private static HealthTarget part() {
        // 非人身体只有一个部位，不触发任何人体命名分支。
        return HealthTarget.newBuilder().setPartId("body").build();
    }

    private static HealthTarget tissue() {
        // 组织与部位分开寻址。
        return HealthTarget.newBuilder().setTissueId("tendril").build();
    }

    private static HealthTarget organ() {
        // 器官使用自己的 HP 与疲劳账户。
        return HealthTarget.newBuilder().setOrganId("filter").build();
    }

    private static void failure(byte[] payload, FailureCode expected) {
        // 只比较公开失败码，避免绑定内部错误文案。
        assertEquals(expected, MeridianProtocol.decodeResponse(payload).getFailure().getCode());
    }
}
