package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static dev.kizuna.meridian.core.CapabilityModifier.Attribute;
import static dev.kizuna.meridian.core.CapabilityModifier.Side;

/** 能力查询、区间做功与恢复积分共用的曲线乘积评估。 */
final class CapabilityCurves {
    private static final double MILLIS_PER_SECOND = 1_000;
    private static final int MILLIS_DECIMAL_PLACES = 3;
    private static final double HALF = 0.5;
    private static final int ROOT_ITERATIONS = 64;
    private static final long[] NO_BOUNDARIES = new long[0];
    private static final double LOG_MINIMUM = StrictMath.log(CapabilityModifier.MIN_TOTAL_MULTIPLIER);
    private static final double LOG_MAXIMUM = StrictMath.log(CapabilityModifier.MAX_TOTAL_MULTIPLIER);
    // 17 点 Gauss–Legendre：中心及正半轴的节点、权重，最高精确到 33 次多项式。
    private static final double CENTER_WEIGHT = 0.17944647035620653;
    private static final double[] NODES = {
            0.17848418149584785, 0.3512317634538763, 0.5126905370864769, 0.6576711592166908,
            0.7815140038968014, 0.8802391537269859, 0.9506755217687678, 0.9905754753144174
    };
    private static final double[] WEIGHTS = {
            0.17656270536699265, 0.16800410215645004, 0.1540457610768103, 0.13513636846852547,
            0.11188384719340397, 0.08503614831717918, 0.0554595293739872, 0.024148302868547932
    };
    private static final Context UNMODIFIED = new Context(List.of());

    private CapabilityCurves() {
        // 纯计算不修改曲线，也不读取墙钟或保存派生能力。
    }

    static Context context(List<CapabilityModifier> modifiers) {
        // 没有修正时上下文没有可变缓存，可以共享空索引而不增加日常推进分配。
        if (modifiers.isEmpty()) {
            return UNMODIFIED;
        }
        return new Context(modifiers);
    }

    private record Moment(long millis, double fractionalMillis) { }

    record Multipliers(double strength, double burst, double endurance) {
        static final Multipliers IDENTITY = new Multipliers(1, 1, 1);

        Multipliers minimum(Multipliers other) {
            // 三项能力同时比较同一边界，避免反复插值同一条曲线。
            return new Multipliers(StrictMath.min(strength, other.strength),
                    StrictMath.min(burst, other.burst), StrictMath.min(endurance, other.endurance));
        }
    }

    private record Endpoint(Moment moment, Side side, Multipliers multipliers, double[] recovery) { }

    static final class Context {
        private final Map<Anatomy.TissueId, Group> groups;
        private final long[] boundaries;

        Context(List<CapabilityModifier> modifiers) {
            // 身份排序保证浮点乘法次序固定；组织索引与全身边界在一次查询或推进内复用。
            if (modifiers.isEmpty()) {
                groups = Map.of();
                boundaries = NO_BOUNDARIES;
                return;
            }
            if (modifiers.size() == 1) {
                CapabilityModifier modifier = modifiers.get(0);
                var group = new Group(modifiers);
                groups = Map.of(modifier.tissueId(), group);
                boundaries = group.boundaries;
                return;
            }
            var byTissue = new HashMap<Anatomy.TissueId, List<CapabilityModifier>>();
            List<CapabilityModifier> ordered = modifiers.stream()
                    .sorted(Comparator.comparing(modifier -> modifier.id().value()))
                    .toList();
            for (CapabilityModifier modifier : ordered) {
                byTissue.computeIfAbsent(modifier.tissueId(), ignored -> new ArrayList<>()).add(modifier);
            }
            var indexed = new HashMap<Anatomy.TissueId, Group>();
            byTissue.forEach((id, items) -> indexed.put(id, new Group(items)));
            groups = Map.copyOf(indexed);
            boundaries = curveBoundaries(modifiers);
        }

        long nextBoundary(long nowMillis, long fallbackMillis) {
            // 严格晚于当前时刻的下一个点，保证循环每轮前进或在目标处结束。
            int index = after(boundaries, nowMillis);
            if (index == boundaries.length) {
                return fallbackMillis;
            }
            return StrictMath.min(boundaries[index], fallbackMillis);
        }

