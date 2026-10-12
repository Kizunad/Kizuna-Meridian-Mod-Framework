package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 出生构型定义；修改角色的实际构型不修改模板，也不重新套用模板覆盖存档。 */
public record BodyTemplate(Reference reference, BodyStructure structure) {
    public BodyTemplate {
        // 模板只有内容来源与结构，不包含某个角色的当前余额或伤势。
        MeridianErrors.required(reference, Data.TEMPLATE_REFERENCE_REQUIRED);
        MeridianErrors.required(structure, Data.TEMPLATE_STRUCTURE_REQUIRED);
    }

    public record Reference(DefinitionId id, int version) {
        public Reference {
            // 内容版本从一开始，与身体快照的 schema 版本分别维护。
            MeridianErrors.required(id, Data.TEMPLATE_ID_REQUIRED);
            if (version < 1) {
                throw MeridianErrors.invalid(Data.INVALID_TEMPLATE_VERSION);
            }
        }
    }
}
