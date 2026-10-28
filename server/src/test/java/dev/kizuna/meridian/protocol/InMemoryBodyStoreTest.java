package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiSource;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiUsage;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpace;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpaceKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

/** 整体回归：可信宿主区域入账、默认释放账户归位及失败时的两账原子性。 */
public class InMemoryBodyStoreTest {
    private static final int BATCH_REGIONS = 4;
    private static final double BATCH_DENSITY = 0.01;

    @Test
    public void batchReleaseTransfersMinimumSharesAndRejectsWholeBatchOnFailure() {
        // 四份最小单位的逐笔减法会积累尾差，批量扣除必须等于各区域明细的同一次合计。
        double minimum = QiAccounting.MIN_TRANSFER_QI;
        QiInventory released = inventory(new Qi(Qi.QiAttribute.WOOD, BATCH_REGIONS * minimum, BATCH_DENSITY));
        var store = new InMemoryBodyStore(List.of(), List.of(), released);
        var transfers = new LinkedHashMap<String, QiInventory>();
        for (int index = 0; index < BATCH_REGIONS; index++) {
            String id = "region_" + index;
            var template = source(QiInventory.empty());
            var space = template.getSpace().toBuilder().setSourceId(id).build();
            store.registerEnvironmentSource(template.toBuilder().setSpace(space).build());
            transfers.put(id, inventory(new Qi(Qi.QiAttribute.WOOD, minimum, BATCH_DENSITY)));
        }
        var before = store.environmentSources();
        var invalid = new LinkedHashMap<>(transfers);
        invalid.put("missing", transfers.values().iterator().next());
        assertThrows(MeridianErrors.TransportRejected.class, () -> store.transferReleasedToSources(invalid));
        invalid.remove("region_0");
        assertEquals(FailureCode.ENVIRONMENT_UNAVAILABLE,
                assertThrows(MeridianErrors.IntakeRejected.class,
                        () -> store.transferReleasedToSources(invalid)).code());
        assertEquals(before, store.environmentSources());
        assertEquals(released, store.environmentQi());
        var committed = store.transferReleasedToSources(transfers);
        assertTrue(store.environmentQi().entries().isEmpty());
        double credited = 0;
        for (var entry : committed.entrySet()) {
            QiInventory inventory = BodyProtobuf.fromInventory(entry.getValue().getInventory());
            assertEquals(transfers.get(entry.getKey()), inventory);
            assertEquals(1, entry.getValue().getRevision());
            credited += inventory.totalQi();
        }
        assertEquals(released.totalQi(), credited, 0);
    }

    @Test
    public void externalReleasedCreditsPreserveAccountsAndRejectNestedOrFailedWrites() {
        // 无区域释放仍走唯一 Store，保留自定义属性和相态，且不改变区域版本。
        BodySnapshot body = ProtocolFixtures.snapshot(Realm.WANDERING_QI);
        var qi = inventory(new Qi(Qi.QiAttribute.of("qi_variants:wither"), 2, .01));
        var initial = source(qi);
        var store = new InMemoryBodyStore(List.of(body), List.of(), qi, List.of(initial));
        store.creditReleasedQi(qi);
        assertEquals(4, store.environmentQi().totalQi(), 0);
        assertEquals(initial, store.environmentSource("region").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> store.creditReleasedQi(QiInventory.empty()));
        assertThrows(IllegalStateException.class, () -> store.transact(body.id(), transaction -> {
            // 禁止嵌套写入，失败不得改变外层事务和默认账户。
            store.creditReleasedQi(qi);
            return null;
        }));
        assertEquals(4, store.environmentQi().totalQi(), 0);
        var restored = new InMemoryBodyStore(List.of(body), store.receipts(), store.environmentQi(), store.environmentSources());
        assertEquals(store.environmentQi(), restored.environmentQi());
        assertEquals(initial, restored.environmentSource("region").orElseThrow());
        var huge = inventory(new Qi(Qi.QiAttribute.WOOD, Double.MAX_VALUE, 1));
        var overflowing = new InMemoryBodyStore(List.of(), List.of(), huge);
        assertThrows(MeridianErrors.TransportRejected.class, () -> overflowing.creditReleasedQi(huge));
        assertEquals(huge, overflowing.environmentQi());
    }

