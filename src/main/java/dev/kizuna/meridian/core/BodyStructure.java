package dev.kizuna.meridian.core;

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
            throw new IllegalArgumentException("身体必须包含至少一个部位");
        }

        // 检查模块的部位归属，并建立整具身体唯一的端口索引。
        Map<Anatomy.PortId, Anatomy.Port> ports = new HashMap<>();
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
        }

        // 循环是合法经脉拓扑；此处只拒绝悬空、方向错误和重复边。
        Set<List<Anatomy.PortId>> edges = new HashSet<>();
        for (var connection : connections) {
            var from = ports.get(connection.from());
            var to = ports.get(connection.to());
            if (from == null || to == null) {
                throw new IllegalArgumentException("连接引用不存在的端口：" + connection.id());
            }
            if (from.direction() == Anatomy.PortDirection.INPUT
                    || to.direction() == Anatomy.PortDirection.OUTPUT) {
                throw new IllegalArgumentException("连接方向与端口不符：" + connection.id());
            }
            if (!edges.add(List.of(connection.from(), connection.to()))) {
                throw new IllegalArgumentException("连接重复：" + connection.id());
            }
        }

        // 局部参数有效仍可能在求和时溢出，提前保证派生总量和比例可用。
        DataChecks.positive(parts.stream().mapToDouble(Anatomy.PartDefinition::maxHp).sum(), "总体部位 HP");
        double capacity = meridians.stream().mapToDouble(m -> m.lumenCapacity() + m.wallCapacity()).sum();
        double reference = meridians.stream().mapToDouble(m -> m.lumenLiquidReference() + m.wallLiquidReference()).sum();
        DataChecks.nonNegative(capacity, "总体真元容量");
        DataChecks.nonNegative(reference, "总体液态参照");
        if (reference > 0) {
            DataChecks.positive(capacity / reference, "容量比");
        }
    }

    /** 只用于容量展示；不依据当前填充量自动判定突破成功。 */
    public double capacityRatio() {
        // 分子分母覆盖相同的内腔与外壁空间；无经脉时显示零而非除零。
        double reference = meridians.stream().mapToDouble(m -> m.lumenLiquidReference() + m.wallLiquidReference()).sum();
        if (reference == 0) {
            return 0;
        }
        return meridians.stream().mapToDouble(m -> m.lumenCapacity() + m.wallCapacity()).sum() / reference;
    }

    private static void requirePart(Map<Anatomy.PartId, Anatomy.PartDefinition> parts, Anatomy.PartId id) {
        // 在完整构型中校验引用，单独的模块定义无法知道部位是否存在。
        if (!parts.containsKey(id)) {
            throw new IllegalArgumentException("引用不存在的部位：" + id);
        }
    }

    private static void addPorts(Map<Anatomy.PortId, Anatomy.Port> index, List<Anatomy.Port> ports) {
        // 不允许不同模块共用同一个端口 ID，否则连接终点会有歧义。
        for (var port : ports) {
            if (index.putIfAbsent(port.id(), port) != null) {
                throw new IllegalArgumentException("端口在身体内必须唯一：" + port.id());
            }
        }
    }
}
