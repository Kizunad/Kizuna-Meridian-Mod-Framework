package dev.kizuna.meridian.core;

import java.util.ArrayList;

/** 凝聚计时中的真元费用与阈值边界；消耗明细进入同一环境释放事务。 */
final class OpeningQiConsumption {
    private OpeningQiConsumption() {
        // 不保留第二份余额，费用与唤醒都从固定规则和实际内腔派生。
    }

    static QiInventory consume(BodyView body, QiAccounts accounts, long millis) {
        // 在到达与加工之前扣除这一段费用；新货物只支持之后的维持，不倒填过去区间。
        OpeningTask task = body.openingTask();
        if (task == null || task.status() != OpeningTask.Status.HOLDING || millis == 0
                || task.rule().holdQiPerSecond() == 0) {
            return QiInventory.empty();
        }
        var states = accounts.meridians();
        var costs = QiConsumption.byVolume(states.stream().map(MeridianState::lumenQi).toList(),
                task.rule().holdQiPerSecond() * (millis / 1000.0));
        for (int i = 0; i < states.size(); i++) {
            MeridianState state = states.get(i);
            QiInventory remaining = QiAccounting.debit(state.lumenQi(), costs.get(i));
            accounts.replaceMeridian(new MeridianState(state.id(), remaining, state.wallQi(),
                    state.fatigue(), state.condition(), state.opening()));
        }
        return QiInventory.combine(costs);
    }

}
