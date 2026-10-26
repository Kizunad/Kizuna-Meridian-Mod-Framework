package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejectionReason;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** 经脉内腔与器官缓存间的批次运输；源、在途、目标各自持有唯一余额。 */
public final class QiTransport {
    public static final double BASE_SPEED_METERS_PER_SECOND = 1;
    private static final double CAPACITY_TOLERANCE = 1e-9;

    private enum DispatchMode { ACTIVE, PASSIVE, OPENING_GUIDANCE }

    private QiTransport() {
        // 主动运输与生理扩散共用批次账本，移动只在发运和到达事件结算。
    }

    public record Request(Anatomy.ConnectionId connectionId, QiInventory cargo, double throughputPerSecond) {
        public Request {
            // 申请流量和货物必须明确，不能靠空批次制造调度事件。
            MeridianErrors.required(connectionId, Data.CONNECTION_ID_REQUIRED);
            MeridianErrors.required(cargo, Data.TRANSIT_QI_REQUIRED);
            DataChecks.positive(cargo.totalQi(), "发运真元");
            DataChecks.positive(throughputPerSecond, "申请吞吐");
        }
    }

    public static BodySnapshot start(BodySnapshot body, Request request) {
        // 1. 校验身体与线路。时间由外层先结算，本算子只在快照时刻发运。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        MeridianErrors.required(request, Data.TRANSPORT_REQUEST_REQUIRED);
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.REVISION_EXHAUSTED);
        }
        var accounts = new QiAccounts(body);
        Anatomy.Connection connection = accounts.connection(request.connectionId());
        if (connection == null) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.ROUTE_NOT_FOUND);
        }
        List<QiTransit> updatedTransits = dispatch(body, accounts, body.transits(), connection, request,
                body.settledAt().elapsedMillis(), DispatchMode.ACTIVE);
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), accounts.organs(), accounts.meridians(), updatedTransits,
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask(),
                body.addonProgress(), body.modifiers());
    }

    static List<QiTransit> dispatchPassive(BodyView body, QiAccounts accounts, List<QiTransit> transits,
                                          Anatomy.Connection connection, Request request, long atMillis) {
        // 生理扩散使用构型声明的通路，不申请意念主动调用权限。
        return dispatch(body, accounts, transits, connection, request, atMillis, DispatchMode.PASSIVE);
    }

    static List<QiTransit> dispatchGuided(BodyView body, QiAccounts accounts, List<QiTransit> transits,
                                         Anatomy.Connection connection, Request request, long atMillis) {
        // 专用权限仅供身体内部已受校验的开脉任务使用，网络请求没有此标志。
        if (body.openingTask() == null || !body.openingTask().active()) {
            throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
        }
        return dispatch(body, accounts, transits, connection, request, atMillis, DispatchMode.OPENING_GUIDANCE);
    }

    private static List<QiTransit> dispatch(BodyView body, QiAccounts accounts, List<QiTransit> transits,
                                          Anatomy.Connection connection, Request request, long atMillis,
                                          DispatchMode mode) {
        // 内部事件与公开意图使用同一校验和扣账顺序；被动权限只由可信构型声明。
        QiAccounts.Endpoint source = accounts.endpoint(connection.from());
        QiAccounts.Endpoint target = accounts.endpoint(connection.to());
        if (source == null || target == null || source.id().equals(target.id())) {
            // 组织尚无真元缓存；同一模块自搬运也不产生有效位置变化。
            throw MeridianErrors.rejectTransport(TransportRejectionReason.ROUTE_UNSUPPORTED);
        }
        if (!accounts.available(source) || !accounts.available(target)) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.ROUTE_UNAVAILABLE);
        }
        if (mode == DispatchMode.ACTIVE && source.id() instanceof Anatomy.MeridianId meridianId) {
            // 生理输入可以进入未开拓经脉，主动抽调必须先建立该源经脉的意念响应。
            MeridianState sourceMeridian = body.meridianStates().stream()
                    .filter(state -> state.id().equals(meridianId)).findFirst().orElseThrow();
            if (sourceMeridian.opening().stage() != MeridianOpening.Stage.OPENED) {
                throw MeridianErrors.rejectTransport(TransportRejectionReason.MERIDIAN_UNOPENED);
            }
        }
        if (mode == DispatchMode.PASSIVE && connection.passiveQiFlow() == null) {
            throw MeridianErrors.invalid(Data.PASSIVE_ROUTE_INVALID);
        }

        // 2. 汇总已有运输的占用。两端收发共用吞吐，器官内部加工不占端口吞吐。
        DispatchCapacity capacity = availableCapacity(accounts, transits, source, target);
        if (request.throughputPerSecond() > capacity.throughputPerSecond()) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.THROUGHPUT_EXCEEDED);
        }
        // 体积来自数量除以密度；边界处允许一小段浮点尾差，避免同一份容量计算在再次校验时被舍入拒绝。
        QiInventory cargo = request.cargo();
        if (cargo.volume() > capacity.targetVolume() + CAPACITY_TOLERANCE) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.CAPACITY_EXCEEDED);
        }

        // 3. 逐相态、逐属性扣源并计算到达时间；外壁不参与余额检查，失败不产生新快照。
        QiAccounting.validateTransferable(cargo);
        QiInventory remainingSourceQi = QiAccounting.debit(accounts.inventory(source), cargo);
        long transportDurationMillis = durationMillis(connection, request);
        if (transportDurationMillis > Long.MAX_VALUE - atMillis) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
        }
        long arrivalMillis = atMillis + transportDurationMillis;

        // 4. 为新批次分配稳定标识，再一起保存扣源结果与独立在途记录。
        var existingTransitIds = new HashSet<String>();
        transits.forEach(transit -> existingTransitIds.add(transit.id()));
        // 自动批次以事件时间和连接区分身份，恢复后仍可复算相同标识。
        String baseId = switch (mode) {
            case ACTIVE -> "transport-" + (body.revision() + 1);
            case PASSIVE -> "passive-" + atMillis + "-" + connection.id().value();
            case OPENING_GUIDANCE -> "opening-" + atMillis + "-" + connection.id().value();
        };
        String transitId = baseId;
        for (int suffix = 1; existingTransitIds.contains(transitId); suffix++) {
            transitId = baseId + "-" + suffix;
        }
        var updatedTransits = new ArrayList<>(transits);
        updatedTransits.add(new QiTransit(transitId, connection.id(), cargo,
                arrivalMillis, request.throughputPerSecond(), QiTransit.Status.MOVING));

        accounts.replace(source, remainingSourceQi);
        return List.copyOf(updatedTransits);
    }

    record DispatchCapacity(double throughputPerSecond, double targetVolume) { }

    public static double availableThroughputPerSecond(BodySnapshot body, Anatomy.ConnectionId connectionId) {
        // 编排器按当前共享预约选择可申请流量；读取不预留资源，最终发运仍重新校验。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        var accounts = new QiAccounts(body);
        Anatomy.Connection connection = accounts.connection(connectionId);
        if (connection == null) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.ROUTE_NOT_FOUND);
        }
        QiAccounts.Endpoint source = accounts.endpoint(connection.from());
        QiAccounts.Endpoint target = accounts.endpoint(connection.to());
        if (source == null || target == null || source.id().equals(target.id())) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.ROUTE_UNSUPPORTED);
        }
        if (body.vitality().dead() || !accounts.available(source) || !accounts.available(target)) {
            return 0;
        }
        return Math.max(0, availableCapacity(accounts, body.transits(), source, target).throughputPerSecond());
    }

    static DispatchCapacity availableCapacity(QiAccounts accounts, List<QiTransit> transits,
                                              QiAccounts.Endpoint source, QiAccounts.Endpoint target) {
        // 主动和被动发送读取同一预约事实，不能分别计算出两份可用吞吐和目标空间。
        return availableCapacity(accounts, transits, source, target, Map.of());
    }

    static DispatchCapacity availableCapacity(QiAccounts accounts, List<QiTransit> transits,
                                              QiAccounts.Endpoint source, QiAccounts.Endpoint target,
                                              Map<Object, Double> reservedThroughput) {
        // 自动引导可让出部分空闲吞吐；预留只限制本次调度，不创建在途或额外资源账户。
        var usedThroughput = new HashMap<Object, Double>();
        double reservedTargetVolume = 0;
        for (var transit : transits) {
            Anatomy.Connection route = accounts.connection(transit.connectionId());
            QiAccounts.Endpoint from = accounts.endpoint(route.from());
            QiAccounts.Endpoint to = accounts.endpoint(route.to());
            if (to != null && to.id().equals(target.id())) {
                // 阻塞货物仍预留目标容量；预约仅是由在途派生的额度，不是另一份余额。
                reservedTargetVolume += transit.cargo().volume();
            }
            if (transit.status() == QiTransit.Status.MOVING) {
                if (transit.circuit() != null) {
                    // 一个闭环只预约每个模块一次，普通运输与它共享相同吞吐预算。
                    for (Object module : CircuitTransport.modules(accounts, transit)) {
                        usedThroughput.merge(module, transit.throughputPerSecond(), Double::sum);
                    }
                    continue;
                }
                if (from != null) {
                    usedThroughput.merge(from.id(), transit.throughputPerSecond(), Double::sum);
                }
                if (to != null && (from == null || !from.id().equals(to.id()))) {
                    usedThroughput.merge(to.id(), transit.throughputPerSecond(), Double::sum);
                }
            }
        }

        // 任意一端的剩余吞吐不足，都不能接受整个批次的流量申请。
        double sourceUsedRate = usedThroughput.getOrDefault(source.id(), 0.0);
        double targetUsedRate = usedThroughput.getOrDefault(target.id(), 0.0);
        double sourceAvailableRate = source.throughputPerSecond() - sourceUsedRate
                - reservedThroughput.getOrDefault(source.id(), 0.0);
        double targetAvailableRate = target.throughputPerSecond() - targetUsedRate
                - reservedThroughput.getOrDefault(target.id(), 0.0);
        double availableThroughputPerSecond = Math.min(sourceAvailableRate, targetAvailableRate);
        double occupiedTargetVolume = accounts.occupied(target);
        double availableTargetCapacity = target.capacity() - occupiedTargetVolume - reservedTargetVolume;
        return new DispatchCapacity(availableThroughputPerSecond, availableTargetCapacity);
    }

    static List<QiTransit> arrive(BodyView body, QiAccounts accounts, List<QiTransit> transits, long atMillis) {
        // 由资源事件合并器按时间调用；同一时刻按稳定 ID 入账，阻塞记录可以重新尝试。
        var connections = DataChecks.index(body.structure().connections(), Anatomy.Connection::id, "连接");
        var pendingTransits = new ArrayList<QiTransit>();
        List<QiTransit> orderedTransits = transits.stream()
                .sorted(Comparator.comparingLong(QiTransit::arrivalMillis).thenComparing(QiTransit::id))
                .toList();
        for (var transit : orderedTransits) {
            if (transit.circuit() != null) {
                // 闭环的结束、提纯和返回必须由可信活动在同一事务结算。
                pendingTransits.add(transit);
                continue;
            }
            if (transit.arrivalMillis() > atMillis) {
                pendingTransits.add(transit);
                continue;
            }
            Anatomy.Connection connection = connections.get(transit.connectionId());
            QiAccounts.Endpoint source = accounts.endpoint(connection.from());
            QiAccounts.Endpoint target = accounts.endpoint(connection.to());
            if (source == null || target == null || source.id().equals(target.id())) {
                // 数据可描述其他模块的线路，未实现的账户类型仍不能假装已经到达。
                throw MeridianErrors.rejectTime(TimeRejectionReason.PENDING_TIME_EVENT);
            }
            // 先检查通路能否使用，再检查目标是否能整批接收；失败时保留在途货物。
            double availableTargetCapacity = target.capacity() - accounts.occupied(target);
            QiTransit.Status blockedStatus = null;
            if (!accounts.available(source) || !accounts.available(target)) {
                blockedStatus = QiTransit.Status.BLOCKED_ROUTE;
            } else if (transit.cargo().volume() > availableTargetCapacity + CAPACITY_TOLERANCE) {
                blockedStatus = QiTransit.Status.BLOCKED_CAPACITY;
            }
            if (blockedStatus != null) {
                // 在途保留唯一货物所有权，已到达但受阻不再消耗输送吞吐，也不安排每毫秒重试。
                pendingTransits.add(new QiTransit(transit.id(), transit.connectionId(), transit.cargo(),
                        transit.arrivalMillis(), transit.throughputPerSecond(), blockedStatus));
                continue;
            }

            // 到达批次只能整批入账；成功即移除在途，保持相态、属性和外壁余额。
            QiInventory receivedQi = QiAccounting.credit(accounts.inventory(target), transit.cargo());
            accounts.replace(target, receivedQi);
        }
        // 模块仍按原构型顺序输出；在途保留原列表顺序，避免纯排序造成无意义状态变化。
        var pendingById = DataChecks.index(pendingTransits, QiTransit::id, "在途记录");
        return transits.stream()
                .filter(transit -> pendingById.containsKey(transit.id()))
                .map(transit -> pendingById.get(transit.id()))
                .toList();
    }

    public static Optional<TimeCursor> nextArrivalBoundary(BodySnapshot body) {
        // 外部调度和内部推进按同一规则选择下个到达。
        return nextArrivalBoundary((BodyView) body);
    }

    static Optional<TimeCursor> nextArrivalBoundary(BodyView body) {
        // 阻塞批次等待相关状态变化后显式触发结算；只为仍在移动的货物安排到达唤醒。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        if (body.vitality().dead()) {
            return Optional.empty();
        }
        OptionalLong nextArrivalMillis = body.transits().stream()
                .filter(transit -> transit.circuit() == null && transit.status() == QiTransit.Status.MOVING)
                .mapToLong(QiTransit::arrivalMillis)
                .min();
        if (nextArrivalMillis.isEmpty()) {
            return Optional.empty();
        }
        var arrivalAt = new TimeCursor(body.settledAt().domain(), body.settledAt().epoch(),
                nextArrivalMillis.getAsLong());
        return Optional.of(arrivalAt);
    }

    private static long durationMillis(Anatomy.Connection connection, Request request) {
        // 批次先完成吞吐服务再传播；阻力降低有效吞吐，所有相态使用相同公式。
        // delay = ceil(1000 × [距离/基础速度 + 元数×(1+阻力)/预约吞吐])，最少一毫秒。
        BigDecimal throughputPerSecond = BigDecimal.valueOf(request.throughputPerSecond());
        BigDecimal travelSeconds = BigDecimal.valueOf(connection.distance())
                .divide(BigDecimal.valueOf(BASE_SPEED_METERS_PER_SECOND));
        BigDecimal resistanceMultiplier = BigDecimal.ONE.add(BigDecimal.valueOf(connection.resistance()));
        BigDecimal transportWork = BigDecimal.valueOf(request.cargo().totalQi()).multiply(resistanceMultiplier);

        // 合并传播时间与吞吐耗时后才除以流量、向上取整，避免提前舍入改变到达毫秒。
        BigDecimal durationNumerator = travelSeconds.multiply(throughputPerSecond)
                .add(transportWork)
                .multiply(BigDecimal.valueOf(1000));
        BigDecimal durationMillis = durationNumerator.divide(throughputPerSecond, 0, RoundingMode.CEILING);
        if (durationMillis.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
        }
        return Math.max(1, durationMillis.longValueExact());
    }
}
