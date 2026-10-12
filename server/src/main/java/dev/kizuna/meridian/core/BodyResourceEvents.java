package dev.kizuna.meridian.core;

import java.util.List;
import java.util.TreeSet;

/** 合并资源事件的时间顺序，避免离线批量推进改变运输与加工结果。 */
final class BodyResourceEvents {
    private BodyResourceEvents() {
        // 只遍历已经存在的事件，不自动启动下一轮加工或循环运输。
    }

    record Result(List<MeridianState> meridians, List<AnatomyState.Organ> organs,
                  List<QiTransit> transits, QiInventory released) { }

    static Result advance(BodySnapshot body, long untilMillis, boolean includeBoundary) {
        // 阻塞记录只在本次显式唤醒的起点尝试；后续到达和加工完成也可以腾出容量。
        long startMillis = body.settledAt().elapsedMillis();
        var eventTimes = new TreeSet<Long>();
        for (var transit : body.transits()) {
            long arrivalOrRetryMillis = Math.max(startMillis, transit.arrivalMillis());
            eventTimes.add(arrivalOrRetryMillis);
        }
        for (var organ : body.organStates()) {
            if (organ.process() != null) {
                long completionOrRetryMillis = Math.max(startMillis, organ.process().completionMillis());
                eventTimes.add(completionOrRetryMillis);
            }
        }

        // 裁剪到本次推进范围；死亡时排除边界本身，保证同毫秒死亡优先。
        eventTimes.removeIf(time -> time > untilMillis || (!includeBoundary && time == untilMillis));
        if (eventTimes.isEmpty()) {
            // 纯寿命推进不复制模块索引，也不重建账户。
            return new Result(body.meridianStates(), body.organStates(), body.transits(), QiInventory.empty());
        }

        var accounts = new QiAccounts(body);
        List<QiTransit> pendingTransits = body.transits();
        QiInventory releasedQi = QiInventory.empty();
        for (long eventMillis : eventTimes) {
            // 同毫秒先完成加工以释放空间，再处理到达；死亡边界由调用者提前排除。
            QiInventory processingLoss = OrganProcessing.completeDue(body, accounts, eventMillis);
            releasedQi = QiAccounting.credit(releasedQi, processingLoss);
            pendingTransits = QiTransport.arrive(body, accounts, pendingTransits, eventMillis);
        }
        return new Result(accounts.meridians(), accounts.organs(), pendingTransits, releasedQi);
    }
}
