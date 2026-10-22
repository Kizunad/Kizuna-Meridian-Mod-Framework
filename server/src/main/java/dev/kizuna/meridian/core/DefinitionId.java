package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 跨附属包的内容标识；局部模块标识另外限定在所属身体内。 */
public record DefinitionId(String value) {
    private static final java.util.regex.Pattern FORMAT = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public DefinitionId {
        // 身体协议反复校验同一种格式，复用已编译规则，避免每个模块重新编译正则。
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw MeridianErrors.invalid(Data.CONTENT_ID_FORMAT);
        }
    }
}
