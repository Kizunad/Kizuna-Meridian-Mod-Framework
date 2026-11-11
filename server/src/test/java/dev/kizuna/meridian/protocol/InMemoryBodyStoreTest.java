package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
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

    @Test
    public void deviceDrawsShareTheRateCurveAndMoveQiIntoTheReleasedAccount() {
        // 窗口上限 4 元/秒，[0,500) 已被身体占 1 元/秒；装置在 [0,1000) 取 2 元，占 2 元/秒。
        QiInventory stock = inventory(new Qi(Qi.QiAttribute.WOOD, 6, .01), new Qi(Qi.QiAttribute.FIRE, 2, .01));
        EnvironmentQiSource initial = source(stock);
        var store = new InMemoryBodyStore(List.of(), List.of(), QiInventory.empty(), List.of(initial));
        assertEquals(3, EnvironmentAbsorption.spareRate(initial, time(0), time(1000)), 0);
        assertEquals(4, EnvironmentAbsorption.spareRate(initial, time(500), time(1000)), 0);
        QiInventory drawn = inventory(new Qi(Qi.QiAttribute.WOOD, 1.5, .01), new Qi(Qi.QiAttribute.FIRE, .5, .01));
        double totalBefore = stock.totalQi();

        // 区域少多少，默认释放账户就多多少；曲线按重叠子区间合计，窗口和上限不动。
        EnvironmentQiSource afterDraw = store.drawEnvironmentSource("region", 0, time(0), time(1000), drawn);
        assertEquals(1, afterDraw.getRevision());
        assertEquals(inventory(new Qi(Qi.QiAttribute.WOOD, 4.5, .01), new Qi(Qi.QiAttribute.FIRE, 1.5, .01)),
                BodyProtobuf.fromInventory(afterDraw.getInventory()));
        assertEquals(drawn, store.environmentQi());
        assertEquals(totalBefore, BodyProtobuf.fromInventory(afterDraw.getInventory()).totalQi()
                + store.environmentQi().totalQi(), 1e-12);
        assertEquals(List.of(usage(0, 500, 3), usage(500, 1000, 2)), afterDraw.getUsageList());
        assertEquals(initial.getValidUntil(), afterDraw.getValidUntil());
        assertEquals(initial.getMaxAbsorptionPerSecond(), afterDraw.getMaxAbsorptionPerSecond(), 0);
        assertEquals(1, EnvironmentAbsorption.spareRate(afterDraw, time(0), time(1000)), 0);

        // 存档恢复只还原已提交的库存、曲线和默认账户，不重放取用。
        var restored = new InMemoryBodyStore(List.of(), store.receipts(), store.environmentQi(), store.environmentSources());
        assertEquals(afterDraw, restored.environmentSource("region").orElseThrow());
        assertEquals(drawn, restored.environmentQi());
    }

    @Test
    public void deviceDrawsRejectOverRateOverdraftStaleRevisionAndBadIntervalsWithoutPartialWrites() {
        // 每种拒绝都要让区域、曲线和默认账户保持原样，下一次合法取用照常成功。
        QiInventory stock = inventory(new Qi(Qi.QiAttribute.WOOD, 1, .01));
        EnvironmentQiSource initial = source(stock);
        BodyMessages.TimeCursor otherEpoch = time(0).toBuilder().setEpoch("epoch-2").build();
        var store = new InMemoryBodyStore(List.of(), List.of(), QiInventory.empty(), List.of(initial));
        QiInventory one = inventory(new Qi(Qi.QiAttribute.WOOD, 1, .01));
        QiInventory half = inventory(new Qi(Qi.QiAttribute.WOOD, .5, .01));

        assertEquals(FailureCode.ENVIRONMENT_BUDGET_EXCEEDED, assertThrows(MeridianErrors.IntakeRejected.class,
                () -> store.drawEnvironmentSource("region", 0, time(0), time(250), one)).code());
        assertEquals(MeridianErrors.TransportRejectionReason.INSUFFICIENT_QI, assertThrows(MeridianErrors.TransportRejected.class,
                () -> store.drawEnvironmentSource("region", 0, time(0), time(2000),
                        inventory(new Qi(Qi.QiAttribute.WOOD, 2, .01)))).reason());
        assertEquals(MeridianErrors.TransportRejectionReason.INSUFFICIENT_QI, assertThrows(MeridianErrors.TransportRejected.class,
                () -> store.drawEnvironmentSource("region", 0, time(0), time(1000),
                        inventory(new Qi(Qi.QiAttribute.WATER, .5, .01)))).reason());
        assertEquals(FailureCode.ENVIRONMENT_REVISION_CONFLICT, assertThrows(MeridianErrors.IntakeRejected.class,
                () -> store.drawEnvironmentSource("region", 1, time(0), time(1000), half)).code());
        assertEquals(FailureCode.ENVIRONMENT_UNAVAILABLE, assertThrows(MeridianErrors.IntakeRejected.class,
                () -> store.drawEnvironmentSource("missing", 0, time(0), time(1000), half)).code());
        for (var interval : List.of(List.of(time(500), time(500)), List.of(time(1500), time(2500)),
                List.of(otherEpoch, time(1000)))) {
            assertEquals(FailureCode.ENVIRONMENT_INTERVAL_INVALID, assertThrows(MeridianErrors.IntakeRejected.class,
                    () -> store.drawEnvironmentSource("region", 0, interval.get(0), interval.get(1), half)).code());
        }
        assertThrows(IllegalArgumentException.class,
                () -> store.drawEnvironmentSource("region", 0, time(0), time(1000), QiInventory.empty()));
        assertThrows(MeridianErrors.TransportRejected.class, () -> store.drawEnvironmentSource("region", 0, time(0),
                time(1000), inventory(new Qi(Qi.QiAttribute.WOOD, QiAccounting.MIN_TRANSFER_QI / 2, .01))));
        assertEquals(initial, store.environmentSource("region").orElseThrow());
        assertEquals(QiInventory.empty(), store.environmentQi());

        // 身体事务回调里取用会被外层暂存覆盖，必须拒绝。
        BodySnapshot body = ProtocolFixtures.snapshot(Realm.WANDERING_QI);
        var withBody = new InMemoryBodyStore(List.of(body), List.of(), QiInventory.empty(), List.of(initial));
        assertThrows(IllegalStateException.class, () -> withBody.transact(body.id(),
                transaction -> withBody.drawEnvironmentSource("region", 0, time(0), time(1000), half)));
        assertEquals(initial, withBody.environmentSource("region").orElseThrow());

        // 失败没有消耗版本：同一预期版本的合法取用成功。
        assertEquals(1, store.drawEnvironmentSource("region", 0, time(0), time(1000), half).getRevision());
    }

    private static EnvironmentQiUsage usage(long startedMillis, long endedMillis, double qiPerSecond) {
        // 曲线分段只比较时间边界和速率，便于直接对照合并结果。
        return EnvironmentQiUsage.newBuilder().setStartedMillis(startedMillis).setEndedMillis(endedMillis)
                .setQiPerSecond(qiPerSecond).build();
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
