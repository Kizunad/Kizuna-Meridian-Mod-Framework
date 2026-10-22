package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 构型及当前结构参数；不含任何角色余额，也不要求人形或固定经脉数量。 */
public record BodyStructure(List<Anatomy.PartDefinition> parts,
                            List<Anatomy.TissueDefinition> tissues,
                            List<Anatomy.OrganDefinition> organs,
                            List<Anatomy.MeridianDefinition> meridians,
                            List<Anatomy.Connection> connections) {
    public BodyStructure {
        // 结构既可供模板复用也可供角色独占，必须切断外部可变列表引用。
        parts = List.copyOf(parts);
        tissues = List.copyOf(tissues);
        organs = List.copyOf(organs);
        meridians = List.copyOf(meridians);
        connections = List.copyOf(connections);

        // 各类模块分别保证 ID 唯一；身体至少有一个部位，但可以没有经脉。
        var partIndex = DataChecks.index(parts, Anatomy.PartDefinition::id, "部位");
        DataChecks.index(tissues, Anatomy.TissueDefinition::id, "组织");
        DataChecks.index(organs, Anatomy.OrganDefinition::id, "器官");
        DataChecks.index(meridians, Anatomy.MeridianDefinition::id, "经脉");
        DataChecks.index(connections, Anatomy.Connection::id, "连接");
        if (parts.isEmpty()) {
            throw MeridianErrors.invalid(Data.NO_PARTS);
        }

        // 检查模块的部位归属，并建立整具身体唯一的端口索引。
        Map<Anatomy.PortId, Anatomy.Port> ports = new HashMap<>();
        Map<Anatomy.PortId, Anatomy.MeridianId> meridianPorts = new HashMap<>();
        for (var tissue : tissues) {
            requirePart(partIndex, tissue.partId());
            addPorts(ports, tissue.ports());
        }
        for (var organ : organs) {
            requirePart(partIndex, organ.partId());
            addPorts(ports, organ.ports());
        }
        for (var meridian : meridians) {
            meridian.partIds().forEach(part -> requirePart(partIndex, part));
            addPorts(ports, meridian.ports());
            meridian.ports().forEach(port -> meridianPorts.put(port.id(), meridian.id()));
        }

        // 循环是合法经脉拓扑；此处只拒绝悬空、方向错误和重复边。
        Set<List<Anatomy.PortId>> edges = new HashSet<>();
        for (var connection : connections) {
            var from = ports.get(connection.from());
            var to = ports.get(connection.to());
            if (from == null || to == null) {
                throw MeridianErrors.invalid(Data.missingConnectionPort(connection.id()));
            }
            if (from.direction() == Anatomy.PortDirection.INPUT
                    || to.direction() == Anatomy.PortDirection.OUTPUT) {
                throw MeridianErrors.invalid(Data.wrongConnectionDirection(connection.id()));
            }
            if (!edges.add(List.of(connection.from(), connection.to()))) {
                throw MeridianErrors.invalid(Data.duplicateConnection(connection.id()));
            }
            if (connection.passiveQiFlow() != null) {
                // 被动出元仅属于内容声明的经脉生理连接，不能把器官原料缓存当作扩散源。
                Anatomy.MeridianId sourceMeridian = meridianPorts.get(connection.from());
                Anatomy.MeridianId targetMeridian = meridianPorts.get(connection.to());
                if (sourceMeridian == null || targetMeridian == null || sourceMeridian.equals(targetMeridian)) {
                    throw MeridianErrors.invalid(Data.PASSIVE_ROUTE_INVALID);
                }
            }
        }

        // 局部参数有效仍可能在求和时溢出，提前保证派生总量和比例可用。
        DataChecks.positive(parts.stream().mapToDouble(Anatomy.PartDefinition::maxHp).sum(), "总体部位 HP");
        DataChecks.nonNegative(meridians.stream().mapToDouble(m -> m.lumenVolume() + m.wallVolume()).sum(), "经脉总体积");
    }

    public double lumenVolume() {
        // 总体积只汇总内腔，外壁是独立账户。
        return meridians.stream().mapToDouble(Anatomy.MeridianDefinition::lumenVolume).sum();
    }

    private static void requirePart(Map<Anatomy.PartId, Anatomy.PartDefinition> parts, Anatomy.PartId id) {
        // 在完整构型中校验引用，单独的模块定义无法知道部位是否存在。
        if (!parts.containsKey(id)) {
            throw MeridianErrors.invalid(Data.missingPart(id));
        }
    }

    private static void addPorts(Map<Anatomy.PortId, Anatomy.Port> index, List<Anatomy.Port> ports) {
        // 不允许不同模块共用同一个端口 ID，否则连接终点会有歧义。
        for (var port : ports) {
            if (index.putIfAbsent(port.id(), port) != null) {
                throw MeridianErrors.invalid(Data.duplicatePort(port.id()));
            }
        }
    }
}