        boolean hasModifiers(Anatomy.TissueId tissueId, long nowMillis) {
            // 到期后的候选与最终移除列表具有相同语义。
            Group group = groups.get(tissueId);
            if (group == null) {
                return false;
            }
            for (CapabilityModifier modifier : group.modifiers) {
                if (modifier.curve().endMillis() > nowMillis) {
                    return true;
                }
            }
            return false;
        }

        Multipliers current(Anatomy.TissueId tissueId, long nowMillis) {
            // 查询取右侧值，起止时刻的阶跃与直接赋值一致。
            Group group = groups.get(tissueId);
            if (group == null) {
                return Multipliers.IDENTITY;
            }
            return group.endpoint(new Moment(nowMillis, 0), Side.RIGHT).multipliers();
        }

        Multipliers minimum(Anatomy.TissueId tissueId, long nowMillis, double durationSeconds) {
            // 正仿射因子乘积的对数为凹函数，连续段内只需比较两端及阶跃两侧。
            Group group = groups.get(tissueId);
            if (group != null && group.minimumStartMillis == nowMillis
                    && group.minimumDurationSeconds == durationSeconds) {
                return group.minimum;
            }
            Moment end = endMoment(nowMillis, durationSeconds);
            if (group == null) {
                return Multipliers.IDENTITY;
            }
            var start = new Moment(nowMillis, 0);
            Multipliers minimum = group.endpoint(start, Side.RIGHT).multipliers()
                    .minimum(group.endpoint(end, Side.RIGHT).multipliers());
            for (int index = after(group.boundaries, nowMillis); index < group.boundaries.length; index++) {
                long boundaryMillis = group.boundaries[index];
                if (boundaryMillis >= end.millis()) {
                    break;
                }
                var boundary = new Moment(boundaryMillis, 0);
                minimum = minimum.minimum(group.endpoint(boundary, Side.LEFT).multipliers());
                minimum = minimum.minimum(group.endpoint(boundary, Side.RIGHT).multipliers());
            }
            if (end.millis() > nowMillis) {
                var lastWholeMillis = new Moment(end.millis(), 0);
                minimum = minimum.minimum(group.endpoint(lastWholeMillis, Side.LEFT).multipliers());
                if (end.fractionalMillis() != 0) {
                    minimum = minimum.minimum(group.endpoint(lastWholeMillis, Side.RIGHT).multipliers());
                }
            }
            group.minimumStartMillis = nowMillis;
            group.minimumDurationSeconds = durationSeconds;
            group.minimum = minimum;
            return minimum;
        }

        double recoverySeconds(Anatomy.TissueId tissueId, long startMillis, long elapsedMillis) {
            // 按该组织的边界积分，外层全身切段与此处共用端点评估缓存。
            Group group = groups.get(tissueId);
            if (group == null || !hasModifiers(tissueId, startMillis)) {
                return elapsedMillis / MILLIS_PER_SECOND;
            }
            long endMillis = startMillis + elapsedMillis;
            long previousMillis = startMillis;
            double effectiveMillis = 0;
            for (int index = after(group.boundaries, startMillis); index < group.boundaries.length; index++) {
                long boundaryMillis = group.boundaries[index];
                if (boundaryMillis >= endMillis) {
                    break;
                }
                double segmentMillis = boundaryMillis - previousMillis;
                effectiveMillis += segmentMillis * group.recoveryIntegral(previousMillis, boundaryMillis);
                previousMillis = boundaryMillis;
            }
            effectiveMillis += (endMillis - previousMillis) * group.recoveryIntegral(previousMillis, endMillis);
            return effectiveMillis / MILLIS_PER_SECOND;
        }
    }

    private static final class Group {
        private static final int ENDPOINT_CACHE_SIZE = 4;
        private final List<CapabilityModifier> modifiers;
        private final long[] boundaries;
        private final Endpoint[] endpoints = new Endpoint[ENDPOINT_CACHE_SIZE];
        private int cachePosition;
        private long minimumStartMillis = -1;
        private double minimumDurationSeconds;
        private Multipliers minimum;

