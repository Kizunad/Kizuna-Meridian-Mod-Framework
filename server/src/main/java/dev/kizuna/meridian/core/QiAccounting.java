package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** 运输、加工与环境入账共用的余额操作，禁止整笔被浮点精度吞掉。 */
public final class QiAccounting {
    /** 最小可流转明细为 1e-12 元，隔离体积反算的浮点尘埃，避免其进入空账户后再次发运。 */
    public static final double MIN_TRANSFER_QI = 1e-12;

    private QiAccounting() {
        // 算术不读取身体，也不提交账户状态。
    }

    public static void validateTransferable(QiInventory cargo) {
        // 只限制新发起的取用；存档读取、合并和完整释放仍须保留低于单位的真实余额。
        for (Qi entry : cargo.entries()) {
            if (entry.amount() < MIN_TRANSFER_QI) {
                throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
            }
        }
    }

    public static QiInventory select(QiInventory inventory, QiInventory amounts) {
        // 配方要求按属性和相态区间匹配；取出的原料保持库存实际密度。
        validateTransferable(amounts);
        var selected = new java.util.ArrayList<Qi>();
        for (Qi requested : amounts.entries()) {
            Qi available = inventory.entries().stream()
                    .filter(entry -> entry.phase() == requested.phase() && entry.attribute().equals(requested.attribute()))
                    .findFirst().orElseThrow(() -> MeridianErrors.rejectTransport(TransportRejectionReason.INSUFFICIENT_QI));
            if (requested.amount() > available.amount()) {
                throw MeridianErrors.rejectTransport(TransportRejectionReason.INSUFFICIENT_QI);
            }
            selected.add(available.withAmount(requested.amount()));
        }
        return new QiInventory(selected);
    }

    public static QiInventory debit(QiInventory inventory, QiInventory cargo) {
        // 用相态和属性匹配成本，不能用其他种类的富余余额抵扣。
        var remainingByKind = new LinkedHashMap<Kind, Qi>();
        for (var entry : inventory.entries()) {
            remainingByKind.put(new Kind(entry.phase(), entry.attribute()), entry);
        }

        // 每项成本都必须有足够同种余额；只修改临时索引，任意失败都不会部分扣款。
        for (var cost : cargo.entries()) {
            var qiKind = new Kind(cost.phase(), cost.attribute());
            Qi availableQi = remainingByKind.get(qiKind);
            if (availableQi == null || cost.amount() > availableQi.amount()
                    || cost.density() != availableQi.density()) {
                throw MeridianErrors.rejectTransport(TransportRejectionReason.INSUFFICIENT_QI);
            }
            double remainingAmount = availableQi.amount() - cost.amount();
            if (remainingAmount >= availableQi.amount()) {
                // 看不见的扣除会在另一个账户造出货物，必须拒绝整个操作。
                throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
            }
            if (remainingAmount == 0) {
                remainingByKind.remove(qiKind);
            } else {
                remainingByKind.put(qiKind, availableQi.withAmount(remainingAmount));
            }
        }
        return new QiInventory(List.copyOf(remainingByKind.values()));
    }

    public static QiInventory credit(QiInventory inventory, QiInventory cargo) {
        // 同种入账先检查有限且确有增长，再合并；失败时由上层放弃整个候选状态。
        var amountByKind = new HashMap<Kind, Double>();
        for (var entry : inventory.entries()) {
            amountByKind.put(new Kind(entry.phase(), entry.attribute()), entry.amount());
        }

        // 库存中的每种真元已唯一归并；验证整笔入账不会溢出或被浮点精度吞掉。
        for (var entry : cargo.entries()) {
            var qiKind = new Kind(entry.phase(), entry.attribute());
            double previousAmount = amountByKind.getOrDefault(qiKind, 0.0);
            double creditedAmount = previousAmount + entry.amount();
            if (!Double.isFinite(creditedAmount) || creditedAmount <= previousAmount) {
                throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
            }
        }
        return QiInventory.combine(List.of(inventory, cargo));
    }

    private record Kind(Qi.QiPhase phase, Qi.QiAttribute attribute) { }
}
