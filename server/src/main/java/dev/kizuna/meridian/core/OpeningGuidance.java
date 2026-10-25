package dev.kizuna.meridian.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 专用开脉引导：在真实有向经脉图上逐段收集真元，使用既有在途账本。 */
final class OpeningGuidance {
    private OpeningGuidance() {
        // 路由只在资源变化后重算，不保存第二份库存或按渲染帧移动货物。
    }

    private record Dispatch(Anatomy.Connection connection, QiTransport.Request request) { }

    static boolean canDispatch(BodySnapshot body) {
        // 满载、断路和吞吐占用时不安排空唤醒。
        return plan(body, new QiAccounts(body), body.transits()).isPresent();
    }

    static List<QiTransit> dispatchDue(BodySnapshot body, QiAccounts accounts, List<QiTransit> transits, long atMillis) {
        // 每个新批次立即占用共享吞吐和目标容量，后续路径只能申请剩余额度。
        List<QiTransit> pending = transits;
        Optional<Dispatch> dispatch = plan(body, accounts, pending);
        while (dispatch.isPresent()) {
            Dispatch selected = dispatch.get();
            pending = QiTransport.dispatchGuided(body, accounts, pending, selected.connection(), selected.request(), atMillis);
            dispatch = plan(body, accounts, pending);
        }
        return pending;
    }

    private static Optional<Dispatch> plan(BodySnapshot body, QiAccounts accounts, List<QiTransit> transits) {
        // 先满足目标，再补其他参与脉；组外经脉可以贡献真实内腔余额。
        OpeningTask task = body.openingTask();
        if (task == null || !task.active() || task.rule().guidanceQiPerSecond() == 0) {
            return Optional.empty();
        }
        var routes = new ArrayList<Anatomy.Connection>();
        for (Anatomy.Connection connection : body.structure().connections()) {
            QiAccounts.Endpoint from = accounts.endpoint(connection.from());
            QiAccounts.Endpoint to = accounts.endpoint(connection.to());
            if (from != null && to != null && from.id() instanceof Anatomy.MeridianId source
                    && to.id() instanceof Anatomy.MeridianId target && !source.equals(target)
                    && accounts.available(from) && accounts.available(to)) {
                routes.add(connection);
            }
        }
        routes.sort(Comparator.comparing(connection -> connection.id().value()));
        Map<Object, Double> supplyReservations = organSupplyReservations(body, accounts, transits);
        var goals = new ArrayList<>(task.participants());
        goals.sort(Comparator.comparing(limit -> !limit.meridianId().equals(task.rule().targetMeridianId())));
        for (MeridianOpeningRule.GuidanceTarget goal : goals) {
            Optional<Dispatch> dispatch = planToGoal(task, accounts, transits, routes, supplyReservations, goal);
            if (dispatch.isPresent()) {
                return dispatch;
            }
        }
        return Optional.empty();
    }

