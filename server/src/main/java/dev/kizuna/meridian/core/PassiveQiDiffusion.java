package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 可信构型声明的自然扩散；未开经脉参与生理流动，主动调用权限保持独立。 */
public final class PassiveQiDiffusion {
    private PassiveQiDiffusion() {
        // 调度只在资源变化及到达事件执行，平衡后的身体不需要轮询。
    }

    public static Optional<TimeCursor> nextBoundary(BodySnapshot body) {
        // 只有当前确实能发运的连接才安排唤醒；满载、阻塞和微小余差等待新的状态变化。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            return Optional.empty();
        }
        List<Anatomy.Connection> connections = passiveConnections(body);
        if (connections.isEmpty()) {
            return Optional.empty();
        }
        var accounts = new QiAccounts(body);
        for (Anatomy.Connection connection : connections) {
            if (plan(accounts, body.transits(), connection).isPresent()) {
                return Optional.of(body.settledAt());
            }
        }
        return Optional.empty();
    }

    static List<Anatomy.Connection> passiveConnections(BodySnapshot body) {
        // 分支按连接 ID 稳定处理，结果不依赖模板或存档列表的排列顺序。
        if (body.openingTask() != null && body.openingTask().active()) {
            // 开脉期间由专用引导接管经脉流向，避免自然压差立即把增压真元抽离。
            return List.of();
        }
        var guided = body.actions().stream().flatMap(action -> action.definition().guidedMeridianIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        var controlledPorts = body.structure().meridians().stream().filter(item -> guided.contains(item.id()))
                .flatMap(item -> item.ports().stream()).map(Anatomy.Port::id)
                .collect(java.util.stream.Collectors.toSet());
        return body.structure().connections().stream()
                .filter(connection -> connection.passiveQiFlow() != null)
                .filter(connection -> !controlledPorts.contains(connection.from()) && !controlledPorts.contains(connection.to()))
                .sorted(Comparator.comparing(connection -> connection.id().value()))
                .toList();
    }

    static List<QiTransit> dispatchDue(BodySnapshot body, QiAccounts accounts, List<QiTransit> transits,
                                      List<Anatomy.Connection> connections, long atMillis) {
        // 每次新预约马上加入当前候选账本，后续分支只能使用剩余吞吐和目标容量。
        List<QiTransit> pendingTransits = transits;
        boolean dispatched;
        do {
            // 同一事件内用尽可用批次或吞吐，避免宿主分段调用凭空提前启动额外批次。
            dispatched = false;
            for (Anatomy.Connection connection : connections) {
                Optional<QiTransport.Request> request = plan(accounts, pendingTransits, connection);
                if (request.isPresent()) {
                    pendingTransits = QiTransport.dispatchPassive(body, accounts, pendingTransits, connection,
                            request.get(), atMillis);
                    dispatched = true;
                }
            }
        } while (dispatched);
        return pendingTransits;
    }

    private static Optional<QiTransport.Request> plan(QiAccounts accounts, List<QiTransit> transits,
                                                     Anatomy.Connection connection) {
        // 1. 只沿完好的已声明通路扩散；外壁和器官加工原料不进入自然流动账户。
        QiAccounts.Endpoint source = accounts.endpoint(connection.from());
        QiAccounts.Endpoint target = accounts.endpoint(connection.to());
        if (!accounts.available(source) || !accounts.available(target)) {
            return Optional.empty();
        }
        QiInventory sourceQi = accounts.inventory(source);
        Anatomy.PassiveQiFlow flow = connection.passiveQiFlow();
        QiTransport.DispatchCapacity capacity = QiTransport.availableCapacity(accounts, transits, source, target);
        double throughputPerSecond = Math.min(flow.throughputPerSecond(), capacity.throughputPerSecond());
        if (throughputPerSecond <= 0 || sourceQi.totalQi() < flow.minimumBatchQi()
                || capacity.targetVolume() <= 0) {
            return Optional.empty();
        }

        // 2. 以局部储量占比模拟压差。目标的入向预约已经占容，源的未到货不能拿来发送。
        double sourceFraction = sourceQi.volume() / source.capacity();
        double targetFraction = 1 - capacity.targetVolume() / target.capacity();
        double fractionDifference = sourceFraction - targetFraction;
        if (fractionDifference <= 0) {
            return Optional.empty();
        }
        double smallerCapacity = Math.min(source.capacity(), target.capacity());
        double largerCapacity = Math.max(source.capacity(), target.capacity());
        double equalizingVolume = fractionDifference * smallerCapacity / (1 + smallerCapacity / largerCapacity);
        QiInventory cargo = sourceQi.take(flow.maximumBatchQi(), Math.min(equalizingVolume, capacity.targetVolume()));
        if (cargo.totalQi() < flow.minimumBatchQi()) {
            // 有限阈值避免循环图在接近平衡后产生无限小批次；余额完整留在原账户。
            return Optional.empty();
        }

        // 调度只搬运原货物，批次和耗时仍按元计，不在路上改变密度。
        return Optional.of(new QiTransport.Request(connection.id(), cargo, throughputPerSecond));
    }
}
