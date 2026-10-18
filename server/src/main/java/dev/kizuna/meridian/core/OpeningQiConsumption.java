package dev.kizuna.meridian.core;

import java.util.ArrayList;

/** 凝聚计时中的真元费用与阈值边界；消耗明细进入同一环境释放事务。 */
final class OpeningQiConsumption {
    private OpeningQiConsumption() {
        // 不保留第二份余额，费用与唤醒都从固定规则和实际内腔派生。
    }

    static QiInventory consume(BodySnapshot body, QiAccounts accounts, long millis) {
        // 在到达与加工之前扣除这一段费用；新货物只支持之后的维持，不倒填过去区间。
        OpeningTask task = body.openingTask();
        if (task == null || task.status() != OpeningTask.Status.HOLDING || millis == 0
                || task.rule().holdQiPerSecond() == 0) {
            return QiInventory.empty();
        }
        MeridianState target = accounts.meridian(task.rule().targetMeridianId());
        double consumedQi = task.rule().holdQiPerSecond() * (millis / 1000.0);
        double minimumTotal = task.rule().minimumLumenQi().totalQi();
        var costs = new ArrayList<Qi>();
        for (Qi minimum : task.rule().minimumLumenQi().entries()) {
            double costQi = consumedQi * (minimum.amount() / minimumTotal);
            // 模拟时钟最小一毫秒，极高消耗可能在最后一毫秒耗尽，仍不能透支账户。
            costQi = Math.min(costQi, matchingAmount(target.lumenQi(), minimum));
            if (costQi > 0) {
                costs.add(new Qi(minimum.phase(), minimum.attribute(), costQi));
            }
        }
        QiInventory releasedQi = new QiInventory(costs);
        QiInventory remainingQi = QiAccounting.debit(target.lumenQi(), releasedQi);
        accounts.replaceMeridian(new MeridianState(target.id(), remainingQi, target.wallQi(), target.fatigue(),
                target.condition(), target.opening()));
        return releasedQi;
    }

    private static double matchingAmount(QiInventory inventory, Qi minimum) {
        // 同属性不同相态仍是独立明细，不以总账掩盖局部短缺。
        return inventory.entries().stream().filter(qi -> qi.phase() == minimum.phase()
                        && qi.attribute().equals(minimum.attribute())).mapToDouble(Qi::amount).sum();
    }

}
