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
import java.util.Optional;
import java.util.OptionalLong;

/** 经脉内腔与器官缓存间的批次运输；源、在途、目标各自持有唯一余额。 */
public final class QiTransport {
    public static final double BASE_SPEED_METERS_PER_SECOND = 1;

    private QiTransport() {
        // 运输按发运与到达事件计算，不在这里启动逐帧流体模拟或自动循环。
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
        var connections = DataChecks.index(body.structure().connections(), Anatomy.Connection::id, "连接");
        Anatomy.Connection connection = connections.get(request.connectionId());
        if (connection == null) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.ROUTE_NOT_FOUND);
        }
        var accounts = new QiAccounts(body);
        QiAccounts.Endpoint source = accounts.endpoint(connection.from());
        QiAccounts.Endpoint target = accounts.endpoint(connection.to());
        if (source == null || target == null || source.id().equals(target.id())) {
            // 组织尚无真元缓存；同一模块自搬运也不产生有效位置变化。
            throw MeridianErrors.rejectTransport(TransportRejectionReason.ROUTE_UNSUPPORTED);
        }
        if (!accounts.available(source) || !accounts.available(target)) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.ROUTE_UNAVAILABLE);
        }
        if (source.id() instanceof Anatomy.MeridianId meridianId) {
            // 生理输入可以进入未开拓经脉，主动抽调必须先建立该源经脉的意念响应。
            MeridianState sourceMeridian = body.meridianStates().stream()
                    .filter(state -> state.id().equals(meridianId)).findFirst().orElseThrow();
            if (sourceMeridian.opening().stage() != MeridianOpening.Stage.OPENED) {
                throw MeridianErrors.rejectTransport(TransportRejectionReason.MERIDIAN_UNOPENED);
            }
        }

        // 2. 汇总已有运输的占用。两端收发共用吞吐，器官内部加工不占端口吞吐。
        var usedThroughput = new HashMap<Object, Double>();
        double reservedTargetQi = 0;
        for (var transit : body.transits()) {
            Anatomy.Connection route = connections.get(transit.connectionId());
            QiAccounts.Endpoint from = accounts.endpoint(route.from());
            QiAccounts.Endpoint to = accounts.endpoint(route.to());
            if (to != null && to.id().equals(target.id())) {
                // 阻塞货物仍预留目标容量；预约仅是由在途派生的额度，不是另一份余额。
                reservedTargetQi += transit.cargo().totalQi();
            }
            if (transit.status() == QiTransit.Status.MOVING) {
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
        double sourceAvailableRate = source.throughputPerSecond() - sourceUsedRate;
        double targetAvailableRate = target.throughputPerSecond() - targetUsedRate;
        double availableThroughputPerSecond = Math.min(sourceAvailableRate, targetAvailableRate);
        if (request.throughputPerSecond() > availableThroughputPerSecond) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.THROUGHPUT_EXCEEDED);
        }
        double occupiedTargetQi = accounts.occupied(target);
        double availableTargetCapacity = target.capacity() - occupiedTargetQi - reservedTargetQi;
        if (request.cargo().totalQi() > availableTargetCapacity) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.CAPACITY_EXCEEDED);
        }

        // 3. 逐相态、逐属性扣源并计算到达时间；外壁不参与余额检查，失败不产生新快照。
        QiInventory remainingSourceQi = QiAccounting.debit(accounts.inventory(source), request.cargo());
        long transportDurationMillis = durationMillis(connection, request);
        long startMillis = body.settledAt().elapsedMillis();
        if (transportDurationMillis > Long.MAX_VALUE - startMillis) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
        }
        long arrivalMillis = startMillis + transportDurationMillis;

        // 4. 为新批次分配稳定标识，再一起保存扣源结果与独立在途记录。
        var existingTransitIds = new HashSet<String>();
        body.transits().forEach(transit -> existingTransitIds.add(transit.id()));
        String baseId = "transport-" + (body.revision() + 1);
        String transitId = baseId;
        for (int suffix = 1; existingTransitIds.contains(transitId); suffix++) {
            // 身体版本给出稳定 ID，导入的同名记录用确定性后缀避让，重试可复算同一结果。
            transitId = baseId + "-" + suffix;
        }
        var updatedTransits = new ArrayList<>(body.transits());
        updatedTransits.add(new QiTransit(transitId, connection.id(), request.cargo(),
                arrivalMillis, request.throughputPerSecond(), QiTransit.Status.MOVING));

        // 源扣除和独立在途记录共同组成候选身体；目标只预留容量，不提前入账。
        accounts.replace(source, remainingSourceQi);
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), accounts.organs(), accounts.meridians(), updatedTransits,
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions());
    }

    static List<QiTransit> arrive(BodySnapshot body, QiAccounts accounts, List<QiTransit> transits, long atMillis) {
        // 由资源事件合并器按时间调用；同一时刻按稳定 ID 入账，阻塞记录可以重新尝试。
        var connections = DataChecks.index(body.structure().connections(), Anatomy.Connection::id, "连接");
        var pendingTransits = new ArrayList<QiTransit>();
        List<QiTransit> orderedTransits = transits.stream()
                .sorted(Comparator.comparingLong(QiTransit::arrivalMillis).thenComparing(QiTransit::id))
                .toList();
        for (var transit : orderedTransits) {
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
            } else if (transit.cargo().totalQi() > availableTargetCapacity) {
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
        // 阻塞批次等待相关状态变化后显式触发结算；只为仍在移动的货物安排到达唤醒。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        if (body.vitality().dead()) {
            return Optional.empty();
        }
        OptionalLong nextArrivalMillis = body.transits().stream()
                .filter(transit -> transit.status() == QiTransit.Status.MOVING)
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
