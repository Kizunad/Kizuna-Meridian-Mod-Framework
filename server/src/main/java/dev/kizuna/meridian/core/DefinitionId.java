package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 跨附属包的内容标识；局部模块标识另外限定在所属身体内。 */
public record DefinitionId(String value) {
    public DefinitionId {
        // 用命名空间区分附属包内容，路径部分允许按目录组织。
        if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw MeridianErrors.invalid(Data.CONTENT_ID_FORMAT);
        }
    }
}
