package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.LumenExport;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyExports;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class BodyExportsTest {
    private static final double PARTIAL_VOLUME = 1;
    private static final double TOLERANCE = 1e-10;
    private static final double LARGE_BALANCE = 1e16;
    private static final double INEXACT_CREDIT = 1.1;

    @Test
    public void exportAndPartialRefundSurviveSnapshotRestartAndReplay() {
        // 混相库存经序列化和全新 Store 恢复，供方仍能仅凭操作号取回凭据。
        var body = donor();
        var store = new InMemoryBodyStore(List.of(body), List.of());
        var funded = store.recordExport(quote(body));
        var after = store.find(body.id()).orElseThrow();
        double debited = body.meridianStates().getFirst().lumenQi().totalQi();
        assertEquals(body.totalQi(), after.totalQi() + debited, TOLERANCE);
        var restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(after));
        var recovered = new InMemoryBodyStore(List.of(restored), List.of());
        assertEquals(funded, recovered.recordExport(funded));
        assertEquals(after, recovered.find(body.id()).orElseThrow());
        var paid = BodyExports.allocate(funded, PARTIAL_VOLUME);
        assertEquals(paid, recovered.recordExport(paid));
        var finalBody = recovered.find(body.id()).orElseThrow();
        double received = BodyExports.credited(paid).totalQi();
        assertTrue(received > 0 && received < debited);
        assertEquals(body.totalQi(), finalBody.totalQi() + received, TOLERANCE);
        assertEquals(0, recovered.environmentQi().totalQi(), 0);
        assertEquals(paid, recovered.recordExport(funded));
        assertEquals(paid, recovered.recordExport(paid));
        assertEquals(finalBody, recovered.find(body.id()).orElseThrow());
    }

    @Test
    public void missingRefundAndReboundOperationAreRejectedBeforePublication() {
        // 受方只收了一部分，抹掉退款或给同号换人都必须拒绝，原来的在途库存仍可结算。
        var body = donor();
        var store = new InMemoryBodyStore(List.of(body), List.of());
        var funded = store.recordExport(quote(body));
        var saved = store.find(body.id());
        var corrupt = BodyExports.allocate(funded, PARTIAL_VOLUME).toBuilder();
        corrupt.getSharesBuilder(0).clearReturned();
        assertThrows(IllegalArgumentException.class, () -> store.recordExport(corrupt.build()));
        var rebound = funded.toBuilder().setRecipient("another").build();
        assertThrows(IllegalArgumentException.class, () -> store.recordExport(rebound));
        var key = BodyExports.PREFIX + funded.getOperationId();
        var forged = com.google.protobuf.Any.pack(rebound);
        assertThrows(IllegalArgumentException.class, () -> AddonProgress.replace(body, key, forged));
        assertEquals(saved, store.find(body.id()));
        assertEquals(funded, store.recordExport(funded));
    }

    private static BodySnapshot donor() {
        // 采用非人构型的混相样本，版本留出提交空间，经脉完好可调用。
        var message = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI)).toBuilder();
        message.setRevision(0);
        message.getMeridianStatesBuilder(0).setCondition(BodyMessages.MeridianCondition.INTACT);
        return BodyProtobuf.fromMessage(message.build());
    }

    @Test
    public void roundedAccountGrowthCannotConfirmDifferentCargo() {
        // 在这个数量级加 1.1 实际会多出 2 元，即使余额有变化也不能确认到账。
        var deposit = new Qi(Qi.QiAttribute.FIRE, INEXACT_CREDIT, 1);
        var account = new QiInventory(List.of(deposit.withAmount(LARGE_BALANCE)));
        var cargo = new QiInventory(List.of(deposit));
        assertThrows(IllegalArgumentException.class, () -> BodyExports.credit(account, cargo));
    }

    private static LumenExport quote(BodySnapshot body) {
        // 导出全部内腔余额；外壁和运输中的货不参与报价。
        var destination = LumenExport.newBuilder()
                .setOperationId(UUID.randomUUID().toString())
                .setRecipient("student")
                .setTarget("channel")
                .build();
        return BodyExports.quote(body, destination, Double.MAX_VALUE).orElseThrow();
    }
}
