package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** 取货整体回归：数量、体积和可表示扣款必须同时满足。 */
public class QiInventoryTest {
    private static final double GAS_DENSITY = 0.1;
    private static final double SOLID_DENSITY = 10;
    private static final double VOLUME_LIMIT = 0.7;
    private static final double INVISIBLE_COST = Math.ulp(1.0) * 0.25;
    private static final double LARGE_BALANCE = 1e16;
    private static final double ARRIVAL_EARTH_QI = 0.001259916539660459;
    private static final double ARRIVAL_EARTH_DENSITY = 0.011417225561026473;
    private static final double ARRIVAL_DUST_QI = 1.6686408809637269e-19;
    private static final double ARRIVAL_DUST_DENSITY = 0.012023824719557466;
    private static final double TARGET_EARTH_QI = 0.01278233693638232;
    private static final double TARGET_EARTH_DENSITY = 0.01125309958988419;
    private static final double TARGET_NONE_QI = 0.0024323750517362013;
    private static final double TARGET_NONE_DENSITY = 0.016935334172379305;

    @Test
    public void volumeRoundoffCannotAddAnUndebitableSecondAttribute() {
        // 第一项耗尽体积后留下的正尾差，不能变成源余额扣不动的第二项货物。
        Qi fire = new Qi(Qi.QiAttribute.FIRE, 1, GAS_DENSITY);
        Qi wood = new Qi(Qi.QiAttribute.WOOD, 1, GAS_DENSITY);
        QiInventory stock = new QiInventory(List.of(fire, wood));
        QiInventory cargo = stock.take(1, VOLUME_LIMIT);
        Qi selectedFire = fire.withAmount(VOLUME_LIMIT * GAS_DENSITY);
        assertEquals(List.of(selectedFire), cargo.entries());
        assertTrue(cargo.totalQi() <= 1);
        assertTrue(cargo.volume() <= VOLUME_LIMIT);

        QiInventory remaining = QiAccounting.debit(stock, cargo);
        List<Qi> remainingEntries = remaining.entries();
        assertEquals(List.of(fire, wood), stock.entries());
        assertEquals(wood, remainingEntries.get(1));
        assertEquals(fire.amount() - selectedFire.amount(), remainingEntries.get(0).amount(), 0);
    }

    @Test
    public void quantityVolumeExhaustionAndMixedPhaseOrdersRemainSupported() {
        // 单属性、混属性、同属性混相及两种排序都使用同一套预算与扣款约束。
        Qi fire = new Qi(Qi.QiAttribute.FIRE, 1, GAS_DENSITY);
        Qi wood = new Qi(Qi.QiAttribute.WOOD, 1, GAS_DENSITY);
        Qi solidFire = new Qi(Qi.QiAttribute.FIRE, 1, SOLID_DENSITY);
        for (List<Qi> entries : List.of(List.of(fire), List.of(fire, wood), List.of(wood, fire),
                List.of(fire, solidFire), List.of(solidFire, fire))) {
            QiInventory stock = new QiInventory(entries);
            double initialQi = stock.totalQi();
            double initialVolume = stock.volume();
            QiInventory volumeBound = stock.take(1, VOLUME_LIMIT);
            QiInventory quantityBound = stock.take(VOLUME_LIMIT, initialVolume);
            assertTrue(volumeBound.totalQi() <= 1);
            assertTrue(volumeBound.volume() <= VOLUME_LIMIT);
            assertTrue(quantityBound.volume() <= initialVolume);
            assertEquals(List.of(entries.get(0).withAmount(VOLUME_LIMIT)), quantityBound.entries());
            QiInventory volumeRemainder = QiAccounting.debit(stock, volumeBound);
            QiInventory quantityRemainder = QiAccounting.debit(stock, quantityBound);
            double quantityTolerance = Math.ulp(initialQi);
            assertEquals(initialQi, volumeRemainder.totalQi() + volumeBound.totalQi(), quantityTolerance);
            assertEquals(initialQi, quantityRemainder.totalQi() + quantityBound.totalQi(), quantityTolerance);
            QiInventory exhausted = stock.take(initialQi, Double.POSITIVE_INFINITY);
            assertEquals(stock, exhausted);
            assertEquals(QiInventory.empty(), QiAccounting.debit(stock, exhausted));
            assertEquals(QiInventory.empty(), stock.take(0, initialVolume));
            assertEquals(QiInventory.empty(), stock.take(initialQi, 0));
        }
    }