        private Group(List<CapabilityModifier> modifiers) {
            // 输入已经按 ID 排序，分组后仍保持原有乘法顺序。
            this.modifiers = modifiers;
            boundaries = curveBoundaries(modifiers);
        }

        private Endpoint endpoint(Moment moment, Side side) {
            // 起点右值、终点左右值在预算和恢复之间共享；缓存有界且只属于一次调用。
            for (Endpoint endpoint : endpoints) {
                if (endpoint != null && endpoint.side() == side && endpoint.moment().equals(moment)) {
                    return endpoint;
                }
            }
            Endpoint evaluated = evaluate(moment, side);
            endpoints[cachePosition] = evaluated;
            cachePosition = (cachePosition + 1) % ENDPOINT_CACHE_SIZE;
            return evaluated;
        }

        private Endpoint evaluate(Moment moment, Side side) {
            // 每条曲线只插值一次，同时取得三项做功能力和恢复端点。
            double strength = 1;
            double burst = 1;
            double endurance = 1;
            var recovery = new double[modifiers.size()];
            for (int index = 0; index < modifiers.size(); index++) {
                CapabilityModifier modifier = modifiers.get(index);
                double value = modifier.valueAt(moment.millis(), moment.fractionalMillis(), side);
                CapabilityModifier.Peaks peaks = modifier.peaks();
                strength *= CapabilityModifier.multiplier(peaks.strength(), value);
                burst *= CapabilityModifier.multiplier(peaks.burst(), value);
                endurance *= CapabilityModifier.multiplier(peaks.endurance(), value);
                recovery[index] = CapabilityModifier.multiplier(peaks.recovery(), value);
                if (!normal(strength) || !normal(burst) || !normal(endurance)) {
                    return extremeEndpoint(moment, side, recovery);
                }
            }
            var multipliers = new Multipliers(clamp(strength), clamp(burst), clamp(endurance));
            return new Endpoint(moment, side, multipliers, recovery);
        }

        private Endpoint extremeEndpoint(Moment moment, Side side, double[] recovery) {
            // 极端配置发生中间溢出时重新计算全部因子，不丢失后续反向倍率。
            for (int index = 0; index < modifiers.size(); index++) {
                recovery[index] = modifiers.get(index).at(Attribute.RECOVERY,
                        moment.millis(), moment.fractionalMillis(), side);
            }
            var multipliers = new Multipliers(product(modifiers, Attribute.STRENGTH, moment, side),
                    product(modifiers, Attribute.BURST, moment, side),
                    product(modifiers, Attribute.ENDURANCE, moment, side));
            return new Endpoint(moment, side, multipliers, recovery);
        }

        private double recoveryIntegral(long startMillis, long endMillis) {
            // 末点左值属于到期前一段；已到期因子从下一段移除，保持原积分舍入顺序。
            Endpoint left = endpoint(new Moment(startMillis, 0), Side.RIGHT);
            Endpoint right = endpoint(new Moment(endMillis, 0), Side.LEFT);
            var factors = new ArrayList<Linear>();
            for (int index = 0; index < modifiers.size(); index++) {
                if (modifiers.get(index).curve().endMillis() > startMillis) {
                    factors.add(new Linear(left.recovery()[index], right.recovery()[index]));
                }
            }
            if (factors.isEmpty()) {
                return 1;
            }
            return integral(factors);
        }
    }

    private static boolean normal(double product) {
        // 非规格化中间结果也要回到全部对数求和，避免舍入后抵消失真。
        return Double.isFinite(product) && product >= Double.MIN_NORMAL;
    }

    private static long[] curveBoundaries(List<CapabilityModifier> modifiers) {
        // 整数边界一次排序去重，接近 long 上限时不转换为 double。
        if (modifiers.size() == 1) {
            CapabilityModifier.Curve curve = modifiers.get(0).curve();
            var times = new long[curve.points().size()];
            for (int index = 0; index < times.length; index++) {
                times[index] = curve.startMillis() + curve.points().get(index).offsetMillis();
            }
            return times;
        }
        var times = new TreeSet<Long>();
        for (CapabilityModifier modifier : modifiers) {
            for (CapabilityModifier.CurvePoint point : modifier.curve().points()) {
                times.add(modifier.curve().startMillis() + point.offsetMillis());
            }
        }
        return times.stream().mapToLong(Long::longValue).toArray();
    }

