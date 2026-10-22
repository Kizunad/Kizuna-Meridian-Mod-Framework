package dev.kizuna.meridian.core;

import java.util.ArrayList;
import java.util.List;

/** 按实际占用体积分摊元数费用；策略投影与实际扣账共用同一个计算。 */
public final class QiConsumption {
    private QiConsumption() {
        // 只返回成本，不修改账户；调用方负责原子扣除及记录释放。
    }

    public static List<QiInventory> byVolume(List<QiInventory> inventories, double amount) {
        // 先在全部明细上计算权重，混相仍保持各自密度与属性。
        DataChecks.nonNegative(amount, "真元费用");
        var entries = new ArrayList<Qi>();
        inventories.forEach(inventory -> entries.addAll(inventory.entries()));
        double[] costs = new double[entries.size()];
        double remaining = Math.min(amount, inventories.stream().mapToDouble(QiInventory::totalQi).sum());
        boolean capped;
        do {
            double totalVolume = 0;
            for (int i = 0; i < entries.size(); i++) {
                if (costs[i] < entries.get(i).amount()) {
                    totalVolume += entries.get(i).volume();
                }
            }
            if (totalVolume == 0 || remaining == 0) {
                break;
            }
            capped = false;
            // 低密度明细先耗尽时，固定其成本后重新分配其余费用，绝不透支。
            double budget = remaining;
            for (int i = 0; i < entries.size(); i++) {
                Qi entry = entries.get(i);
                if (costs[i] < entry.amount() && budget * (entry.volume() / totalVolume) >= entry.amount()) {
                    costs[i] = entry.amount();
                    remaining = Math.max(0, remaining - entry.amount());
                    capped = true;
                }
            }
            if (!capped) {
                for (int i = 0; i < entries.size(); i++) {
                    if (costs[i] < entries.get(i).amount()) {
                        costs[i] = budget * (entries.get(i).volume() / totalVolume);
                    }
                }
            }
        } while (capped);

        // 按账户原顺序输出，空账户保留空成本，供调用方逐账户提交。
        var result = new ArrayList<QiInventory>();
        int index = 0;
        for (QiInventory inventory : inventories) {
            var charged = new ArrayList<Qi>();
            for (Qi entry : inventory.entries()) {
                double cost = costs[index++];
                if (cost > 0) {
                    charged.add(entry.withAmount(cost));
                }
            }
            result.add(new QiInventory(charged));
        }
        return List.copyOf(result);
    }
}
