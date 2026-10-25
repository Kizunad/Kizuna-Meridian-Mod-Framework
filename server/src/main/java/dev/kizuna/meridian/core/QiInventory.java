package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 一个合法账户的不可变明细。允许混相、混属性，但同属性同相态区间只能有一行。 */
public record QiInventory(List<Qi> entries) {
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
        // 空账户不预填任意相态或属性，防止无意义的零余额行。
        return new QiInventory(List.of());
    }

    public double totalQi() {
        // 属性与相态只区分明细种类，所有数量统一按元求和。
        return entries.stream().mapToDouble(Qi::amount).sum();
    }

    public double volume() {
        // 混相库存逐条按实际密度占容，不按境界倍率估算。
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