    private static Optional<Dispatch> planToGoal(OpeningTask task, QiAccounts accounts, List<QiTransit> transits,
                                                List<Anatomy.Connection> routes, Map<Object, Double> supplyReservations,
                                                MeridianOpeningRule.GuidanceTarget goal) {
        // 引导只补足稳定空间内的目标占用；已在途的货物也预留该空间。
        double desiredVolume = goal.volume();
        double storedVolume = accounts.meridian(goal.meridianId()).lumenQi().volume();
        if (storedVolume >= desiredVolume - 1e-9) {
            return Optional.empty();
        }

        // 反向搜索给每个源固定无环下一跳；经脉原图仍可包含循环。
        Map<Anatomy.MeridianId, Anatomy.Connection> nextHop = routesTo(accounts, routes, goal.meridianId());
        for (Anatomy.Connection route : routes) {
            QiAccounts.Endpoint source = accounts.endpoint(route.from());
            QiAccounts.Endpoint target = accounts.endpoint(route.to());
            if (nextHop.get(source.id()) != route) {
                continue;
            }
            double retainedVolume = task.participants().stream()
                    .filter(limit -> limit.meridianId().equals(source.id()))
                    .mapToDouble(MeridianOpeningRule.GuidanceTarget::volume).findFirst().orElse(0);
            double movableVolume = accounts.inventory(source).volume() - retainedVolume;
            QiTransport.DispatchCapacity capacity = QiTransport.availableCapacity(accounts, transits, source, target,
                    supplyReservations);
            double rate = Math.min(task.rule().guidanceQiPerSecond(), capacity.throughputPerSecond());
            double availableVolume = Math.min(movableVolume, capacity.targetVolume());
            if (target.id().equals(goal.meridianId())) {
                double incomingVolume = target.capacity() - accounts.occupied(target) - capacity.targetVolume();
                availableVolume = Math.min(availableVolume,
                        desiredVolume - accounts.inventory(target).volume() - incomingVolume);
            }
            if (rate > 1e-9 && availableVolume > 1e-9) {
                QiInventory cargo = accounts.inventory(source).take(task.rule().guidanceBatchQi(), availableVolume);
                if (cargo.totalQi() > 1e-9) {
                    return Optional.of(new Dispatch(route, new QiTransport.Request(route.id(), cargo, rate)));
                }
            }
        }
        return Optional.empty();
    }

    private static Map<Object, Double> organSupplyReservations(BodySnapshot body, QiAccounts accounts,
                                                              List<QiTransit> transits) {
        // 有器官缓存等候输入时，为入口经脉让出至多一半吞吐；另一半仍能抽走存量腾出空间。
        // 不能只降低单批引导速度，否则同一事件的下一批会再次吃满剩余吞吐。
        var reservations = new HashMap<Object, Double>();
        for (Anatomy.Connection connection : body.structure().connections()) {
            QiAccounts.Endpoint source = accounts.endpoint(connection.from());
            QiAccounts.Endpoint target = accounts.endpoint(connection.to());
            if (source == null || target == null || !(source.id() instanceof Anatomy.OrganId)
                    || !(target.id() instanceof Anatomy.MeridianId)
                    || !accounts.available(source) || !accounts.available(target)
                    || accounts.inventory(source).totalQi() <= 1e-9) {
                continue;
            }
            double reservedRate = Math.min(target.throughputPerSecond() / 2, source.throughputPerSecond());
            reservations.merge(target.id(), reservedRate, Math::max);
        }

        // 正在输入的器官批次已经占用共享吞吐，只保留尚未使用的份额，避免重复扣减。
        for (QiTransit transit : transits) {
            if (transit.status() != QiTransit.Status.MOVING) {
                continue;
            }
            Anatomy.Connection connection = accounts.connection(transit.connectionId());
            QiAccounts.Endpoint source = accounts.endpoint(connection.from());
            QiAccounts.Endpoint target = accounts.endpoint(connection.to());
            if (source != null && target != null && source.id() instanceof Anatomy.OrganId) {
                reservations.computeIfPresent(target.id(), (id, rate) -> Math.max(0, rate - transit.throughputPerSecond()));
            }
        }
        return reservations;
    }

    private static Map<Anatomy.MeridianId, Anatomy.Connection> routesTo(QiAccounts accounts,
                                                                     List<Anatomy.Connection> routes,
                                                                     Anatomy.MeridianId target) {
        // 首次访问固定下一跳，使相同状态在保存恢复后选择相同线路。
        var nextHop = new HashMap<Anatomy.MeridianId, Anatomy.Connection>();
        var visited = new HashSet<Anatomy.MeridianId>();
        var pending = new ArrayDeque<Anatomy.MeridianId>();
        visited.add(target);
        pending.add(target);
        while (!pending.isEmpty()) {
            Anatomy.MeridianId destination = pending.removeFirst();
            for (Anatomy.Connection route : routes) {
                Anatomy.MeridianId source = (Anatomy.MeridianId) accounts.endpoint(route.from()).id();
                if (accounts.endpoint(route.to()).id().equals(destination) && visited.add(source)) {
                    nextHop.put(source, route);
                    pending.addLast(source);
                }
            }
        }
        return nextHop;
    }

}
