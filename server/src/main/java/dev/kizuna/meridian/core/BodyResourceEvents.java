package dev.kizuna.meridian.core;

import java.util.List;
import java.util.TreeSet;

/** 合并资源事件的时间顺序，避免离线批量推进改变运输与加工结果。 */
final class BodyResourceEvents {
    private BodyResourceEvents() {
        // 加工与运输共用事件队列，生理扩散只在实际资源变化后尝试发运。
    }

    record Result(List<MeridianState> meridians, List<AnatomyState.Organ> organs,
                  List<QiTransit> transits, QiInventory released) { }

    enum Boundary { INCLUDED, EXCLUDED }

    static Result advance(BodyView body, long untilMillis, Boundary boundary) {
        // 阻塞记录只在本次显式唤醒的起点尝试；后续到达和加工完成也可以腾出容量。
        boolean includeBoundary = boundary == Boundary.INCLUDED;
        long startMillis = body.settledAt().elapsedMillis();
        var eventTimes = new TreeSet<Long>();
        List<Anatomy.Connection> passiveConnections = PassiveQiDiffusion.passiveConnections(body);
        if (!passiveConnections.isEmpty() || (body.openingTask() != null && body.openingTask().active())) {
            // 首次状态变化也需要发运机会；后续调度只由真实到达或加工事件唤醒。
            eventTimes.add(startMillis);
        }
        for (var transit : body.transits()) {
            if (transit.circuit() != null) {
                continue;
            }
            long arrivalOrRetryMillis = Math.max(startMillis, transit.arrivalMillis());
            eventTimes.add(arrivalOrRetryMillis);
        }
        for (var organ : body.organStates()) {
            if (organ.process() != null) {
                long completionOrRetryMillis = Math.max(startMillis, organ.process().completionMillis());
                eventTimes.add(completionOrRetryMillis);
            }
        }
        if (body.openingTask() != null && body.openingTask().status() == OpeningTask.Status.HOLDING
                && body.openingTask().rule().holdQiPerSecond() > 0) {
            // 即使区间内没有货物事件，也要在终点结算实际维持成本。
            eventTimes.add(untilMillis);
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
        long consumedUntilMillis = startMillis;
        while (!eventTimes.isEmpty()) {
            long eventMillis = eventTimes.pollFirst();
            QiInventory holdingLoss = OpeningQiConsumption.consume(body, accounts, eventMillis - consumedUntilMillis);
            releasedQi = QiAccounting.credit(releasedQi, holdingLoss);
            consumedUntilMillis = eventMillis;
            // 同毫秒先完成加工以释放空间，再处理到达；死亡边界由调用者提前排除。
            QiInventory processingLoss = OrganProcessing.completeDue(body, accounts, eventMillis);
            releasedQi = QiAccounting.credit(releasedQi, processingLoss);
            pendingTransits = QiTransport.arrive(body, accounts, pendingTransits, eventMillis);
            pendingTransits = OpeningGuidance.dispatchDue(body, accounts, pendingTransits, eventMillis);
            pendingTransits = PassiveQiDiffusion.dispatchDue(body, accounts, pendingTransits, passiveConnections, eventMillis);
            for (QiTransit transit : pendingTransits) {
                // 新批次可在本次长区间内继续到达和扩散，分段推进与批量推进使用同一事件序列。
                long arrivalMillis = transit.arrivalMillis();
                boolean withinBoundary = arrivalMillis < untilMillis || (includeBoundary && arrivalMillis == untilMillis);
                if (transit.circuit() == null && transit.status() == QiTransit.Status.MOVING
                        && arrivalMillis > eventMillis && withinBoundary) {
                    eventTimes.add(arrivalMillis);
                }
            }
        }
        // 死亡终点排除加工与到达，但死亡之前的实际维持费用仍须计入释放账。
        QiInventory finalHoldingLoss = OpeningQiConsumption.consume(body, accounts, untilMillis - consumedUntilMillis);
        releasedQi = QiAccounting.credit(releasedQi, finalHoldingLoss);
        return new Result(accounts.meridians(), accounts.organs(), pendingTransits, releasedQi);
    }
}
