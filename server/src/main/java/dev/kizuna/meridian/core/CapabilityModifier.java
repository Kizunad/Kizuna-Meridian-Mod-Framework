package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

import java.util.List;
import java.util.Optional;

/** 身体保存的临时组织能力曲线；常值曲线与变值曲线共用相同规则。 */
public record CapabilityModifier(DefinitionId id, DefinitionId source, Anatomy.TissueId tissueId,
                                 Curve curve, Peaks peaks) {
    public static final int MAX_PER_BODY = 32;
    public static final int MIN_CURVE_POINTS = 2;
    public static final int MAX_CURVE_POINTS = 16;
    public static final String MAX_CURVE_DURATION_PROPERTY = "meridian.capability.max_curve_duration_millis";
    public static final String MAX_PEAK_PROPERTY = "meridian.capability.max_peak";
    public static final String MIN_MULTIPLIER_PROPERTY = "meridian.capability.min_multiplier";
    public static final String MIN_TOTAL_MULTIPLIER_PROPERTY = "meridian.capability.min_total_multiplier";
    public static final String MAX_TOTAL_MULTIPLIER_PROPERTY = "meridian.capability.max_total_multiplier";
    private static final long DEFAULT_MAX_CURVE_DURATION_MILLIS = 300_000;
    private static final double DEFAULT_MAXIMUM = 10;
    private static final double DEFAULT_MINIMUM = 0.05;
    public static final long MAX_CURVE_DURATION_MILLIS = configuredDuration();
    public static final double MAX_PEAK = configuredMaximum(MAX_PEAK_PROPERTY);
    public static final double MIN_MULTIPLIER = configuredMinimum(MIN_MULTIPLIER_PROPERTY);
    public static final double MIN_TOTAL_MULTIPLIER = configuredMinimum(MIN_TOTAL_MULTIPLIER_PROPERTY);
    public static final double MAX_TOTAL_MULTIPLIER = configuredMaximum(MAX_TOTAL_MULTIPLIER_PROPERTY);

    public CapabilityModifier {
        // 身份和曲线先独立校验，各线性段的最低倍率只需在端点检查。
        MeridianErrors.required(id, Data.INVALID_CAPABILITY_MODIFIER);
        MeridianErrors.required(source, Data.INVALID_CAPABILITY_MODIFIER);
        MeridianErrors.required(tissueId, Data.INVALID_CAPABILITY_MODIFIER);
        MeridianErrors.required(curve, Data.INVALID_CAPABILITY_MODIFIER);
        MeridianErrors.required(peaks, Data.INVALID_CAPABILITY_MODIFIER);
        for (CurvePoint point : curve.points()) {
            for (Attribute attribute : Attribute.values()) {
                if (multiplier(peaks.get(attribute), point.value()) < MIN_MULTIPLIER) {
                    throw MeridianErrors.invalid(Data.INVALID_CAPABILITY_MODIFIER);
                }
            }
        }
    }

    public enum Attribute { STRENGTH, BURST, ENDURANCE, RECOVERY }

    enum Side { LEFT, RIGHT }

    public record CurvePoint(long offsetMillis, double value) {
        public CurvePoint {
            // 曲线允许负值表达虚脱期，但时间和振幅都必须有界。
            if (offsetMillis < 0 || !Double.isFinite(value) || value < -1 || value > 1) {
                throw MeridianErrors.invalid(Data.INVALID_CAPABILITY_MODIFIER);
            }
        }
    }

    public record Curve(long startMillis, List<CurvePoint> points) {
        public Curve {
            // 复制后固定曲线事实；首尾值可非零，生效和到期处允许阶跃。
            points = List.copyOf(points);
            if (startMillis < 0 || points.size() < MIN_CURVE_POINTS || points.size() > MAX_CURVE_POINTS
                    || points.get(0).offsetMillis() != 0) {
                throw MeridianErrors.invalid(Data.INVALID_CAPABILITY_MODIFIER);
            }
            long previousOffsetMillis = -1;
            for (CurvePoint point : points) {
                if (point.offsetMillis() <= previousOffsetMillis) {
                    throw MeridianErrors.invalid(Data.INVALID_CAPABILITY_MODIFIER);
                }
                previousOffsetMillis = point.offsetMillis();
            }
            if (previousOffsetMillis > MAX_CURVE_DURATION_MILLIS
                    || previousOffsetMillis > Long.MAX_VALUE - startMillis) {
                throw MeridianErrors.invalid(Data.INVALID_CAPABILITY_MODIFIER);
            }
        }

        public long endMillis() {
            // 构造时已检查加法溢出，末点表示失效时刻。
            return startMillis + points.get(points.size() - 1).offsetMillis();
        }
    }

    public record Peaks(double strength, double burst, double endurance, double recovery) {
        public Peaks {
            // 四项共享同一峰值边界，缺省零也不能隐式解释成不变。
            for (double peak : new double[] {strength, burst, endurance, recovery}) {
                if (!Double.isFinite(peak) || peak <= 0 || peak > MAX_PEAK) {
                    throw MeridianErrors.invalid(Data.INVALID_CAPABILITY_MODIFIER);
                }
            }
        }

        double get(Attribute attribute) {
            // 同一评估器按维度取峰值，恢复与做功不另建曲线规则。
            return switch (attribute) {
                case STRENGTH -> strength;
                case BURST -> burst;
                case ENDURANCE -> endurance;
                case RECOVERY -> recovery;
            };
        }
    }

    double at(Attribute attribute, long millis, double fractionalMillis, Side side) {
        // 各能力维度复用相同曲线值，保持同一插值与阶跃语义。
        double value = valueAt(millis, fractionalMillis, side);
        return multiplier(peaks.get(attribute), value);
    }

    double valueAt(long millis, double fractionalMillis, Side side) {
        // 使用整数相减后再插值，接近 long 时钟上限时仍保留短曲线的毫秒精度。
        long startMillis = curve.startMillis();
        long endMillis = curve.endMillis();
        boolean beforeStart = millis < startMillis;
        boolean afterEnd = millis > endMillis || (millis == endMillis && fractionalMillis > 0);
        if (beforeStart || afterEnd) {
            return 0;
        }
        if (fractionalMillis == 0 && ((millis == startMillis && side == Side.LEFT)
                || (millis == endMillis && side == Side.RIGHT))) {
            return 0;
        }
        long offsetMillis = millis - startMillis;
        List<CurvePoint> points = curve.points();
        for (int index = 1; index < points.size(); index++) {
            CurvePoint right = points.get(index);
            if (offsetMillis < right.offsetMillis()
                    || (offsetMillis == right.offsetMillis() && fractionalMillis == 0)) {
                CurvePoint left = points.get(index - 1);
                double elapsedMillis = offsetMillis - left.offsetMillis() + fractionalMillis;
                double fraction = elapsedMillis / (right.offsetMillis() - left.offsetMillis());
                return left.value() + (right.value() - left.value()) * fraction;
            }
        }
        return 0;
    }

    public static Optional<TimeCursor> nextBoundary(BodySnapshot body) {
        // 生效、拐点和到期都是连续负载可能改变的事件，冻结也不延长期限。
        long nowMillis = body.settledAt().elapsedMillis();
        long earliestMillis = Long.MAX_VALUE;
        boolean found = false;
        for (CapabilityModifier modifier : body.modifiers()) {
            for (CurvePoint point : modifier.curve().points()) {
                long boundaryMillis = modifier.curve().startMillis() + point.offsetMillis();
                if (boundaryMillis > nowMillis && (!found || boundaryMillis < earliestMillis)) {
                    earliestMillis = boundaryMillis;
                    found = true;
                }
            }
        }
        if (!found) {
            return Optional.empty();
        }
        TimeCursor time = body.settledAt();
        return Optional.of(new TimeCursor(time.domain(), time.epoch(), earliestMillis));
    }

    static double multiplier(double peak, double value) {
        // 固定倍率就是值为 1 的常值曲线，所有曲线共用此公式。
        return 1 + (peak - 1) * value;
    }

    private static long configuredDuration() {
        // 配置只在类初始化时读取，候选计算与回执复算使用同一规则。
        String configured = System.getProperty(MAX_CURVE_DURATION_PROPERTY,
                Long.toString(DEFAULT_MAX_CURVE_DURATION_MILLIS));
        try {
            long durationMillis = Long.parseLong(configured);
            if (durationMillis <= 0) {
                throw MeridianErrors.invalid(Data.CAPABILITY_CONFIGURATION);
            }
            return durationMillis;
        } catch (NumberFormatException exception) {
            throw MeridianErrors.invalid(Data.CAPABILITY_CONFIGURATION, exception);
        }
    }

    private static double configuredMaximum(String property) {
        // 上限至少保留无修正时的倍率 1，拒绝非有限配置。
        double maximum = configuredNumber(property, DEFAULT_MAXIMUM);
        if (maximum < 1) {
            throw MeridianErrors.invalid(Data.CAPABILITY_CONFIGURATION);
        }
        return maximum;
    }

    private static double configuredMinimum(String property) {
        // 正下限避免零能力倍率和恢复停滞，总范围必须包含倍率 1。
        double minimum = configuredNumber(property, DEFAULT_MINIMUM);
        if (minimum <= 0 || minimum > 1) {
            throw MeridianErrors.invalid(Data.CAPABILITY_CONFIGURATION);
        }
        return minimum;
    }

    private static double configuredNumber(String property, double defaultValue) {
        // 解析错误与越界错误统一为身体规则配置错误。
        String configured = System.getProperty(property, Double.toString(defaultValue));
        try {
            double number = Double.parseDouble(configured);
            if (!Double.isFinite(number)) {
                throw MeridianErrors.invalid(Data.CAPABILITY_CONFIGURATION);
            }
            return number;
        } catch (NumberFormatException exception) {
            throw MeridianErrors.invalid(Data.CAPABILITY_CONFIGURATION, exception);
        }
    }
}