    @Test
    public void skippedCandidateLeavesBothBudgetsForASmallerBalance() {
        // 可扣性取决于源余额的精度；第一项跳过后，后续小余额仍可消耗完整预算。
        Qi large = new Qi(Qi.QiAttribute.FIRE, LARGE_BALANCE, 1);
        Qi small = new Qi(Qi.QiAttribute.WOOD, QiAccounting.MIN_TRANSFER_QI, 1);
        QiInventory stock = new QiInventory(List.of(large, small));
        double volumeLimit = small.volume();
        QiInventory cargo = stock.take(QiAccounting.MIN_TRANSFER_QI, volumeLimit);
        assertEquals(List.of(small), cargo.entries());
        assertEquals(List.of(large), QiAccounting.debit(stock, cargo).entries());
        assertEquals(QiInventory.empty(), new QiInventory(List.of(large))
                .take(QiAccounting.MIN_TRANSFER_QI, volumeLimit));
    }

    @Test
    public void quantumBoundaryKeepsDustAndCombinesItWithoutRoundingAwayResources() {
        // 恰好一个单位可扣；低于单位的旧余额原样保留，两笔合并达到单位后才能流转。
        double belowUnit = Math.nextDown(QiAccounting.MIN_TRANSFER_QI);
        Qi dust = new Qi(Qi.QiAttribute.FIRE, belowUnit, 1);
        Qi unit = new Qi(Qi.QiAttribute.WOOD, QiAccounting.MIN_TRANSFER_QI, 1);
        QiInventory stock = new QiInventory(List.of(dust, unit));
        // 空账户入账也必须保留低于流转单位的旧明细，空货物不能改变任何余额。
        QiInventory empty = QiInventory.empty();
        assertEquals(stock, QiAccounting.credit(empty, stock));
        assertEquals(stock, QiAccounting.credit(stock, empty));
        assertEquals(0, empty.volume(), 0);
        QiInventory cargo = stock.take(QiAccounting.MIN_TRANSFER_QI, QiAccounting.MIN_TRANSFER_QI);
        assertEquals(List.of(unit), cargo.entries());
        QiInventory remaining = QiAccounting.debit(stock, cargo);
        assertEquals(List.of(dust), remaining.entries());
        assertEquals(QiInventory.empty(), remaining.take(1, 1));
        QiInventory combined = QiInventory.combine(List.of(remaining, remaining));
        assertEquals(belowUnit + belowUnit, combined.totalQi(), 0);
        assertEquals(combined, combined.take(1, 1));
        assertEquals(QiInventory.empty(), QiAccounting.debit(combined, combined));
    }

    @Test
    public void arrivalCaseDustRemainsAtSourceWhileNormalCargoCanBeCredited() {
        // 真实到货输入中的无属性尘埃可从旧小账户扣尽，却无法增加已有目标余额。
        Qi earth = new Qi(Qi.QiAttribute.EARTH, ARRIVAL_EARTH_QI, ARRIVAL_EARTH_DENSITY);
        Qi dust = new Qi(Qi.QiAttribute.NONE, ARRIVAL_DUST_QI, ARRIVAL_DUST_DENSITY);
        QiInventory stock = new QiInventory(List.of(earth, dust));
        QiInventory target = new QiInventory(List.of(
                new Qi(Qi.QiAttribute.EARTH, TARGET_EARTH_QI, TARGET_EARTH_DENSITY),
                new Qi(Qi.QiAttribute.NONE, TARGET_NONE_QI, TARGET_NONE_DENSITY)));
        var rejected = assertThrows(MeridianErrors.TransportRejected.class, () -> QiAccounting.credit(target, stock));
        assertEquals(MeridianErrors.TransportRejectionReason.NUMERIC_LIMIT, rejected.reason());

        QiInventory cargo = stock.take(1, 1);
        assertEquals(List.of(earth), cargo.entries());
        assertEquals(List.of(dust), QiAccounting.debit(stock, cargo).entries());
        List<Qi> credited = QiAccounting.credit(target, cargo).entries();
        assertEquals(TARGET_EARTH_QI + ARRIVAL_EARTH_QI, credited.get(0).amount(), 0);
        assertEquals(target.entries().get(1), credited.get(1));
    }

    @Test
    public void directInvisibleDebitStillRejectsTheWholeCargo() {
        // 调用方绕过取货直接提交微量扣款时，账本继续拒绝，且正常明细也不发生部分扣款。
        Qi fire = new Qi(Qi.QiAttribute.FIRE, 1, GAS_DENSITY);
        Qi wood = new Qi(Qi.QiAttribute.WOOD, 1, GAS_DENSITY);
        QiInventory stock = new QiInventory(List.of(fire, wood));
        QiInventory cargo = new QiInventory(List.of(fire.withAmount(VOLUME_LIMIT), wood.withAmount(INVISIBLE_COST)));
        var rejected = assertThrows(MeridianErrors.TransportRejected.class, () -> QiAccounting.debit(stock, cargo));
        assertEquals(MeridianErrors.TransportRejectionReason.NUMERIC_LIMIT, rejected.reason());
        assertEquals(List.of(fire, wood), stock.entries());
    }
}
