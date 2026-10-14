package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

import java.util.List;

/** 不可变的模块结构值；可用于出生模板，也可描述改造后的实际构型。 */
public final class Anatomy {
    private Anatomy() {
        // 仅收纳结构类型，外部无需创建容器对象。
    }

    public record PartId(String value) {
        public PartId {
            // 局部标识不携带物种限制，唯一性由所属身体校验。
            DataChecks.localId(value);
        }
    }

    public record TissueId(String value) {
        public TissueId {
            // 每块组织有独立标识，组织类型另由内容 ID 表达。
            DataChecks.localId(value);
        }
    }

    public record OrganId(String value) {
        public OrganId {
            // 这里只检查格式，器官是否存在由构型检查。
            DataChecks.localId(value);
        }
    }

    public record MeridianId(String value) {
        public MeridianId {
            // 经脉数量与命名由构型决定，不要求人体左右命名。
            DataChecks.localId(value);
        }
    }

    public record PortId(String value) {
        public PortId {
            // 同一身体内的端口统一寻址，构型还会检查跨模块重名。
            DataChecks.localId(value);
        }
    }

    public record ConnectionId(String value) {
        public ConnectionId {
            // 在途记录通过连接标识定位运输线路。
            DataChecks.localId(value);
        }
    }

    public record PartDefinition(PartId id, String displayName, double maxHp) {
        public PartDefinition {
            // 定义必须提供可引用身份和正数 HP 上限，当前 HP 留给实例状态。
            MeridianErrors.required(id, Data.PART_ID_REQUIRED);
            DataChecks.text(displayName, "部位名称");
            DataChecks.positive(maxHp, "部位生命值");
        }
    }

    /** kind 使用内容标识区分肌肉、皮肤、骨骼等，不封闭第三方组织类型。 */
    public record TissueDefinition(TissueId id, String displayName, DefinitionId kind, PartId partId,
                                   double maxHp, double fatigueCapacity, List<Port> ports, TissueCapacity capacity,
                                   double recoveryHalfLifeSeconds) {
        public TissueDefinition(TissueId id, String displayName, DefinitionId kind, PartId partId,
                                double maxHp, double fatigueCapacity, List<Port> ports, TissueCapacity capacity) {
            // 内容没有声明恢复能力时保持禁用，避免给所有物种暗中配置相同恢复数值。
            this(id, displayName, kind, partId, maxHp, fatigueCapacity, ports, capacity, 0);
        }

        public TissueDefinition {
            // 先检查类型与归属，再检查组织自身的承载上限。
            MeridianErrors.required(id, Data.TISSUE_ID_REQUIRED);
            DataChecks.text(displayName, "组织名称");
            MeridianErrors.required(kind, Data.TISSUE_KIND_REQUIRED);
            MeridianErrors.required(partId, Data.TISSUE_PART_REQUIRED);
            DataChecks.positive(maxHp, "组织生命值");
            DataChecks.positive(fatigueCapacity, "组织疲劳容量");
            MeridianErrors.required(capacity, Data.CAPACITY_REQUIRED);
            DataChecks.nonNegative(recoveryHalfLifeSeconds, "组织疲劳恢复半衰期");
            // 防止调用者随后修改端口列表，绕过构型校验。
            ports = List.copyOf(ports);
        }
    }

    /** 力量以 N、爆发功率以 W、耐力做功预算以 J 表达；不从经脉容量推导。 */
    public record TissueCapacity(double strengthNewtons, double burstWatts, double enduranceJoules) {
        public TissueCapacity {
            // 皮肤等被动组织允许零值，主动做功时再检查是否具有所需能力。
            DataChecks.nonNegative(strengthNewtons, "组织力量");
            DataChecks.nonNegative(burstWatts, "组织爆发功率");
            DataChecks.nonNegative(enduranceJoules, "组织耐力");
        }
    }

