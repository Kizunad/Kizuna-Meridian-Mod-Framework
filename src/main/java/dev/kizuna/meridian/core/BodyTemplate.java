package dev.kizuna.meridian.core;

import java.util.Objects;

/** 出生构型定义；修改角色的实际构型不修改模板，也不重新套用模板覆盖存档。 */
public record BodyTemplate(Reference reference, BodyStructure structure) {
    public BodyTemplate {
        // 模板只有内容来源与结构，不包含某个角色的当前余额或伤势。
        Objects.requireNonNull(reference, "模板引用不能为空");
        Objects.requireNonNull(structure, "模板构型不能为空");
    }

    public record Reference(DefinitionId id, int version) {
        public Reference {
            // 内容版本从一开始，与身体快照的 schema 版本分别维护。
            Objects.requireNonNull(id, "模板标识不能为空");
            if (version < 1) {
                throw new IllegalArgumentException("模板版本必须为正整数");
            }
        }
    }
}
