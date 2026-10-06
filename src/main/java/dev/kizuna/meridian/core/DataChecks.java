package dev.kizuna.meridian.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 数据值校验，不包含伤害、成长或宿主权限规则。 */
final class DataChecks {
    private DataChecks() {
        // 校验仅使用静态方法，不需要工具类实例。
    }

    static void text(String value, String label) {
        // 空白文本也不能作为有效名称或身份。
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }

    static void localId(String value) {
        // 局部 ID 属于单具身体，跨内容包的命名空间由 DefinitionId 负责。
        if (value == null || !value.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("局部标识必须使用非空小写字母、数字、点、横线或下划线");
        }
    }

    static void nonNegative(double value, String label) {
        // NaN 不会被普通大小比较拒绝，因此先检查有限性。
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(label + "必须为非负有限数");
        }
    }

    static void positive(double value, String label) {
        // 容量参照等字段不能为零，避免后续比值与承载判断失去意义。
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(label + "必须为正有限数");
        }
    }

    static void revision(long value) {
        // 零表示初始状态，负数不能作为有效版本。
        if (value < 0) {
            throw new IllegalArgumentException("版本号不能为负数");
        }
    }

    static void atMost(double value, double maximum, String label) {
        nonNegative(value, label);
        // 0.1 + 0.2 等运算可比 0.3 高一个 ULP；仅容忍机器舍入，不给零账户额度。
        if (value > maximum && !(maximum > 0 && value - maximum <= 4 * Math.ulp(maximum))) {
            throw new IllegalArgumentException(label + "超过上限");
        }
    }

    static <K, V> Map<K, V> index(List<V> values, Function<V, K> key, String label) {
        // 建索引时拒绝覆盖旧值，避免重复模块被静默丢失。
        Map<K, V> result = new LinkedHashMap<>();
        for (V value : values) {
            K id = Objects.requireNonNull(key.apply(value), label + "标识不能为空");
            if (result.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException(label + "标识重复：" + id);
            }
        }
        return result;
    }
}