    private static int after(long[] times, long nowMillis) {
        // 二分查找严格晚于游标的首个边界，工作量不随已经处理的点数增长。
        int index = Arrays.binarySearch(times, nowMillis);
        if (index >= 0) {
            return index + 1;
        }
        return -index - 1;
    }

    private record Linear(double left, double right) {
        double at(double fraction) {
            // 两端均为正倍率；一次舍入的插值避免较小端点被相减吞掉。
            if (fraction == 0) {
                return left;
            }
            if (fraction == 1) {
                return right;
            }
            return StrictMath.fma(fraction, right - left, left);
        }
    }

    private static Moment endMoment(long startMillis, double durationSeconds) {
        // 保留不足一毫秒的时长，且在整数加法前检查整个做功区间能否表示。
        BigDecimal durationMillis = BigDecimal.valueOf(durationSeconds).movePointRight(MILLIS_DECIMAL_PLACES);
        if (durationMillis.compareTo(BigDecimal.valueOf(Long.MAX_VALUE - startMillis)) > 0) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
        }
        long wholeMillis = durationMillis.longValue();
        double fractionalMillis = durationMillis.subtract(BigDecimal.valueOf(wholeMillis)).doubleValue();
        return new Moment(startMillis + wholeMillis, fractionalMillis);
    }

    private static double product(List<CapabilityModifier> modifiers, Attribute attribute, Moment moment, Side side) {
        // 常规倍率直接相乘，完整乘积后才截断；极端配置用对数和保留相互抵消的因子。
        double product = 1;
        for (CapabilityModifier modifier : modifiers) {
            double multiplier = modifier.at(attribute, moment.millis(), moment.fractionalMillis(), side);
            product *= multiplier;
            if (!Double.isFinite(product) || product < Double.MIN_NORMAL) {
                return extremeProduct(modifiers, attribute, moment, side);
            }
        }
        return clamp(product);
    }

    private static double extremeProduct(List<CapabilityModifier> modifiers, Attribute attribute,
                                          Moment moment, Side side) {
        // 中途上溢或下溢仍可能被后续因子抵消，必须重新合并全部对数再截断。
        double logarithm = 0;
        for (CapabilityModifier modifier : modifiers) {
            double multiplier = modifier.at(attribute, moment.millis(), moment.fractionalMillis(), side);
            logarithm += StrictMath.log(multiplier);
        }
        return clampedExponential(logarithm);
    }

    private static double clamp(double product) {
        // 总倍率上下限只施加一次，不能在乘积中途丢掉超出范围的部分。
        return StrictMath.max(CapabilityModifier.MIN_TOTAL_MULTIPLIER,
                StrictMath.min(CapabilityModifier.MAX_TOTAL_MULTIPLIER, product));
    }

    private static double clampedExponential(double logarithm) {
        // 先在对数域截断，避免 exp 自身超出 double 的表示范围。
        if (logarithm <= LOG_MINIMUM) {
            return CapabilityModifier.MIN_TOTAL_MULTIPLIER;
        }
        if (logarithm >= LOG_MAXIMUM) {
            return CapabilityModifier.MAX_TOTAL_MULTIPLIER;
        }
        return clamp(StrictMath.exp(logarithm));
    }

    private static double integral(List<Linear> factors) {
        // 总倍率截断会产生额外折点；先找交点，不能直接对含截断的整段做高斯积分。
        if (factors.size() == 1) {
            Linear factor = factors.get(0);
            if (factor.left() == clamp(factor.left()) && factor.right() == clamp(factor.right())) {
                // 单个未触及截断的线性因子，中点值就是精确的单位区间积分。
                return factor.at(HALF);
            }
        }
        double peak = peakFraction(factors);
        var cuts = new TreeSet<Double>();
        cuts.add(0.0);
        cuts.add(peak);
        cuts.add(1.0);
        addCrossings(cuts, factors, 0, peak);
        addCrossings(cuts, factors, peak, 1);
        double previous = 0;
        double sum = 0;
        for (double cut : cuts) {
            if (cut > previous) {
                sum += quadrature(factors, previous, cut);
                previous = cut;
            }
        }
        return sum;
    }

    private static double peakFraction(List<Linear> factors) {
        // 正仿射因子乘积的对数导数单调不增，最多一个内峰；固定次数定位。
        if (logDerivative(factors, 0) <= 0) {
            return 0;
        }
        if (logDerivative(factors, 1) >= 0) {
            return 1;
        }
        double low = 0;
        double high = 1;
        for (int iteration = 0; iteration < ROOT_ITERATIONS; iteration++) {
            double middle = (low + high) * HALF;
            if (logDerivative(factors, middle) > 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (low + high) * HALF;
    }

    private static double logDerivative(List<Linear> factors, double fraction) {
        // 对数导数以比值求和，避免先构造可能超出 double 范围的完整乘积。
        double derivative = 0;
        for (Linear factor : factors) {
            derivative += (factor.right() - factor.left()) / factor.at(fraction);
        }
        return derivative;
    }

    private static double logProduct(List<Linear> factors, double fraction) {
        // 常规范围只取一次对数；求根固定次数不变，极端中间结果仍合并全部因子的对数。
        double product = 1;
        for (Linear factor : factors) {
            product *= factor.at(fraction);
            if (!normal(product)) {
                return extremeLogProduct(factors, fraction);
            }
        }
        return StrictMath.log(product);
    }

    private static double extremeLogProduct(List<Linear> factors, double fraction) {
        // 中间溢出或下溢可能被后续因子抵消，必须从头累计全部对数。
        double logarithm = 0;
        for (Linear factor : factors) {
            logarithm += StrictMath.log(factor.at(fraction));
        }
        return logarithm;
    }

    private static void addCrossings(TreeSet<Double> cuts, List<Linear> factors, double left, double right) {
        // 每个单调半段与上下限各至多相交一次。
        if (left == right) {
            return;
        }
        double leftLog = logProduct(factors, left);
        double rightLog = logProduct(factors, right);
        for (double limit : new double[] {LOG_MINIMUM, LOG_MAXIMUM}) {
            if ((leftLog < limit && rightLog > limit) || (leftLog > limit && rightLog < limit)) {
                cuts.add(crossing(factors, left, right, limit));
            }
        }
    }

    private static double crossing(List<Linear> factors, double left, double right, double limit) {
        // 固定次数二分，不以经过时长或每秒采样决定计算量。
        double low = left;
        double high = right;
        boolean increasing = logProduct(factors, right) > logProduct(factors, left);
        for (int iteration = 0; iteration < ROOT_ITERATIONS; iteration++) {
            double middle = (low + high) * HALF;
            boolean below = logProduct(factors, middle) < limit;
            if (below == increasing) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (low + high) * HALF;
    }

    private static double quadrature(List<Linear> factors, double left, double right) {
        // 切开截断交点后，每段为常数或至多 32 次多项式，17 点高斯足以覆盖。
        double middle = (left + right) * HALF;
        double radius = (right - left) * HALF;
        double middleProduct = linearProduct(factors, middle);
        if (middleProduct == CapabilityModifier.MIN_TOTAL_MULTIPLIER) {
            return (right - left) * CapabilityModifier.MIN_TOTAL_MULTIPLIER;
        }
        if (middleProduct == CapabilityModifier.MAX_TOTAL_MULTIPLIER) {
            return (right - left) * CapabilityModifier.MAX_TOTAL_MULTIPLIER;
        }
        double weightedSum = CENTER_WEIGHT * middleProduct;
        for (int index = 0; index < NODES.length; index++) {
            double offset = radius * NODES[index];
            double pair = linearProduct(factors, middle - offset) + linearProduct(factors, middle + offset);
            weightedSum += WEIGHTS[index] * pair;
        }
        return radius * weightedSum;
    }

    private static double linearProduct(List<Linear> factors, double fraction) {
        // 高斯节点也使用完整浮点乘积；只有极端配置才需要对数域回算。
        double product = 1;
        for (Linear factor : factors) {
            product *= factor.at(fraction);
            if (!Double.isFinite(product) || product < Double.MIN_NORMAL) {
                return clampedExponential(logProduct(factors, fraction));
            }
        }
        return clamp(product);
    }
}