    /** 器官声明自身接口与可选摄入能力；转换产物由配方定义。 */
    public record OrganDefinition(OrganId id, String displayName, DefinitionId kind, PartId partId,
                                  double maxHp, List<Port> ports, double qiCapacity, double throughputPerSecond,
                                  OrganIntakeCapability intake) {
        public OrganDefinition(OrganId id, String displayName, DefinitionId kind, PartId partId,
                               double maxHp, List<Port> ports, double qiCapacity, double throughputPerSecond) {
            // 未声明能力的器官只能使用已有缓存，不隐式获得从环境补元的能力。
            this(id, displayName, kind, partId, maxHp, ports, qiCapacity, throughputPerSecond, null);
        }

        public OrganDefinition {
            // 这里只声明器官本体与接口，不把具体加工配方写死在结构里。
            MeridianErrors.required(id, Data.ORGAN_ID_REQUIRED);
            DataChecks.text(displayName, "器官名称");
            MeridianErrors.required(kind, Data.ORGAN_KIND_REQUIRED);
            MeridianErrors.required(partId, Data.ORGAN_PART_REQUIRED);
            DataChecks.positive(maxHp, "器官生命值");
            DataChecks.positive(qiCapacity, "器官真元容量");
            DataChecks.positive(throughputPerSecond, "器官端口吞吐");
            ports = List.copyOf(ports);
        }
    }

    public record OrganIntakeCapability(double maxQiPerSecond) {
        public OrganIntakeCapability {
            // 摄入与端口搬运是不同能力，附属包必须明确给出可运行的摄入速率。
            DataChecks.positive(maxQiPerSecond, "器官摄入速率");
        }
    }

    public record Port(PortId id, PortDirection direction) {
        public Port {
            // 方向必须显式声明，连接检查才能判断允许的流向。
            MeridianErrors.required(id, Data.PORT_ID_REQUIRED);
            MeridianErrors.required(direction, Data.PORT_DIRECTION_REQUIRED);
        }
    }

    /** 容量以元计，液态参照空间与有效容量分列；容量增长本身不生成真元。 */
    public record MeridianDefinition(MeridianId id, String displayName, List<PartId> partIds,
                                     double lumenCapacity, double wallCapacity,
                                     double lumenLiquidReference, double wallLiquidReference,
                                     double throughputPerSecond, double resilience,
                                     double fatigueCapacity, List<Port> ports) {
        public MeridianDefinition {
            // 经脉覆盖部位不得重复或为空，部位实际存在性由构型校验。
            MeridianErrors.required(id, Data.MERIDIAN_ID_REQUIRED);
            DataChecks.text(displayName, "经脉名称");
            partIds = List.copyOf(partIds);
            DataChecks.index(partIds, part -> part, "经脉覆盖部位");
            if (partIds.isEmpty()) {
                throw MeridianErrors.invalid(Data.MERIDIAN_WITHOUT_PART);
            }
            // 有效容量和液态参照分开；新增外壁空间也必须有参照，避免虚增容量比。
            DataChecks.positive(lumenCapacity, "经脉内腔容量");
            DataChecks.nonNegative(wallCapacity, "经脉外壁容量");
            DataChecks.positive(lumenLiquidReference, "内腔液态参照");
            DataChecks.nonNegative(wallLiquidReference, "外壁液态参照");
            if (wallCapacity > 0 && wallLiquidReference == 0) {
                throw MeridianErrors.invalid(Data.WALL_WITHOUT_REFERENCE);
            }
            // 这里只接受有效结构参数，输送、超载和成长公式由运行层负责。
            DataChecks.positive(throughputPerSecond, "经脉吞吐量");
            DataChecks.positive(resilience, "经脉承受度");
            DataChecks.positive(fatigueCapacity, "经脉疲劳容量");
            ports = List.copyOf(ports);
        }
    }

    /** 有向连接；双向通路显式声明两条连接，阻力为非负相对系数，距离以米计。 */
    public record Connection(ConnectionId id, PortId from, PortId to, double resistance, double distance) {
        public Connection {
            // 同一端口自连没有运输意义，多端口形成的循环仍然允许。
            MeridianErrors.required(id, Data.CONNECTION_ID_REQUIRED);
            MeridianErrors.required(from, Data.CONNECTION_FROM_REQUIRED);
            MeridianErrors.required(to, Data.CONNECTION_TO_REQUIRED);
            if (from.equals(to)) {
                throw MeridianErrors.invalid(Data.SELF_CONNECTION);
            }
            DataChecks.nonNegative(resistance, "连接阻力");
            DataChecks.positive(distance, "连接距离");
        }
    }

    public enum PortDirection {
        INPUT,
        OUTPUT,
        BIDIRECTIONAL
    }
}
