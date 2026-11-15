package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RaiseTrainingGrowth;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TissueGrowth;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 抬高训练成长经由真实授权、事务、持久化回执和协议边界：只升不降、不越上限、只动训练成长和版本。 */
public final class RaiseTrainingGrowthTest {
    private static final String TISSUE = "tendril";
    private static final double MAXIMUM = 0.5;
    private static final double RAISED = 0.35;
    private static final double LOWER = 0.2;
    private static final double EPSILON = 1e-12;

    @Test
    public void raisesGrowthPreservesEverythingElseAndReplaysAfterReload() {
        // 只有专用入口和有权的调用者能提交；成长到给定值，其余字段逐一不变；重试和重载后都复用原回执。
        BodySnapshot initial = initial();
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty());
        var handler = handler(store);
        byte[] wire = request("raise", initial, growth(TISSUE, RAISED));
        assertFailure(handler.handle("owner", wire), FailureCode.ACCESS_DENIED);
        assertFailure(handler.handleModuleTags("owner", wire), FailureCode.ACCESS_DENIED);
        assertFailure(handler.handleTrainingGrowth("other", wire), FailureCode.ACCESS_DENIED);
        byte[] response = handler.handleTrainingGrowth("owner", wire);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        BodySnapshot updated = store.find(initial.id()).orElseThrow();
        assertOnlyGrowthChanged(initial, updated, RAISED);
        assertArrayEquals(response, handler.handleTrainingGrowth("owner", wire));
        var restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(updated));
        var reloaded = new InMemoryBodyStore(List.of(restored), store.receipts(), QiInventory.empty());
        assertArrayEquals(response, handler(reloaded).handleTrainingGrowth("owner", wire));
        assertEquals(1, reloaded.receipts().size());
        assertEquals(updated, reloaded.find(initial.id()).orElseThrow());
    }

    @Test
    public void growthOnlyGoesUpAndMayReachTheMaximum() {
        // 目标不高于现值时照常提交，但成长保持现值；目标正好是上限可以。
        BodySnapshot initial = initial();
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty());
        var handler = handler(store);
        handler.handleTrainingGrowth("owner", request("raise", initial, growth(TISSUE, RAISED)));
        BodySnapshot raised = store.find(initial.id()).orElseThrow();
        byte[] lower = handler.handleTrainingGrowth("owner", request("lower", raised, growth(TISSUE, LOWER)));
        assertTrue(MeridianProtocol.decodeResponse(lower).hasCommandCommitted());
        BodySnapshot kept = store.find(initial.id()).orElseThrow();
        assertEquals(RAISED, growthOf(kept), EPSILON);
        assertEquals(2, kept.revision());
        handler.handleTrainingGrowth("owner", request("top", kept, growth(TISSUE, MAXIMUM)));
        assertEquals(MAXIMUM, growthOf(store.find(initial.id()).orElseThrow()), EPSILON);
    }

    @Test
    public void rejectionsAreAtomicAndMalformedRequestsNeverReachTheBody() {
        // 越过上限、没有这个组织、组织没声明训练、版本过期：整笔拒绝，身体和回执不动。
        BodySnapshot initial = initial();
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty());
        var handler = handler(store);
        assertFailure(handler.handleTrainingGrowth("owner", request("over", initial, growth(TISSUE, MAXIMUM + 0.01))),
                FailureCode.INVALID_REQUEST);
        var mixed = RaiseTrainingGrowth.newBuilder().addTissues(tissue(TISSUE, RAISED))
                .addTissues(tissue("missing", RAISED)).build();
        assertFailure(handler.handleTrainingGrowth("owner", request("missing", initial, mixed)),
                FailureCode.MODULE_NOT_FOUND);
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertEquals(0, store.receipts().size());
        BodySnapshot untrained = untrained();
        var plain = new InMemoryBodyStore(List.of(untrained), List.of(), QiInventory.empty());
        assertFailure(handler(plain).handleTrainingGrowth("owner", request("plain", untrained, growth(TISSUE, LOWER))),
                FailureCode.INVALID_REQUEST);
        handler.handleTrainingGrowth("owner", request("raise", initial, growth(TISSUE, RAISED)));
        assertFailure(handler.handleTrainingGrowth("owner", request("stale", initial, growth(TISSUE, MAXIMUM))),
                FailureCode.REVISION_CONFLICT);
        // 空列表、同一组织写两次、负数：协议编码时就拒绝。
        var twice = RaiseTrainingGrowth.newBuilder().addTissues(tissue(TISSUE, LOWER))
                .addTissues(tissue(TISSUE, RAISED)).build();
        for (RaiseTrainingGrowth malformed : List.of(RaiseTrainingGrowth.getDefaultInstance(), twice,
                growth(TISSUE, -0.1))) {
            assertThrows(RuntimeException.class, () -> request("malformed", initial, malformed));
        }
    }

    private static void assertOnlyGrowthChanged(BodySnapshot initial, BodySnapshot updated, double expected) {
        // 除了那一个组织的训练成长和版本，其余构型、资源、伤势和时间逐字段一致。
        assertEquals(expected, growthOf(updated), EPSILON);
        var original = BodyProtobuf.toMessage(initial);
        var message = BodyProtobuf.toMessage(updated);
        var unchanged = message.toBuilder()
                .setTissueStates(0, original.getTissueStates(0))
                .setRevision(0)
                .build();
        assertEquals(original, unchanged);
        assertEquals(1, updated.revision());
    }

    private static double growthOf(BodySnapshot body) {
        return BodyProtobuf.toMessage(body).getTissueStates(0).getTrainingGrowth();
    }

    private static BodySnapshot initial() {
        // 非人体模板的那一块肌肉声明了训练定义，成长上限 0.5。
        BodyMessages.BodySnapshot.Builder message = BodyProtobuf.toMessage(untrained()).toBuilder();
        var training = BodyMessages.TissueTraining.newBuilder().setMaximumGrowth(MAXIMUM)
                .setConsolidationHalfLifeSeconds(3600).setWorkJoulesPerGrowth(1000);
        var structure = message.getStructure().toBuilder();
        structure.setTissues(0, structure.getTissues(0).toBuilder().setTraining(training));
        return BodyProtobuf.fromMessage(message.setStructure(structure).build());
    }

    private static BodySnapshot untrained() {
        // 模板原样：组织没有训练定义。
        var message = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI));
        return BodyProtobuf.fromMessage(message.toBuilder().setRevision(0).build());
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store) {
        // 专用入口仍需通过宿主身份和私有策略；抬高训练成长不读身体时钟。
        return new BodyCommandHandler<>((caller, command) -> {
            if (caller.equals("owner")) {
                return Optional.of(caller);
            }
            return Optional.empty();
        },
                (caller, command, body) -> caller.equals("owner"),
                (caller, body) -> { throw new AssertionError("抬高训练成长不应读取时钟"); },
                store, (caller, receipt, before, after) -> { /* 回执由 Store 原子保存。 */ });
    }

    private static RaiseTrainingGrowth growth(String tissue, double growth) {
        return RaiseTrainingGrowth.newBuilder().addTissues(tissue(tissue, growth)).build();
    }

    private static TissueGrowth tissue(String id, double growth) {
        return TissueGrowth.newBuilder().setTissueId(id).setGrowth(growth).build();
    }

    private static byte[] request(String id, BodySnapshot body, RaiseTrainingGrowth growth) {
        // 版本来自调用者所见快照，不能由可信入口代替调用者猜测。
        var command = BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .setRaiseTrainingGrowth(growth)
                .build();
        var request = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(id)
                .setBodyCommand(command)
                .build();
        return MeridianProtocol.encodeRequest(request);
    }

    private static void assertFailure(byte[] response, FailureCode code) {
        // 对照公开协议失败码，避免异常文本或日志掩盖错误分支。
        assertEquals(code, MeridianProtocol.decodeResponse(response).getFailure().getCode());
    }
}
