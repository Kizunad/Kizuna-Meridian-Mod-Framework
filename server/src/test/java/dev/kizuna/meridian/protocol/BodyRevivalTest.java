package dev.kizuna.meridian.protocol;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages.ReviveBody;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 复活使用正式健康入口，恢复与寿元扣减只形成一次提交。 */
public class BodyRevivalTest {
    private static final double COST = 100;
    private static final double HP_RATIO = 0.1;
    private static final double HEALTHY_PART_HP = 80;
    private static final double HEALTHY_TISSUE_HP = 40;
    private static final double FRACTIONAL_AGE = 0.1;
    private static final double FRACTIONAL_SPENT = 0.2;
    private static final long RESPAWN_MILLIS = 5000;

    @Test
    public void restoresMinimumHealthAndFullFatigueWithoutChangingOtherAccounts() {
        // 非人体构型验证恢复底线、较高 HP 保留及其他账户逐字段不变。
        BodySnapshot dead = deadBody();
        var store = new InMemoryBodyStore(List.of(dead), List.of());
        var handler = handler(store);
        byte[] request = request(dead, COST);
        assertEquals(FailureCode.ACCESS_DENIED,
                MeridianProtocol.decodeResponse(handler.handle("host", request)).getFailure().getCode());
        byte[] response = handler.handleHealthEffect("host", request);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        BodySnapshot revived = store.find(dead.id()).orElseThrow();
        var before = BodyProtobuf.toMessage(dead);
        var after = BodyProtobuf.toMessage(revived);
        assertFalse(revived.vitality().dead());
        assertEquals(dead.revision() + 1, revived.revision());
        assertEquals(RESPAWN_MILLIS, revived.settledAt().elapsedMillis());
        assertEquals(dead.vitality().ageSeconds(), revived.vitality().ageSeconds(), 0);
        assertEquals(dead.vitality().spentLifespanSeconds() + COST,
                revived.vitality().spentLifespanSeconds(), 0);
        assertEquals(before.getStructure().getParts(0).getMaxHp() * HP_RATIO, after.getPartStates(0).getHp(), 0);
        assertEquals(before.getStructure().getTissues(0).getMaxHp() * HP_RATIO, after.getTissueStates(0).getHp(), 0);
        assertEquals(before.getOrganStates(0).getHp(), after.getOrganStates(0).getHp(), 0);
        assertEquals(before.getStructure().getTissues(0).getFatigueCapacity(),
                after.getTissueStates(0).getFatigue(), 0);
        assertPreserved(before, after);
        var receipt = store.receipts().get(0);
        assertEquals(COST, receipt.getHealth().getRevival().getSpentLifespanSeconds(), 0);
        assertFalse(receipt.getHealth().getRevival().getLifespanExhausted());
        assertEquals(receipt, MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt)));
        assertArrayEquals(response, handler.handleHealthEffect("host", request));
        assertEquals(revived, store.find(dead.id()).orElseThrow());
        assertEquals(1, store.receipts().size());
    }

    @Test
    public void rejectsAliveBodyAndExhaustsShortBalanceAtomically() {
        // 活体不可借复活回血；余额不足仍提交，回执明确标记寿元耗尽。
        BodySnapshot dead = fractionalLifespanBody();
        var original = BodyProtobuf.toMessage(dead);
        var livingVitality = original.getVitality().toBuilder().setDead(false /* dead */).build();
        BodySnapshot living = BodyProtobuf.fromMessage(original.toBuilder().setVitality(livingVitality).build());
        var livingStore = new InMemoryBodyStore(List.of(living), List.of());
        var failure = MeridianProtocol.decodeResponse(
                handler(livingStore).handleHealthEffect("host", request(living, COST)));
        assertEquals(FailureCode.BODY_ALIVE, failure.getFailure().getCode());
        assertEquals(living, livingStore.find(living.id()).orElseThrow());
        assertTrue(livingStore.receipts().isEmpty());

        double balance = dead.vitality().remainingSeconds();
        var store = new InMemoryBodyStore(List.of(dead), List.of());
        var response = MeridianProtocol.decodeResponse(
                handler(store).handleHealthEffect("host", request(dead, balance + COST)));
        assertTrue(response.hasCommandCommitted());
        BodySnapshot revived = store.find(dead.id()).orElseThrow();
        assertFalse(revived.vitality().dead());
        assertEquals(0, revived.vitality().remainingSeconds(), 0);
        assertEquals(balance, store.receipts().get(0).getHealth().getRevival().getSpentLifespanSeconds(), 0);
        assertTrue(store.receipts().get(0).getHealth().getRevival().getLifespanExhausted());
        var settled = revived.settledAt();
        var later = new TimeCursor(settled.domain(), settled.epoch(), settled.elapsedMillis() + 1);
        var plan = new BodyTimeAdvancement.Plan(later, 1, BodyTimeAdvancement.Mode.ACTIVE);
        assertTrue(BodyTimeAdvancement.apply(revived, plan).body().vitality().dead());
    }

    @Test
    public void restoresOrgansAndPreservesHigherPartAndTissueHealth() {
        // 反向伤势组合避免只验证某一种账户的底线。
        var original = BodyProtobuf.toMessage(deadBody());
        var part = original.getPartStates(0).toBuilder().setHp(HEALTHY_PART_HP).build();
        var tissue = original.getTissueStates(0).toBuilder().setHp(HEALTHY_TISSUE_HP).build();
        var organ = original.getOrganStates(0).toBuilder().setHp(0).build();
        var message = original.toBuilder()
                .setPartStates(0, part)
                .setTissueStates(0, tissue)
                .setOrganStates(0, organ)
                .build();
        BodySnapshot dead = BodyProtobuf.fromMessage(message);
        var store = new InMemoryBodyStore(List.of(dead), List.of());
        handler(store).handleHealthEffect("host", request(dead, COST));
        var revived = BodyProtobuf.toMessage(store.find(dead.id()).orElseThrow());
        assertEquals(part.getHp(), revived.getPartStates(0).getHp(), 0);
        assertEquals(tissue.getHp(), revived.getTissueStates(0).getHp(), 0);
        assertEquals(message.getStructure().getOrgans(0).getMaxHp() * HP_RATIO,
                revived.getOrganStates(0).getHp(), 0);
    }

    private static void assertPreserved(BodyMessages.BodySnapshot before, BodyMessages.BodySnapshot after) {
        // 排除只有复活会改变的字段，整份剩余消息须逐字节相等。
        var expected = before.toBuilder()
                .clearRevision()
                .clearSettledAt()
                .clearVitality()
                .clearPartStates()
                .clearTissueStates()
                .clearOrganStates()
                .build();
        var actual = after.toBuilder()
                .clearRevision()
                .clearSettledAt()
                .clearVitality()
                .clearPartStates()
                .clearTissueStates()
                .clearOrganStates()
                .build();
        assertEquals(expected, actual);
        assertEquals(before.getOrganStatesList(), after.getOrganStatesList());
    }

    private static BodySnapshot deadBody() {
        // 保留非空真元与经脉损伤，用可信死态快照覆盖复活算子的独立约束。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var clock = original.getSettledAt().toBuilder().setElapsedMillis(0).build();
        var vitality = original.getVitality().toBuilder().setDead(true /* dead */).build();
        var part = original.getPartStates(0).toBuilder().setHp(0).build();
        var tissue = original.getTissueStates(0).toBuilder().setHp(0).build();
        var message = original.toBuilder()
                .setRevision(0)
                .setSettledAt(clock)
                .setVitality(vitality)
                .setPartStates(0, part)
                .setTissueStates(0, tissue)
                .putAddonProgress("sample:scrolls", Any.pack(StringValue.of("sample:volume_read")))
                .build();
        return BodyProtobuf.fromMessage(message);
    }

    private static BodySnapshot fractionalLifespanBody() {
        // 0.2 加剩余的 0.7 会留下浮点尾差，耗尽回执必须仍精确对应零余额。
        var original = BodyProtobuf.toMessage(deadBody());
        var vitality = original.getVitality().toBuilder()
                .setLifespanLimitSeconds(1)
                .setAgeSeconds(FRACTIONAL_AGE)
                .setSpentLifespanSeconds(FRACTIONAL_SPENT)
                .build();
        return BodyProtobuf.fromMessage(original.toBuilder().setVitality(vitality).build());
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store) {
        // 与真实宿主相同的健康入口使用复活时刻，不追补死态期间的生理变化。
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true, (caller, body) -> {
                    var target = BodyProtobuf.toTime(body.settledAt()).toBuilder()
                            .setElapsedMillis(RESPAWN_MILLIS)
                            .build();
                    return TimeAdvancePlan.newBuilder()
                            .setTarget(target)
                            .setMode(TimeAdvanceMode.ACTIVE)
                            .setBiologicalSecondsPerSimulationSecond(1)
                            .build();
                }, store, (caller, receipt, before, after) -> { /* 直接检查事务存储。 */ });
    }

    private static byte[] request(BodySnapshot body, double cost) {
        // 费用由内容调用方传入，格式校验与请求身份复用正式协议。
        var revive = ReviveBody.newBuilder()
                .setSourceId("sample:respawn")
                .setLifespanSeconds(cost)
                .build();
        var command = BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .setReviveBody(revive)
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("revive")
                .setBodyCommand(command)
                .build();
        return MeridianProtocol.encodeRequest(request);
    }
}