    @Test
    public void mixedCreditsAndReleasedTransfersPreserveWindowAndSurviveRestore() {
        // 同一自定义属性的游离态和气态必须分别保存，普通属性可以与已有库存合并。
        Qi.QiAttribute wither = Qi.QiAttribute.of("qi_variants:wither");
        QiInventory released = inventory(new Qi(wither, 4, .01), new Qi(wither, 2, .1));
        EnvironmentQiSource initial = source(inventory(new Qi(Qi.QiAttribute.WOOD, 8, .01)));
        var store = new InMemoryBodyStore(List.of(), List.of(), released, List.of(initial));
        QiInventory credited = inventory(new Qi(Qi.QiAttribute.WOOD, 4, .01), new Qi(wither, 1, .1));
        QiInventory expectedCredit = inventory(new Qi(Qi.QiAttribute.WOOD, 12, .01), new Qi(wither, 1, .1));

        EnvironmentQiSource afterCredit = store.creditEnvironmentSource("region", credited);
        assertEquals(initial.toBuilder().setRevision(1).setInventory(BodyProtobuf.toInventory(expectedCredit)).build(), afterCredit);
        assertEquals(afterCredit, store.environmentSource("region").orElseThrow());
        assertEquals(released, store.environmentQi());

        // 部分转出保持两账总量，区域窗口、速率与历史用量都不能被重新发放。
        QiInventory amount = inventory(new Qi(wither, 2, .01), new Qi(wither, 1, .1));
        double totalBefore = store.environmentQi().totalQi() + expectedCredit.totalQi();
        EnvironmentQiSource afterTransfer = store.transferReleasedToSource("region", amount);
        QiInventory expectedInventory = inventory(new Qi(Qi.QiAttribute.WOOD, 12, .01),
                new Qi(wither, 2, .1), new Qi(wither, 2, .01));
        assertEquals(initial.toBuilder().setRevision(2).setInventory(BodyProtobuf.toInventory(expectedInventory)).build(), afterTransfer);
        assertEquals(amount, store.environmentQi());
        assertEquals(totalBefore, store.environmentQi().totalQi() + expectedInventory.totalQi(), 0);
        assertTrue(store.receipts().isEmpty());

        // 存档恢复只还原两份已提交余额，不重放外部入账或默认账户转出。
        EnvironmentQiSource savedSource = EnvironmentProtobuf.decodeSource(EnvironmentProtobuf.encodeSource(afterTransfer));
        QiInventory savedReleased = BodyProtobuf.fromInventory(BodyProtobuf.toInventory(store.environmentQi()));
        var restored = new InMemoryBodyStore(List.of(), store.receipts(), savedReleased, List.of(savedSource));
        assertEquals(afterTransfer, restored.environmentSource("region").orElseThrow());
        assertEquals(store.environmentQi(), restored.environmentQi());
    }

