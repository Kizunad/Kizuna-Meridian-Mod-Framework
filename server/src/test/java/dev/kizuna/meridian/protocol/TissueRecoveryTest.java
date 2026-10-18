package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyTimeSource;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/** 整体回归：通用组织恢复跨协议、时间结算与存档重试遵守同一规则。 */
public class TissueRecoveryTest {
    @Test
    public void trustedRecoveryRestoresCapabilityAndSurvivesPersistenceWithoutReplay() {
        // 触须与注意组织共用规则，但按各自能力恢复；查询本身不修改疲劳。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, (context, current) -> plan(4000), true);
        byte[] request = request("rest", 0);
        byte[] response = handler.handle("owner", request);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        BodySnapshot recovered = store.find(initial.id()).orElseThrow();
        assertEquals(10, recovered.tissueStates().get(0).fatigue(), 1e-10);
        assertEquals(20, recovered.tissueStates().get(1).fatigue(), 1e-10);
        assertEquals(500, TissueCapabilities.available(recovered, new Anatomy.TissueId("tendril")).enduranceJoules(), 1e-10);
        assertEquals(240, TissueCapabilities.available(recovered, new Anatomy.TissueId("focus")).enduranceJoules(), 1e-10);
        assertEquals(initial.totalQi(), recovered.totalQi(), 0);
        assertEquals(initial.partStates(), recovered.partStates());
        assertEquals(1, recovered.revision());

        // 快照保存半衰期与动作，回执保存可信时间；成功重试不能再读取当前时钟。
        var receipt = store.transact(initial.id(), tx -> tx.receipt("owner", "rest").orElseThrow());
        var savedReceipt = MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt));
        BodySnapshot savedBody = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(recovered));
        assertEquals(recovered, savedBody);
        assertEquals(plan(4000), savedReceipt.getTimeAdvance().getPlan());
        var restored = new InMemoryBodyStore(List.of(savedBody), List.of(savedReceipt));
        var replayHandler = handler(restored, (context, current) -> {
            // 此回调一旦执行，说明重试错误地重新询问了宿主事实。
            throw new AssertionError("成功回执重试不应读取时间");
        }, true);
        assertArrayEquals(response, replayHandler.handle("owner", request));
        assertEquals(savedBody, restored.find(initial.id()).orElseThrow());

        // 接着只能恢复尚未结算的新区间；即使已有回执，同一游标的新请求也不能再恢复。
        var resumed = handler(restored, (context, current) -> plan(8000), true);
        assertTrue(MeridianProtocol.decodeResponse(resumed.handle("owner", request("next", 1))).hasCommandCommitted());
        BodySnapshot continued = restored.find(initial.id()).orElseThrow();
        assertEquals(5, continued.tissueStates().get(0).fatigue(), 1e-10);
        assertEquals(5, continued.tissueStates().get(1).fatigue(), 1e-10);
        assertEquals(MeridianMessages.FailureCode.TIME_NOT_ADVANCED,
                MeridianProtocol.decodeResponse(resumed.handle("owner", request("same-time", 2))).getFailure().getCode());
        assertEquals(continued, restored.find(initial.id()).orElseThrow());
    }

    @Test
    public void restIntervalsRespectFreezeInjuryAndDeathInsteadOfAgingSpeed() {
        // 分段与合并使用相同时间量；恢复独立于老化倍率，并在满恢复时截断。
        BodySnapshot initial = body();
        BodySnapshot direct = advance(initial, plan(4000));
        BodySnapshot split = advance(advance(initial, plan(2000)), plan(4000));
        for (int index = 0; index < direct.tissueStates().size(); index++) {
            assertEquals(direct.tissueStates().get(index).fatigue(), split.tissueStates().get(index).fatigue(), 1e-10);
        }
        BodySnapshot fastAging = advance(initial, plan(4000).toBuilder().setBiologicalSecondsPerSimulationSecond(10).build());
        assertEquals(direct.tissueStates(), fastAging.tissueStates());
        BodySnapshot fullyRecovered = advance(initial, plan(Long.MAX_VALUE));
        assertEquals(0, fullyRecovered.tissueStates().get(0).fatigue(), 0);
        assertEquals(0, fullyRecovered.tissueStates().get(1).fatigue(), 0);

        // 冻结区间不能在解冻后补领，只有之后一秒可用于恢复。
        BodySnapshot frozen = advance(initial, plan(10_000).toBuilder().setMode(MeridianMessages.TimeAdvanceMode.LIFESPAN_ONLY).build());
        assertEquals(initial.tissueStates(), frozen.tissueStates());
        assertEquals(20 * Math.pow(0.5, .25), advance(frozen, plan(11_000)).tissueStates().get(0).fatigue(), 1e-10);
        var message = BodyProtobuf.toMessage(initial);
        BodySnapshot disabledPart = BodyProtobuf.fromMessage(message.toBuilder().setPartStates(0,
                message.getPartStates(0).toBuilder().setHp(0)).build());
        assertEquals(disabledPart.tissueStates(), advance(disabledPart, plan(4000)).tissueStates());
        BodySnapshot disabledTissue = BodyProtobuf.fromMessage(message.toBuilder().setTissueStates(0,
                message.getTissueStates(0).toBuilder().setHp(0)).build());
        BodySnapshot afterInjury = advance(disabledTissue, plan(4000));
        assertEquals(disabledTissue.tissueStates().get(0), afterInjury.tissueStates().get(0));
        assertEquals(20, afterInjury.tissueStates().get(1).fatigue(), 1e-10);

        // 寿尽前两秒可恢复，之后的八秒不能计入；死体不能再次推进。
        BodySnapshot mortal = BodyProtobuf.fromMessage(message.toBuilder().setVitality(message.getVitality().toBuilder()
                .setLifespanLimitSeconds(17)).build());
        BodySnapshot dead = advance(mortal, plan(10_000).toBuilder().setBiologicalSecondsPerSimulationSecond(1).build());
        assertTrue(dead.vitality().dead());
        assertEquals(2000, dead.settledAt().elapsedMillis());
        assertEquals(20 / Math.sqrt(2), dead.tissueStates().get(0).fatigue(), 1e-10);
        assertEquals(40, dead.tissueStates().get(1).fatigue(), 1e-10);
        assertEquals(MeridianErrors.TimeRejectionReason.BODY_DEAD,
                assertThrows(MeridianErrors.TimeRejected.class, () -> advance(dead, plan(20_000))).reason());
    }

    @Test
    public void invalidRecoveryFactsAndRejectedTransactionsNeverChangeFatigue() {
        // 半衰期配置经过 protoc 边界，负值与非有限数均不能进入恢复计算。
        var message = BodyProtobuf.toMessage(body());
        for (double halfLifeSeconds : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var malformed = message.toBuilder().setStructure(message.getStructure().toBuilder().setTissues(0,
                    message.getStructure().getTissues(0).toBuilder().setRecoveryHalfLifeSeconds(halfLifeSeconds))).build();
            assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.decodeSnapshot(malformed.toByteArray()));
        }
        assertEquals(plan(1000), TimeProtobuf.decodePlan(TimeProtobuf.encodePlan(plan(1000))));

        // 私有策略可拒绝恢复，但不能留下候选疲劳、时间或回执。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var denied = handler(store, (context, current) -> plan(4000), false);
        assertEquals(MeridianMessages.FailureCode.ACCESS_DENIED,
                MeridianProtocol.decodeResponse(denied.handle("owner", request("denied", 0))).getFailure().getCode());
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertTrue(store.transact(initial.id(), tx -> tx.receipt("owner", "denied").isEmpty()));

        // 半衰期为零禁用自然恢复；极小不可表示增量不能只移走时间游标。
        BodySnapshot noRecovery = BodyProtobuf.fromMessage(message.toBuilder().setStructure(message.getStructure().toBuilder()
                .setTissues(0, message.getStructure().getTissues(0).toBuilder().clearRecoveryHalfLifeSeconds())).build());
        assertEquals(initial.tissueStates().get(0), advance(noRecovery, plan(4000)).tissueStates().get(0));
        BodySnapshot tinyRecovery = BodyProtobuf.fromMessage(message.toBuilder().setStructure(message.getStructure().toBuilder()
                .setTissues(0, message.getStructure().getTissues(0).toBuilder().setRecoveryHalfLifeSeconds(Double.MAX_VALUE))).build());
        assertEquals(MeridianErrors.TimeRejectionReason.NUMERIC_LIMIT,
                assertThrows(MeridianErrors.TimeRejected.class, () -> advance(tinyRecovery, plan(1))).reason());
    }

    private static BodySnapshot body() {
        // 独立非人构型同时声明两种组织，框架不能依赖人体 ID 或默认恢复比例。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var tendril = original.getStructure().getTissues(0).toBuilder().setRecoveryHalfLifeSeconds(4);
        var focus = tendril.clone().setId("focus").setKind("sample:nerve").clearPorts()
                .setFatigueCapacity(100).setRecoveryHalfLifeSeconds(2)
                .setCapacity(BodyMessages.TissueCapacity.newBuilder().setStrengthNewtons(0)
                        .setBurstWatts(100).setEnduranceJoules(300));
        return BodyProtobuf.fromMessage(original.toBuilder().setRevision(0).clearTransits()
                .setSettledAt(plan(0).getTarget()).setStructure(original.getStructure().toBuilder()
                        .setTissues(0, tendril).addTissues(focus))
                .setPartStates(0, original.getPartStates(0).toBuilder().setHp(100))
                .setTissueStates(0, original.getTissueStates(0).toBuilder().setHp(50).setFatigue(20))
                .addTissueStates(BodyMessages.TissueState.newBuilder().setId("focus").setHp(50).setFatigue(80)).build());
    }

    private static MeridianMessages.TimeAdvancePlan plan(long millis) {
        // 零老化倍率隔离模拟时间单位；哪些组织休息由身体动作决定。
        return MeridianMessages.TimeAdvancePlan.newBuilder().setTarget(BodyMessages.TimeCursor.newBuilder()
                        .setDomain("sample:world").setEpoch("epoch-1").setElapsedMillis(millis))
                .setBiologicalSecondsPerSimulationSecond(0).setMode(MeridianMessages.TimeAdvanceMode.ACTIVE).build();
    }

    private static BodySnapshot advance(BodySnapshot body, MeridianMessages.TimeAdvancePlan plan) {
        // 纯计算用例也经过实际协议映射，检查两种核心表示语义一致。
        return BodyTimeAdvancement.apply(body, TimeProtobuf.fromMessage(plan)).body();
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, BodyTimeSource<String> time, boolean allow) {
        // 通过公开处理器复用版本、授权、原子提交及回执，不给测试建立另一条恢复入口。
        return new BodyCommandHandler<>((context, command) -> Optional.of(context),
                (context, command, body) -> allow, time, store, (context, receipt, before, after) -> { });
    }

    private static byte[] request(String requestId, long revision) {
        // 客户端只请求结算，没有可上传的休息时间、速率或恢复量。
        return MeridianProtocol.encodeRequest(MeridianMessages.RequestEnvelope.newBuilder().setProtocolVersion(1)
                .setRequestId(requestId).setBodyCommand(MeridianMessages.BodyCommand.newBuilder()
                        .setBodyId("creature-1").setExpectedRevision(revision)
                        .setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance())).build());
    }
}
