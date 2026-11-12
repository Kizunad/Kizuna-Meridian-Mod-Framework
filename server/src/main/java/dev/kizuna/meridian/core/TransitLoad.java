package dev.kizuna.meridian.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份在途列表对各端点的占用：已用吞吐和预留的目标容量。
 * 自然扩散一次事件里要对几十条连接反复试发运，每次都重扫全部在途太慢，所以先汇总一遍，发出新批次时再补记那一批。
 * 汇总严格按在途列表的顺序累加，和逐次重扫得到的数逐位相同。
 * 这份汇总只对建它时的那份列表、加上之后用 {@link #add} 补记的批次有效；列表有别的变化（到达、阻塞）必须重建。
 */
final class TransitLoad {
    private final Map<Object, Double> usedThroughput = new HashMap<>();
    private final Map<Object, Double> reservedVolume = new HashMap<>();

    private TransitLoad() {
        // 只经 of 建立，保证每批在途都按列表顺序记入。
    }

    static TransitLoad of(QiAccounts accounts, List<QiTransit> transits) {
        // 主动和被动发送读取同一预约事实，不能分别计算出两份可用吞吐和目标空间。
        var load = new TransitLoad();
        for (QiTransit transit : transits) {
            load.add(accounts, transit);
        }
        return load;
    }

    void add(QiAccounts accounts, QiTransit transit) {
        // 新批次排在列表末尾，补记的顺序与重扫整份列表一致。
        Anatomy.Connection route = accounts.connection(transit.connectionId());
        QiAccounts.Endpoint from = accounts.endpoint(route.from());
        QiAccounts.Endpoint to = accounts.endpoint(route.to());
        if (to != null) {
            // 阻塞货物仍预留目标容量；预约仅是由在途派生的额度，不是另一份余额。
            reservedVolume.merge(to.id(), transit.cargo().volume(), Double::sum);
        }
        if (transit.status() != QiTransit.Status.MOVING) {
            return;
        }
        if (transit.circuit() != null) {
            // 一个闭环只预约每个模块一次，普通运输与它共享相同吞吐预算。
            for (Object module : CircuitTransport.modules(accounts, transit)) {
                usedThroughput.merge(module, transit.throughputPerSecond(), Double::sum);
            }
            return;
        }
        if (from != null) {
            usedThroughput.merge(from.id(), transit.throughputPerSecond(), Double::sum);
        }
        if (to != null && (from == null || !from.id().equals(to.id()))) {
            usedThroughput.merge(to.id(), transit.throughputPerSecond(), Double::sum);
        }
    }

    double usedThroughput(QiAccounts.Endpoint endpoint) {
        // 没有在途经过的端点吞吐未被占用。
        return usedThroughput.getOrDefault(endpoint.id(), 0.0);
    }

    double reservedVolume(QiAccounts.Endpoint target) {
        // 没有在途驶向的端点没有预留。
        return reservedVolume.getOrDefault(target.id(), 0.0);
    }
}