    @Test
    public void missingSourceInvalidAmountsAndExhaustedRevisionLeaveStateUntouched() {
        // 两入口共用来源与明细约束，空操作也不能消耗版本或触发部分转出。
        QiInventory amount = inventory(new Qi(Qi.QiAttribute.WOOD, 1, .01));
        EnvironmentQiSource initial = source(amount);
        EnvironmentQiSource exhausted = initial.toBuilder().setRevision(Long.MAX_VALUE)
                .setSpace(initial.getSpace().toBuilder().setSourceId("exhausted")).build();
        var store = new InMemoryBodyStore(List.of(), List.of(), amount, List.of(initial, exhausted));
        for (var update : creditOperations(store)) {
            assertEquals(FailureCode.ENVIRONMENT_UNAVAILABLE,
                    assertThrows(MeridianErrors.IntakeRejected.class, () -> update.apply("missing", amount)).code());
            assertEquals(FailureCode.REVISION_EXHAUSTED,
                    assertThrows(MeridianErrors.IntakeRejected.class, () -> update.apply("exhausted", amount)).code());
            assertThrows(NullPointerException.class, () -> update.apply("region", null));
            assertThrows(IllegalArgumentException.class, () -> update.apply("region", QiInventory.empty()));
            // 非法数量由不可变账目构造器拒绝，不能流入 Store 发布阶段。
            for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class,
                        () -> update.apply("region", inventory(new Qi(Qi.QiAttribute.WOOD, invalid, .01))));
            }
            assertEquals(initial, store.environmentSource("region").orElseThrow());
            assertEquals(exhausted, store.environmentSource("exhausted").orElseThrow());
            assertEquals(amount, store.environmentQi());
            assertEquals(2, store.environmentSources().size());
        }
    }

    @Test
    public void releasedTransfersRejectPartialOverdraftsAndMismatchedKinds() {
        // 第二项超额时第一项也不能转出，其他属性或相态的富余余额不能抵扣。
        QiInventory released = inventory(new Qi(Qi.QiAttribute.WOOD, 5, .01), new Qi(Qi.QiAttribute.FIRE, 2, .1));
        EnvironmentQiSource initial = source(inventory(new Qi(Qi.QiAttribute.WOOD, 8, .01)));
        var store = new InMemoryBodyStore(List.of(), List.of(), released, List.of(initial));
        List<QiInventory> invalidTransfers = List.of(
                inventory(new Qi(Qi.QiAttribute.WOOD, 6, .01)),
                inventory(new Qi(Qi.QiAttribute.WOOD, 1, .01), new Qi(Qi.QiAttribute.FIRE, 3, .1)),
                inventory(new Qi(Qi.QiAttribute.WATER, 1, .01)),
                inventory(new Qi(Qi.QiAttribute.WOOD, 1, .1)),
                inventory(new Qi(Qi.QiAttribute.WOOD, 1, .02)));
        for (QiInventory invalid : invalidTransfers) {
            assertEquals(MeridianErrors.TransportRejectionReason.INSUFFICIENT_QI,
                    assertThrows(MeridianErrors.TransportRejected.class,
                            () -> store.transferReleasedToSource("region", invalid)).reason());
            assertEquals(released, store.environmentQi());
            assertEquals(initial, store.environmentSource("region").orElseThrow());
        }

        // 合法整笔转出能清空默认账户；失败的历史尝试没有消耗来源版本。
        EnvironmentQiSource transferred = store.transferReleasedToSource("region", released);
        assertEquals(1, transferred.getRevision());
        assertEquals(QiInventory.empty(), store.environmentQi());
        assertEquals(15, BodyProtobuf.fromInventory(transferred.getInventory()).totalQi(), 0);
    }

    @Test
    public void numericAndProtocolFailuresCannotPublishEitherAccount() {
        // 浮点吞量和同类合并溢出均发生在候选阶段，默认账户不能先扣再失败。
        EnvironmentQiSource huge = source(inventory(new Qi(Qi.QiAttribute.WOOD, Double.MAX_VALUE, 1)));
        for (double amount : new double[] {1, Double.MAX_VALUE}) {
            QiInventory credited = inventory(new Qi(Qi.QiAttribute.WOOD, amount, 1));
            var store = new InMemoryBodyStore(List.of(), List.of(), credited, List.of(huge));
            for (var update : creditOperations(store)) {
                assertEquals(MeridianErrors.TransportRejectionReason.NUMERIC_LIMIT,
                        assertThrows(MeridianErrors.TransportRejected.class, () -> update.apply("region", credited)).reason());
                assertEquals(huge, store.environmentSource("region").orElseThrow());
                assertEquals(credited, store.environmentQi());
            }
        }

        // Java 明细合法但合并后超过协议列表上限，仍须整笔拒绝，证明发布前校验完整来源。
        QiInventory full = new QiInventory(IntStream.range(0, MeridianProtocol.MAX_LIST_ENTRIES)
                .mapToObj(index -> new Qi(Qi.QiAttribute.of("test:kind_" + index), 1, 1)).toList());
        EnvironmentQiSource initial = source(full);
        QiInventory credited = inventory(new Qi(Qi.QiAttribute.of("qi_variants:wither"), 1, .01));
        var store = new InMemoryBodyStore(List.of(), List.of(), credited, List.of(initial));
        for (var update : creditOperations(store)) {
            assertThrows(IllegalArgumentException.class, () -> update.apply("region", credited));
            assertEquals(initial, store.environmentSource("region").orElseThrow());
            assertEquals(credited, store.environmentQi());
        }
    }

    @Test
    public void bodyTransactionsRejectNestedCreditsAndTransfers() {
        // 回调能重入 Java 监视器，但不能绕过身体事务的发布边界修改环境。
        BodySnapshot body = ProtocolFixtures.snapshot(Realm.WANDERING_QI);
        QiInventory amount = inventory(new Qi(Qi.QiAttribute.WOOD, 1, .01));
        EnvironmentQiSource initial = source(amount);
        var store = new InMemoryBodyStore(List.of(body), List.of(), amount, List.of(initial));
        for (var update : creditOperations(store)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> store.transact(body.id(), transaction -> {
                // 嵌套失败后外层也退出，已提交的身体、来源和释放账户保持一致。
                return update.apply("region", amount);
            }));
            assertEquals(MeridianErrors.Host.NESTED_TRANSACTION, failure.getMessage());
            assertEquals(body, store.find(body.id()).orElseThrow());
            assertEquals(initial, store.environmentSource("region").orElseThrow());
            assertEquals(amount, store.environmentQi());
            assertTrue(store.receipts().isEmpty());
        }
        // 拒绝嵌套不能遗留 transactionOpen，退出回调后正常入账仍可继续。
        assertEquals(1, store.creditEnvironmentSource("region", amount).getRevision());
    }

    private static List<BiFunction<String, QiInventory, EnvironmentQiSource>> creditOperations(InMemoryBodyStore store) {
        // 两个宿主入口都必须满足同一来源校验和事务隔离契约。
        return List.of(store::creditEnvironmentSource, store::transferReleasedToSource);
    }

    private static QiInventory inventory(Qi... entries) {
        // 统一走不可变明细构造器，测试不伪造或跳过账目校验。
        return new QiInventory(List.of(entries));
    }

    private static EnvironmentQiSource source(QiInventory inventory) {
        // 使用已有供给用量，防止只在空窗口里验证入账而漏掉预算被重置的错误。
        return EnvironmentQiSource.newBuilder()
                .setSpace(EnvironmentSpace.newBuilder().setProtocolVersion(1).setSourceId("region")
                        .setKind(EnvironmentSpaceKind.REGION_BACKGROUND).setVolumeBlocks(25))
                .setRevision(0).setInventory(BodyProtobuf.toInventory(inventory))
                .setValidFrom(time(0)).setValidUntil(time(2000)).setMaxAbsorptionPerSecond(4)
                .addUsage(EnvironmentQiUsage.newBuilder().setStartedMillis(0).setEndedMillis(500).setQiPerSecond(1))
                .build();
    }

    private static BodyMessages.TimeCursor time(long millis) {
        // 来源沿用固定时钟，入账和归位都不得移动供给时间边界。
        return BodyMessages.TimeCursor.newBuilder().setDomain("sample:world").setEpoch("epoch-1")
                .setElapsedMillis(millis).build();
    }
}
