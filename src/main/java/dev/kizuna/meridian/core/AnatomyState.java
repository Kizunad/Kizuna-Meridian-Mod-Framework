package dev.kizuna.meridian.core;

import java.util.Objects;

/** 模块局部状态；身体快照负责检查 ID、HP 与疲劳上限。 */
public final class AnatomyState {
    private AnatomyState() {
        // 局部状态通过各自 record 创建，无需实例化这个类型容器。
    }

    /** 全身生命比例只汇总部位 HP，组织与器官 HP 不再叠加到全身。 */
    public record Part(Anatomy.PartId id, double hp) {
        public Part {
            // 局部值只检查非负；对应部位是否存在及 HP 上限由完整快照验证。
            Objects.requireNonNull(id, "部位标识不能为空");
            DataChecks.nonNegative(hp, "部位 HP");
        }
    }

    public record Tissue(Anatomy.TissueId id, double hp, double fatigue) {
        public Tissue {
            // HP 与疲劳分别保存，不从其中一个推测另一个。
            Objects.requireNonNull(id, "组织标识不能为空");
            DataChecks.nonNegative(hp, "组织 HP");
            DataChecks.nonNegative(fatigue, "组织疲劳");
        }
    }

    public record Organ(Anatomy.OrganId id, double hp) {
        public Organ {
            // 保留实际损伤值，器官失能判定留给后续身体规则。
            Objects.requireNonNull(id, "器官标识不能为空");
            DataChecks.nonNegative(hp, "器官 HP");
        }
    }
}
