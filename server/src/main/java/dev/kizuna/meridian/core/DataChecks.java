package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 数据值校验，不包含伤害、成长或宿主权限规则。 */
final class DataChecks {
    static final int MAX_MODULE_TAGS = 16;
    private static final java.util.regex.Pattern LOCAL_ID = java.util.regex.Pattern.compile("[a-z0-9_.-]+");

    private DataChecks() {
        // 校验仅使用静态方法，不需要工具类实例。
    }

    static void text(String value, String label) {
        // 空白文本也不能作为有效名称或身份。
        if (value == null || value.isBlank()) {
            throw MeridianErrors.invalid(Data.empty(label));
        }
    }

    static void localId(String value) {
        // 高频身体校验复用格式规则；局部ID不接受跨内容包的命名空间。
        if (value == null || !LOCAL_ID.matcher(value).matches()) {
            throw MeridianErrors.invalid(Data.LOCAL_ID_FORMAT);
        }
    }

    static void nonNegative(double value, String label) {
        // NaN 不会被普通大小比较拒绝，因此先检查有限性。
        if (!Double.isFinite(value) || value < 0) {
            throw MeridianErrors.invalid(Data.nonNegativeFinite(label));
        }
    }

    static void positive(double value, String label) {
        // 容量参照等字段不能为零，避免后续比值与承载判断失去意义。
        if (!Double.isFinite(value) || value <= 0) {
            throw MeridianErrors.invalid(Data.positiveFinite(label));
        }
    }

    static void revision(long value) {
        // 零表示初始状态，负数不能作为有效版本。
        if (value < 0) {
            throw MeridianErrors.invalid(Data.NEGATIVE_REVISION);
        }
    }

    static void validateTags(List<DefinitionId> tags) {
        // 标签仅为内容选择提供身份，数量和唯一性在任何构型入口都执行同一校验。
        if (tags.size() > MAX_MODULE_TAGS) {
            throw MeridianErrors.invalid(Data.MODULE_TAG_LIMIT);
        }
        index(tags, DefinitionId::value, "模块标签");
    }

    static void atMost(double value, double maximum, String label) {
        nonNegative(value, label);
        // 0.1 + 0.2 等运算可比 0.3 高一个 ULP；仅容忍机器舍入，不给零账户额度。
        if (value > maximum && !(maximum > 0 && value - maximum <= 4 * Math.ulp(maximum))) {
            throw MeridianErrors.invalid(Data.overLimit(label));
        }
    }

    static <K, V> Map<K, V> index(List<V> values, Function<V, K> key, String label) {
        // 建索引时拒绝覆盖旧值，避免重复模块被静默丢失。
        Map<K, V> result = new LinkedHashMap<>();
        for (V value : values) {
            K id = key.apply(value);
            if (id == null) {
                // 缺失身份仍按原异常拒绝，合法索引不反复分配错误文案。
                MeridianErrors.required(id, Data.missingIdentifier(label));
            }
            if (result.putIfAbsent(id, value) != null) {
                throw MeridianErrors.invalid(Data.duplicateIdentifier(label, id));
            }
        }
        return result;
    }
}
