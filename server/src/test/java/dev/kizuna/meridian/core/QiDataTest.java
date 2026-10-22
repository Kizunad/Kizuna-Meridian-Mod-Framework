package dev.kizuna.meridian.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class QiDataTest {
    @Test
    public void customAttributesAndMixedPhasesKeepSeparateBalances() {
        // 同属性混相和异属性同相都应保留独立明细，包含化虚境的相干态。
        var entries = new ArrayList<>(List.of(
                new Qi(Qi.QiAttribute.of("addon:lava"), 3, 1),
                new Qi(Qi.QiAttribute.of("addon:lava"), 4, 10),
                new Qi(Qi.QiAttribute.of("addon:frost"), 5, 1),
                new Qi(Qi.QiAttribute.of("addon:lava"), 6, 1000)));
        QiInventory inventory = new QiInventory(entries);
        entries.clear();

        assertEquals(4, inventory.entries().size());
        assertEquals(18, inventory.totalQi(), 0);
        assertThrows(UnsupportedOperationException.class, () -> inventory.entries().clear());
    }

    @Test
    public void arithmeticUsesAttributeIdentityAndNeverConvertsOrOverdraws() {
        // 相同 ID 的不同属性对象可合并；相态不同或属性不同时必须显式转换。
        Qi lava = new Qi(Qi.QiAttribute.of("addon:lava"), 3, 1);
        Qi same = new Qi(Qi.QiAttribute.of("addon:lava"), 2, 1);

        assertEquals(5, lava.add(same).amount(), 0);
        assertEquals(1, lava.subtract(same).amount(), 0);
        assertEquals(3, lava.amount(), 0);
        assertThrows(IllegalArgumentException.class, () -> same.subtract(lava));
        assertThrows(IllegalArgumentException.class, () -> lava.add(new Qi(lava.attribute(), 1, .1)));
        assertThrows(IllegalArgumentException.class, () -> lava.add(new Qi(Qi.QiAttribute.FIRE, 1, 1)));
    }

    @Test
    public void sameAttributeAndPhaseMergeWithVolumeConserved() {
        // 同一相态内不同密度可合并，但数量和体积都守恒，平均密度不能跨越相态边界。
        QiInventory merged = QiInventory.combine(List.of(
                new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 2, .01))),
                new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, 3, .05)))));

        assertEquals(1, merged.entries().size());
        assertEquals(5, merged.totalQi(), 0);
        assertEquals(260, merged.volume(), 1e-12);
        assertEquals(5.0 / 260, merged.entries().get(0).density(), 1e-12);
        assertEquals(Qi.QiPhase.FREE, merged.entries().get(0).phase());
    }

    @Test
    public void duplicateRowsZerosAndNonFiniteBalancesAreRejected() {
        // 账户保持每种真元一行正数明细，拒绝会破坏总量判断的非有限数。
        Qi qi = new Qi(Qi.QiAttribute.NONE, 1, .01);
        assertThrows(IllegalArgumentException.class, () -> new QiInventory(List.of(qi, qi)));
        assertThrows(IllegalArgumentException.class, () -> new QiInventory(List.of(Qi.empty(qi.attribute(), qi.density()))));
        for (double value : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Qi(qi.attribute(), value, qi.density()));
        }
        assertEquals(0, QiInventory.empty().totalQi(), 0);
    }

    @Test
    public void decimalRoundoffDoesNotInventAnOverdraftOrNegativeBalance() {
        // 正常小数尾差可以归零，实际超额和从零账户扣款仍然拒绝。
        Qi balance = new Qi(Qi.QiAttribute.NONE, 0.3, .01);
        Qi cost = new Qi(Qi.QiAttribute.NONE, 0.1 + 0.2, .01);

        assertEquals(0, balance.subtract(cost).amount(), 0);
        assertThrows(IllegalArgumentException.class, () -> balance.subtract(new Qi(balance.attribute(), 0.30001, balance.density())));
        assertThrows(IllegalArgumentException.class, () -> Qi.empty(balance.attribute(), balance.density()).subtract(
                new Qi(balance.attribute(), Double.MIN_VALUE, balance.density())));
    }

    @Test
    public void finiteRowsCannotOverflowTheTotal() {
        // 分别检查加法、账户求和和两类经脉账户求和的溢出边界。
        Qi large = new Qi(Qi.QiAttribute.NONE, Double.MAX_VALUE, 1000);
        assertThrows(IllegalArgumentException.class, () -> large.add(large));
        assertThrows(IllegalArgumentException.class, () -> new QiInventory(List.of(large,
                new Qi(Qi.QiAttribute.FIRE, Double.MAX_VALUE, 1))));
        assertThrows(IllegalArgumentException.class, () -> new MeridianState(new Anatomy.MeridianId("channel"),
                new QiInventory(List.of(large)), new QiInventory(List.of(large)), 0, MeridianState.Condition.INTACT,
                new MeridianOpening(MeridianOpening.Stage.UNOPENED, 0)));
    }
}
