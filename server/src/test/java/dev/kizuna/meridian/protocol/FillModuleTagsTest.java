package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FillModuleTags;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ModuleTagAssignment;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/** 构型维护经由真实授权、事务、持久化回执和协议边界。 */
public final class FillModuleTagsTest {
    @Test
    public void fillsAllKindsPreservesStateAndReplaysAfterReload() {
        // 任意生物的三个模块种类都按实际 ID 补齐，不能改动其他字段。
        BodySnapshot initial = initial();
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty());
        var handler = handler(store);
        var tissue = ModuleTagAssignment.newBuilder().setTissueId("tendril").addTags("sample:muscle").build();
        var organ = ModuleTagAssignment.newBuilder().setOrganId("filter").addTags("sample:breathing").build();
        var meridian = ModuleTagAssignment.newBuilder().setMeridianId("channel").addTags("sample:hand").build();
        var tags = FillModuleTags.newBuilder().addModules(tissue).addModules(organ).addModules(meridian).build();
        byte[] wire = request("fill", initial, tags);
        assertFailure(handler.handle("owner", wire), FailureCode.ACCESS_DENIED);
        assertFailure(handler.handleModuleTags("other", wire), FailureCode.ACCESS_DENIED);
        byte[] response = handler.handleModuleTags("owner", wire);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        var updated = store.find(initial.id()).orElseThrow();
        assertOnlyTagsChanged(initial, updated);
        assertArrayEquals(response, handler.handleModuleTags("owner", wire));
        var restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(updated));
        var reloaded = new InMemoryBodyStore(List.of(restored), store.receipts(), QiInventory.empty());
        assertArrayEquals(response, handler(reloaded).handleModuleTags("owner", wire));
        assertEquals(1, reloaded.receipts().size());
        assertEquals(updated, reloaded.find(initial.id()).orElseThrow());
        // 新请求提交相同集合也不能抹掉历史回执；集合顺序没有替换语义。
        assertTrue(MeridianProtocol.decodeResponse(handler(reloaded).handleModuleTags("owner",
                request("same-tags", updated, tags))).hasCommandCommitted());
        assertEquals(2, reloaded.receipts().size());
    }

    @Test
    public void conflictsAreAtomicAndRevisionAndUnknownModulesAreRejected() {
        // 一批中先遇到可补项再遇到冲突，也必须保留整份原身体和原回执。
        BodySnapshot initial = initial();
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty());
        var handler = handler(store);
        var channel = ModuleTagAssignment.newBuilder().setMeridianId("channel").addTags("sample:hand").build();
        var first = FillModuleTags.newBuilder().addModules(channel).build();
        handler.handleModuleTags("owner", request("first", initial, first));
        var current = store.find(initial.id()).orElseThrow();
        var tissue = ModuleTagAssignment.newBuilder().setTissueId("tendril").addTags("sample:muscle").build();
        var different = channel.toBuilder().clearTags().addTags("sample:foot").build();
        var conflict = FillModuleTags.newBuilder().addModules(tissue).addModules(different).build();
        assertFailure(handler.handleModuleTags("owner", request("conflict", current, conflict)),
                FailureCode.INVALID_REQUEST);
        assertFailure(handler.handleModuleTags("owner", request("stale", initial, first)),
                FailureCode.REVISION_CONFLICT);
        var unknown = channel.toBuilder().setMeridianId("missing").build();
        var unknownTags = FillModuleTags.newBuilder().addModules(unknown).build();
        assertFailure(handler.handleModuleTags("owner", request("missing", current, unknownTags)),
                FailureCode.MODULE_NOT_FOUND);
        assertEquals(current, store.find(initial.id()).orElseThrow());
        assertEquals(1, store.receipts().size());
    }

    private static void assertOnlyTagsChanged(BodySnapshot initial, BodySnapshot updated) {
        // 三种标签分别核对，其余构型、资源、伤势和时间必须逐字段一致。
        var message = BodyProtobuf.toMessage(updated);
        var structure = message.getStructure();
        assertEquals(List.of("sample:muscle"), structure.getTissues(0).getTagsList());
        assertEquals(List.of("sample:breathing"), structure.getOrgans(0).getTagsList());
        assertEquals(List.of("sample:hand"), structure.getMeridians(0).getTagsList());
        var original = BodyProtobuf.toMessage(initial);
        var unchanged = message.toBuilder()
                .setStructure(original.getStructure())
                .setRevision(0)
                .build();
        assertEquals(original, unchanged);
        assertEquals(1, updated.revision());
    }

    private static BodySnapshot initial() {
        // 使用非人体模板和含在途、寿命等状态的完整身体保护通用契约。
        var message = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI));
        return BodyProtobuf.fromMessage(message.toBuilder().setRevision(0).build());
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store) {
        // 专用入口仍需通过宿主身份和私有策略；标签维护无需推进身体时钟。
        return new BodyCommandHandler<>((caller, command) -> {
            if (caller.equals("owner")) {
                return Optional.of(caller);
            }
            return Optional.empty();
        },
                (caller, command, body) -> caller.equals("owner"),
                (caller, body) -> { throw new AssertionError("标签维护不应读取时钟"); },
                store, (caller, receipt, before, after) -> { /* 回执由 Store 原子保存。 */ });
    }

    private static byte[] request(String id, BodySnapshot body, FillModuleTags tags) {
        // 版本来自调用者所见快照，不能由可信入口代替调用者猜测。
        var command = BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .setFillModuleTags(tags)
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
