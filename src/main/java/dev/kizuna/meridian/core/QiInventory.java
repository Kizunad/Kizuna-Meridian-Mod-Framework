package dev.kizuna.meridian.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一个合法账户的不可变明细。允许混相、混属性，但同种真元只能有一行。 */
public record QiInventory(List<Qi> entries) {
    public QiInventory {
        // 复制后逐项检查，避免账户建成后从外部插入未经校验的明细。
        entries = List.copyOf(entries);
        Set<Kind> kinds = new HashSet<>();
        for (Qi entry : entries) {
            DataChecks.positive(entry.amount(), "账户明细数量");
            if (!kinds.add(new Kind(entry.phase(), entry.attribute()))) {
                throw new IllegalArgumentException("同相态同属性的真元必须合并为一条明细");
            }
        }
        // 每条数值有限不保证合计有限，账户级别还需要检查溢出。
        DataChecks.nonNegative(entries.stream().mapToDouble(Qi::amount).sum(), "账户总量");
    }

    public static QiInventory empty() {
        // 空账户不预填任意相态或属性，防止无意义的零余额行。
        return new QiInventory(List.of());
    }

    public double totalQi() {
        // 属性与相态只区分明细种类，所有数量统一按元求和。
        return entries.stream().mapToDouble(Qi::amount).sum();
    }

    private record Kind(Qi.QiPhase phase, Qi.QiAttribute attribute) {
    }
}
