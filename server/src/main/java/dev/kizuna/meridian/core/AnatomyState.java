package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 模块局部状态；身体快照负责检查 ID、HP 与疲劳上限。 */
public final class AnatomyState {
    private AnatomyState() {
        // 局部状态通过各自 record 创建，无需实例化这个类型容器。
    }

    /** 全身生命比例只汇总部位 HP，组织与器官 HP 不再叠加到全身。 */
    public record Part(Anatomy.PartId id, double hp) {
        public Part {
            // 局部值只检查非负；对应部位是否存在及 HP 上限由完整快照验证。
            MeridianErrors.required(id, Data.PART_ID_REQUIRED);
            DataChecks.nonNegative(hp, "部位 HP");
        }
    }

    public record Tissue(Anatomy.TissueId id, double hp, double fatigue) {
        public Tissue {
            // HP 与疲劳分别保存，不从其中一个推测另一个。
            MeridianErrors.required(id, Data.TISSUE_ID_REQUIRED);
            DataChecks.nonNegative(hp, "组织 HP");
            DataChecks.nonNegative(fatigue, "组织疲劳");
        }
    }

    public record Organ(Anatomy.OrganId id, double hp, QiInventory buffer, OrganProcess process) {
        public Organ {
            // 缓存与加工输入是不同账户；空闲时 process 为 null，不能重复存放加工中的输入。
            MeridianErrors.required(id, Data.ORGAN_ID_REQUIRED);
            DataChecks.nonNegative(hp, "器官 HP");
            MeridianErrors.required(buffer, Data.QI_REQUIRED);
        }

        public double occupiedQi() {
            // 尚未转换前只计输入；预计输出不是第二份已存在的真元。
            double bufferedQi = buffer.totalQi();
            double processingInputQi = 0;
            if (process != null) {
                processingInputQi = process.recipe().input().totalQi();
            }
            return bufferedQi + processingInputQi;
        }
    }
}
