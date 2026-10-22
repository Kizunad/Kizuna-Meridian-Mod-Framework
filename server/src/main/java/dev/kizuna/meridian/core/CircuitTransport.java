package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 闭环只支付一次批量吞吐服务，沿途传播时间仍逐段累加。 */
public final class CircuitTransport {
    private CircuitTransport() {
        // 只提供受控在途的通用线路与预约校验，不决定内容的操作或提纯规则。
    }

    public static double durationMillis(BodyStructure structure, List<String> route,
                                        double internalMeters, double amount, double throughput) {
        // 连接阻力取瓶颈，不把同一批货物在每个节点重复排队计费。
        double distance = internalMeters;
        double resistance = 0;
        for (String id : route) {
            Anatomy.Connection connection = connection(structure, id);
            distance += connection.distance();
            resistance = Math.max(resistance, connection.resistance());
        }
        double seconds = distance / QiTransport.BASE_SPEED_METERS_PER_SECOND
                + amount * (1 + resistance) / throughput;
        DataChecks.positive(seconds, "闭环耗时");
        return Math.ceil(seconds * 1000);
    }

    public static void validate(BodyStructure structure, QiTransit transit) {
        // 连续性按模块检查：输出口和下个模块输入口不同，但必须首尾相接。
        var circuit = transit.circuit();
        List<String> route = circuit.getConnectionIdsList();
        if (route.size() < 2 || route.size() > 128 || new HashSet<>(route).size() != route.size()
                || !route.get(route.size() - 1).equals(transit.connectionId().value())
                || transit.status() != QiTransit.Status.MOVING) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
        DataChecks.nonNegative(circuit.getInternalDistanceMeters(), "脉内路程");
        DataChecks.nonNegative(circuit.getWorkMillis(), "闭环已完成时间");
        DataChecks.positive(circuit.getDurationMillis(), "闭环总时间");
        DataChecks.atMost(circuit.getWorkMillis(), circuit.getDurationMillis(), "闭环已完成时间");
        for (int index = 0; index < route.size(); index++) {
            Anatomy.Connection current = connection(structure, route.get(index));
            Anatomy.Connection following = connection(structure, route.get((index + 1) % route.size()));
            if (!meridian(structure, current.to()).equals(meridian(structure, following.from()))) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
        }
    }

    public static Set<Anatomy.MeridianId> meridians(BodyStructure structure, QiTransit transit) {
        // 首尾闭合使全部来源模块恰好覆盖整条环路；集合避免重复预约。
        var ids = new HashSet<Anatomy.MeridianId>();
        for (String id : transit.circuit().getConnectionIdsList()) {
            ids.add(meridian(structure, connection(structure, id).from()));
        }
        return Set.copyOf(ids);
    }

    static Set<Object> modules(QiAccounts accounts, QiTransit transit) {
        // 与普通运输共用账户端点，避免维护第二套吞吐索引。
        var ids = new HashSet<Object>();
        for (String id : transit.circuit().getConnectionIdsList()) {
            var connection = accounts.connection(new Anatomy.ConnectionId(id));
            ids.add(accounts.endpoint(connection.from()).id());
        }
        return ids;
    }

    public static Anatomy.MeridianId meridian(BodyStructure structure, Anatomy.PortId port) {
        // 闭环目前只支持经脉内腔，器官加工通过自己的唯一加工账户进行。
        return structure.meridians().stream().filter(item -> item.ports().stream()
                        .anyMatch(candidate -> candidate.id().equals(port)))
                .map(Anatomy.MeridianDefinition::id).findFirst()
                .orElseThrow(() -> MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY));
    }

    public static Anatomy.Connection connection(BodyStructure structure, String id) {
        // 不允许临时虚构绕过实际拓扑的边。
        return structure.connections().stream().filter(item -> item.id().value().equals(id)).findFirst()
                .orElseThrow(() -> MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY));
    }
}
