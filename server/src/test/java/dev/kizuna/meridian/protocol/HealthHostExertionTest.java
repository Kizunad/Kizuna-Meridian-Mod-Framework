package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ExertTissue;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyHealthHost;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/** 健康宿主的做功分支复用普通命令、能力拒绝和成功回执。 */
public class HealthHostExertionTest {
    private static final String REQUEST_ID = "host-exertion";
    private static final String SUBJECT_ID = "subject";
    private static final long START_MILLIS = 1_000;
    private static final double EXCESSIVE_POWER_WATTS = 1_000;
    private static final double BOOST = 2;
    private static final double HALF = 0.5;

    @Test
    public void exertionAcceptsOnlyBatchExertionWithMatchingRequestId() {
        // 批量做功走普通入口；单组织、健康命令和其他身体操作都不能夹带进来。
        BodySnapshot body = body();
        RequestEnvelope exertion = request(body, load(1));
        HealthHostRequest valid = hostRequest(exertion);
        assertEquals(valid, HealthHostProtobuf.decodeRequest(HealthHostProtobuf.encodeRequest(valid)));
        assertFalse(HealthProtobuf.isHealthCommand(exertion.getBodyCommand()));
        assertThrows(IllegalArgumentException.class,
                () -> HealthHostProtobuf.encodeRequest(valid.toBuilder().setRequestId("another").build()));

        var death = HealthMessages.ConfirmBodyDeath.newBuilder().setSourceId("sample:host").build();
        List<BodyCommand> invalid = List.of(
                command(body).setExertTissue(load(1)).build(),
                command(body).setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance()).build(),
                command(body).setConfirmBodyDeath(death).build());
        for (BodyCommand operation : invalid) {
            RequestEnvelope nested = exertion.toBuilder().setBodyCommand(operation).build();
            HealthHostRequest rejected = valid.toBuilder().setExertion(nested).build();
            assertThrows(IllegalArgumentException.class, () -> HealthHostProtobuf.encodeRequest(rejected));
            assertThrows(IllegalArgumentException.class,
                    () -> HealthHostProtobuf.decodeRequest(rejected.toByteArray()));
        }
        var query = MeridianMessages.GetBodySnapshot.newBuilder().setBodyId(body.id().value()).build();
        RequestEnvelope nonCommand = exertion.toBuilder().setGetBodySnapshot(query).build();
        assertThrows(IllegalArgumentException.class,
                () -> HealthHostProtobuf.encodeRequest(valid.toBuilder().setExertion(nonCommand).build()));
        assertThrows(IllegalArgumentException.class,
                () -> HealthHostProtobuf.encodeRequest(valid.toBuilder().setEffect(exertion).build()));
    }

    @Test
    public void hostReturnsCapacityRejectionWithoutChangingFatigue() {
        // 健康宿主只转交普通命令，能力不足沿用组织做功拒绝且不保存回执。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        BodyHealthHost host = host(store);
        HealthHostRequest request = hostRequest(request(initial, load(EXCESSIVE_POWER_WATTS)));
        byte[] response = host.exchange(HealthHostProtobuf.encodeRequest(request));
        HealthHostResponse rejected = HealthHostProtobuf.decodeResponse(response);
        assertEquals(MeridianMessages.FailureCode.TISSUE_LOAD_EXCEEDED, rejected.getFailure().getCode());
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertTrue(store.receipts().isEmpty());
    }

    @Test
    public void hostExertionUsesTheWholeIntervalInsteadOfTheCurrentBoost() {
        // 当前峰值够用但半秒后到期，宿主提交一秒做功必须按区间最低倍率拒绝。
        var fixed = BodyMessages.CapabilityModifier.newBuilder()
                .setId("sample:boost")
                .setSource("sample:technique")
                .setTissueId("tendril")
                .setStartMillis(START_MILLIS)
                .addCurve(point(0))
                .addCurve(point((long) (START_MILLIS * HALF)))
                .setStrengthPeak(BOOST)
                .setBurstPeak(BOOST)
                .setEndurancePeak(BOOST)
                .setRecoveryPeak(1)
                .build();
        var message = BodyProtobuf.toMessage(body()).toBuilder().addModifiers(fixed).build();
        BodySnapshot initial = BodyProtobuf.fromMessage(message);
        double requestedWatts = 200;
        assertTrue(TissueCapabilities.available(initial, new Anatomy.TissueId("tendril"))
                .burstWatts() > requestedWatts);
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        byte[] request = HealthHostProtobuf.encodeRequest(hostRequest(request(initial, load(requestedWatts))));
        HealthHostResponse rejected = HealthHostProtobuf.decodeResponse(host(store).exchange(request));
        assertEquals(MeridianMessages.FailureCode.TISSUE_LOAD_EXCEEDED, rejected.getFailure().getCode());
        assertEquals(initial, store.find(initial.id()).orElseThrow());
    }

    @Test
    public void successfulRetriesReturnCurrentSnapshotWithoutChargingTwice() {
        // 外部协议重试和恢复后的重试均复用成功回执，返回当前身体而非历史快照。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        byte[] payload = HealthHostProtobuf.encodeRequest(hostRequest(request(initial, load(1))));
        BodyHealthHost host = host(store);
        HealthHostResponse first = HealthHostProtobuf.decodeResponse(host.exchange(payload));
        assertTrue(first.hasBody());
        BodySnapshot charged = store.find(initial.id()).orElseThrow();
        assertTrue(charged.tissueStates().get(0).fatigue() > initial.tissueStates().get(0).fatigue());
        assertEquals(1, charged.revision());
        assertEquals(BodyProtobuf.toMessage(charged),
                HealthHostProtobuf.decodeResponse(host.exchange(payload)).getBody());
        assertEquals(charged, store.find(initial.id()).orElseThrow());
        assertEquals(1, store.receipts().size());

        RequestEnvelope another = request(charged, load(1)).toBuilder().setRequestId("another-exertion").build();
        assertTrue(HealthHostProtobuf.decodeResponse(host.exchange(
                HealthHostProtobuf.encodeRequest(hostRequest(another)))).hasBody());
        BodySnapshot current = store.find(initial.id()).orElseThrow();
        assertEquals(BodyProtobuf.toMessage(current),
                HealthHostProtobuf.decodeResponse(host.exchange(payload)).getBody());
        var restored = new InMemoryBodyStore(List.of(current), store.receipts(), store.environmentQi());
        assertEquals(BodyProtobuf.toMessage(current),
                HealthHostProtobuf.decodeResponse(host(restored).exchange(payload)).getBody());
        assertEquals(current, restored.find(initial.id()).orElseThrow());
        assertEquals(2, restored.receipts().size());
    }

    private static BodyHealthHost host(InMemoryBodyStore store) {
        // 测试宿主用可信上下文转交普通处理器，生产生物附属按相同交换契约接线。
        var handler = new BodyCommandHandler<String>((context, command) -> Optional.of("sample:damage"),
                (context, command, body) -> true,
                (context, body) -> TimeProtobuf.toMessage(new BodyTimeAdvancement.Plan(body.settledAt(), 0,
                        BodyTimeAdvancement.Mode.ACTIVE)), store,
                (context, receipt, before, after) -> { /* 以 Store 的提交结果为准。 */ });
        return payload -> {
            HealthHostRequest request = HealthHostProtobuf.decodeRequest(payload);
            assertEquals(SUBJECT_ID, request.getSubjectId());
            byte[] commandResponse = handler.handle("trusted", request.getExertion().toByteArray());
            var response = MeridianProtocol.decodeResponse(commandResponse);
            var result = HealthHostResponse.newBuilder()
                    .setProtocolVersion(MeridianProtocol.VERSION)
                    .setRequestId(request.getRequestId());
            if (response.hasFailure()) {
                result.setFailure(response.getFailure());
            } else {
                String bodyId = request.getExertion().getBodyCommand().getBodyId();
                result.setBody(BodyProtobuf.toMessage(store.find(new dev.kizuna.meridian.core.BodyId(bodyId))
                        .orElseThrow()));
            }
            return HealthHostProtobuf.encodeResponse(result.build());
        };
    }

    private static HealthHostRequest hostRequest(RequestEnvelope exertion) {
        // 外层路由身份与内层幂等 ID 一起保留。
        return HealthHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(exertion.getRequestId())
                .setSubjectId(SUBJECT_ID)
                .setExertion(exertion)
                .build();
    }

    private static BodyMessages.CurvePoint point(long offsetMillis) {
        // 常值曲线节点与能力测试使用相同的公开字段。
        return BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(offsetMillis)
                .setValue(1)
                .build();
    }

    private static RequestEnvelope request(BodySnapshot body, ExertTissue load) {
        // 所有测试请求沿用正常批量做功契约，不通过健康命令入口。
        var loads = MeridianMessages.ExertTissues.newBuilder().addLoads(load).build();
        BodyCommand command = command(body).setExertTissues(loads).build();
        return RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(REQUEST_ID)
                .setBodyCommand(command)
                .build();
    }

    private static BodyCommand.Builder command(BodySnapshot body) {
        // 每个新操作使用当前权威版本，重复请求保留原始版本。
        return BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision());
    }

    private static ExertTissue load(double watts) {
        // 小负载用于幂等断言，超限负载只改变功率，便于辨认失败原因。
        return ExertTissue.newBuilder()
                .setTissueId("tendril")
                .setForceNewtons(0)
                .setPowerWatts(watts)
                .setDurationSeconds(1)
                .build();
    }

    private static BodySnapshot body() {
        // 使用现有非人构型，重设短时钟与可提交版本，不引入人体默认内容。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var time = original.getSettledAt().toBuilder().setElapsedMillis(START_MILLIS).build();
        var message = original.toBuilder()
                .setSettledAt(time)
                .setRevision(0)
                .clearTransits()
                .build();
        return BodyProtobuf.fromMessage(message);
    }
}
