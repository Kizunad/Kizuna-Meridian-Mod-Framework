package dev.kizuna.meridian.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class QiDataTest {
    @Test
    public void customAttributesAndMixedPhasesKeepSeparateBalances() {
        // 同属性混相和异属性同相都应保留独立明细，包含化虚相态。
        var entries = new ArrayList<>(List.of(
                new Qi(Qi.QiPhase.LIQUID, Qi.QiAttribute.of("addon:lava"), 3),
                new Qi(Qi.QiPhase.SOLID, Qi.QiAttribute.of("addon:lava"), 4),
                new Qi(Qi.QiPhase.LIQUID, Qi.QiAttribute.of("addon:frost"), 5),
                new Qi(Qi.QiPhase.VOID, Qi.QiAttribute.of("addon:lava"), 6)));
        QiInventory inventory = new QiInventory(entries);
        entries.clear();

        assertEquals(4, inventory.entries().size());
        assertEquals(18, inventory.totalQi(), 0);
        assertThrows(UnsupportedOperationException.class, () -> inventory.entries().clear());
    }

    @Test
    public void arithmeticUsesAttributeIdentityAndNeverConvertsOrOverdraws() {
        // 相同 ID 的不同属性对象可合并；不同相态或属性必须显式转换。
        Qi lava = new Qi(Qi.QiPhase.LIQUID, Qi.QiAttribute.of("addon:lava"), 3);
        Qi same = new Qi(Qi.QiPhase.LIQUID, Qi.QiAttribute.of("addon:lava"), 2);

        assertEquals(5, lava.add(same).amount(), 0);
        assertEquals(1, lava.subtract(same).amount(), 0);
        assertEquals(3, lava.amount(), 0);
        assertThrows(IllegalArgumentException.class, () -> same.subtract(lava));
        assertThrows(IllegalArgumentException.class, () -> lava.add(new Qi(Qi.QiPhase.GAS, lava.attribute(), 1)));
        assertThrows(IllegalArgumentException.class, () -> lava.add(new Qi(Qi.QiPhase.LIQUID, Qi.QiAttribute.FIRE, 1)));
    }

    @Test
    public void duplicateRowsZerosAndNonFiniteBalancesAreRejected() {
        // 账户保持每种真元一行正数明细，拒绝会破坏总量判断的非有限数。
        Qi qi = new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, 1);
        assertThrows(IllegalArgumentException.class, () -> new QiInventory(List.of(qi, qi)));
        assertThrows(IllegalArgumentException.class, () -> new QiInventory(List.of(Qi.empty(qi.phase(), qi.attribute()))));
        for (double value : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Qi(qi.phase(), qi.attribute(), value));
        }
        assertEquals(0, QiInventory.empty().totalQi(), 0);
    }

    @Test
    public void decimalRoundoffDoesNotInventAnOverdraftOrNegativeBalance() {
        // 正常小数尾差可以归零，实际超额和从零账户扣款仍然拒绝。
        Qi balance = new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, 0.3);
        Qi cost = new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, 0.1 + 0.2);

        assertEquals(0, balance.subtract(cost).amount(), 0);
        assertThrows(IllegalArgumentException.class, () -> balance.subtract(new Qi(balance.phase(), balance.attribute(), 0.30001)));
        assertThrows(IllegalArgumentException.class, () -> Qi.empty(balance.phase(), balance.attribute()).subtract(
                new Qi(balance.phase(), balance.attribute(), Double.MIN_VALUE)));
    }

    @Test
    public void finiteRowsCannotOverflowTheTotal() {
        // 分别检查加法、账户求和和两类经脉账户求和的溢出边界。
        Qi large = new Qi(Qi.QiPhase.FREE, Qi.QiAttribute.NONE, Double.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> large.add(large));
        assertThrows(IllegalArgumentException.class, () -> new QiInventory(List.of(large,
                new Qi(Qi.QiPhase.LIQUID, Qi.QiAttribute.FIRE, Double.MAX_VALUE))));
        assertThrows(IllegalArgumentException.class, () -> new MeridianState(new Anatomy.MeridianId("channel"),
                new QiInventory(List.of(large)), new QiInventory(List.of(large)), 0, MeridianState.Condition.INTACT));
    }
}
