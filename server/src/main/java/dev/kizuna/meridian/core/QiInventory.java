package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 一个合法账户的不可变明细。允许混相、混属性，但同属性同相态区间只能有一行。 */
public record QiInventory(List<Qi> entries) {
    private static final QiInventory EMPTY = new QiInventory(List.of());

    public QiInventory {
        // 复制后逐项检查，避免账户建成后从外部插入未经校验的明细。
        entries = List.copyOf(entries);
        Set<Kind> kinds = new HashSet<>();
        for (Qi entry : entries) {
            DataChecks.positive(entry.amount(), "账户明细数量");
            if (!kinds.add(new Kind(entry.phase(), entry.attribute()))) {
                throw MeridianErrors.invalid(Data.DUPLICATE_QI_KIND);
            }
        }
        // 每条数值有限不保证合计有限，账户级别还需要检查溢出。
        DataChecks.nonNegative(entries.stream().mapToDouble(Qi::amount).sum(), "账户总量");
        DataChecks.nonNegative(entries.stream().mapToDouble(Qi::volume).sum(), "账户总体积");
    }

    public static QiInventory empty() {
        // 空账户不可变且没有数值，事件切段可共享已校验的空明细，避免反复构造和校验。
        return EMPTY;
    }

    public double totalQi() {
        // 属性与相态只区分明细种类，所有数量统一按元求和。
        if (entries.isEmpty()) {
            return 0;
        }
        if (entries.size() == 1) {
            return entries.get(0).amount();
        }
        return entries.stream().mapToDouble(Qi::amount).sum();
    }

    public double volume() {
        // 混相库存逐条按实际密度占容，不按境界倍率估算。
        if (entries.isEmpty()) {
            return 0;
        }
        if (entries.size() == 1) {
            return entries.get(0).volume();
        }
        return entries.stream().mapToDouble(Qi::volume).sum();
    }

    public QiInventory take(double maximumQi, double maximumVolume) {
        // 批次同时受元数吞吐和目标空间约束，每条截取都保留其原始密度。
        var cargo = new java.util.ArrayList<Qi>();
        double remainingQi = Math.max(0, maximumQi);
        double remainingVolume = Math.max(0, maximumVolume);
        for (Qi entry : entries) {
            double availableAmount = entry.amount();
            double amount = Math.min(availableAmount, Math.min(remainingQi, remainingVolume * entry.density()));
            if (amount < QiAccounting.MIN_TRANSFER_QI || availableAmount - amount == availableAmount) {
                // 尘埃不占用预算；大余额可能连高于最小单位的数量也扣不动，仍须检查实际减法。
                continue;
            }
            Qi selected = entry.withAmount(amount);
            cargo.add(selected);
            remainingQi = Math.max(0, remainingQi - amount);
            remainingVolume = Math.max(0, remainingVolume - selected.volume());
            if (remainingQi == 0 || remainingVolume == 0) {
                break;
            }
        }
        return new QiInventory(cargo);
    }

    public QiInventory takeProportionally(double maximumQi, double maximumVolume) {
        // 每条明细取同一比例，一批货里各相态的元数比例与账户相同，不先搬完排在前面的明细。
        if (entries.isEmpty()) {
            return EMPTY;
        }
        double qiBudget = Math.max(0, maximumQi);
        double volumeBudget = Math.max(0, maximumVolume);
        double totalQi = totalQi();
        double totalVolume = volume();
        var cargo = new java.util.ArrayList<Qi>();
        for (Qi entry : entries) {
            // 元数预算按元数占比分，体积预算按体积占比分；两项都与本条数量成正比，取更紧的一项。
            double qiPart = entry.amount() / totalQi;
            double volumePart = entry.volume() / totalVolume;
            double byQi = qiBudget * qiPart;
            double byVolume = volumeBudget * volumePart * entry.density();
            double availableAmount = entry.amount();
            double amount = Math.min(availableAmount, Math.min(byQi, byVolume));
            if (amount < QiAccounting.MIN_TRANSFER_QI || availableAmount - amount == availableAmount) {
                // 尘埃或扣不动的份额留在源账户，不挪给其他明细，其他明细的比例不变。
                continue;
            }
            cargo.add(entry.withAmount(amount));
        }
        return new QiInventory(cargo);
    }

    public QiInventory ofAttribute(Qi.QiAttribute attribute) {
        // 只留指定属性的明细，顺序与数量不变。
        MeridianErrors.required(attribute, Data.QI_ATTRIBUTE_REQUIRED);
        List<Qi> selected = entries.stream()
                .filter(entry -> entry.attribute().equals(attribute))
                .toList();
        return new QiInventory(selected);
    }

    public static QiInventory combine(List<QiInventory> inventories) {
        // 账户转移合并同相态同属性的数量，不在转移时隐式退化或抹掉元素属性。
        var merged = new LinkedHashMap<Kind, Qi>();
        for (var inventory : inventories) {
            for (var entry : inventory.entries()) {
                var kind = new Kind(entry.phase(), entry.attribute());
                merged.merge(kind, entry, Qi::add);
            }
        }
        // 按首次出现顺序输出，使相同输入的回执与参考环境账户顺序稳定。
        return new QiInventory(List.copyOf(merged.values()));
    }

    private record Kind(Qi.QiPhase phase, Qi.QiAttribute attribute) {
    }
}
