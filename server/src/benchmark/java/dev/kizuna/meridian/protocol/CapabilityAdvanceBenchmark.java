package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.OpeningTask;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 可重复的单次推进基准；构型生成、结果验证和逐段对照均不计入计时。 */
public final class CapabilityAdvanceBenchmark {
    private static final long START_MILLIS = 1_000;
    private static final long TARGET_MILLIS = 1_600;
    private static final int MAX_MODIFIERS = 32;
    private static final int MAX_POINTS = 16;
    private static final int POINT_SPACING_MILLIS = 32;
    private static final int DEFAULT_WARMUP = 200;
    private static final int DEFAULT_SAMPLES = 51;
    private static final int DAILY_WARMUP = 100_000;
    private static final int DAILY_SAMPLES = 501;
    private static final int SHORT_CURVE_POINTS = 2;
    private static final int TYPICAL_CURVE_POINTS = 6;
    private static final int SMALL_STACK_SIZE = 4;
    private static final int SMALL_STACK_POINTS = 8;
    private static final double PEAK = 1.01;
    private static final double CROSSING_PEAK = 3;
    private static final double CROSSING_LOW_VALUE = -0.475;
    private static final double CROSSING_LATE_VALUE = 0.5;
    private static final int POINT_MIDPOINT = MAX_POINTS / 2;
    private static final double NANOS_PER_MILLI = 1_000_000;
    private static final double P95 = 0.95;
    private static volatile BodyTimeAdvancement.Result consumed;

    private CapabilityAdvanceBenchmark() {
        // 独立任务入口，不注册测试或后台任务。
    }

    private enum Kind { SAME, SPREAD, CROSSINGS, OPENING }

    private record Scenario(String name, BodySnapshot body, int warmup, int samples) { }

    @Name("dev.kizuna.meridian.AdvanceSample")
    @StackTrace(false /* value */)
    private static final class AdvanceSample extends Event {
        public String scenario;
        public String mode;
        public String phase;
        public int sample;
        public String openingStatus;
        public long heldMillis;
    }

    public static void main(String[] args) {
        // 固定 Java 版本及输入，只计 apply；属性允许单独重测某组和增加预热次数。
        if (Runtime.version().feature() != 17) {
            throw new IllegalStateException("基准必须使用 Java 17");
        }
        int warmup = Integer.getInteger("benchmarkWarmup", DEFAULT_WARMUP);
        int samples = Integer.getInteger("benchmarkSamples", DEFAULT_SAMPLES);
        String selection = System.getProperty("benchmarkScenario", "");
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        System.out.println("scenario,mode,warmup,samples,median_ms,p95_ms,max_ms,max_sample");
        for (Scenario scenario : scenarios(warmup, samples)) {
            if (!selection.isEmpty() && !scenario.name().equals(selection)) {
                continue;
            }
            measure(scenario, BodyTimeAdvancement.Mode.ACTIVE);
            measure(scenario, BodyTimeAdvancement.Mode.LIFESPAN_ONLY);
        }
    }

    private static List<Scenario> scenarios(int warmup, int samples) {
        // 日常组使用更多预热和样本，降低微秒级计时的 JIT 与时钟噪声。
        var scenarios = new ArrayList<Scenario>();
        scenarios.add(new Scenario("daily-none", CapabilityModifiersTest.recoveringBody(),
                DAILY_WARMUP, DAILY_SAMPLES));
        scenarios.add(new Scenario("daily-1x2", modified(Kind.SAME, 1, SHORT_CURVE_POINTS),
                DAILY_WARMUP, DAILY_SAMPLES));
        scenarios.add(new Scenario("daily-1x6", modified(Kind.SAME, 1, TYPICAL_CURVE_POINTS),
                DAILY_WARMUP, DAILY_SAMPLES));
        scenarios.add(new Scenario("daily-4x8", modified(Kind.SAME, SMALL_STACK_SIZE, SMALL_STACK_POINTS),
                DAILY_WARMUP, DAILY_SAMPLES));
        for (Kind kind : Kind.values()) {
            scenarios.add(new Scenario(kind.name().toLowerCase(Locale.ROOT),
                    modified(kind, MAX_MODIFIERS, MAX_POINTS), warmup, samples));
        }
        return scenarios;
    }

    private static void measure(Scenario scenario, BodyTimeAdvancement.Mode mode) {
        // 所有调用重用同一不可变输入；测量不包含 fixture、快照编码或逐段对照。
        TimeCursor start = scenario.body().settledAt();
        var target = new TimeCursor(start.domain(), start.epoch(), TARGET_MILLIS);
        var plan = new BodyTimeAdvancement.Plan(target, 0, mode);
        warmup(scenario, plan);
        long[] nanos = samples(scenario, plan);
        int maximumSample = 0;
        for (int sample = 1; sample < nanos.length; sample++) {
            if (nanos[sample] > nanos[maximumSample]) {
                maximumSample = sample;
            }
        }
        Arrays.sort(nanos);
        int p95Index = (int) StrictMath.ceil(nanos.length * P95) - 1;
        System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%.6f,%.6f,%.6f,%d%n", scenario.name(), mode,
                scenario.warmup(), scenario.samples(), nanos[nanos.length / 2] / NANOS_PER_MILLI,
                nanos[p95Index] / NANOS_PER_MILLI, nanos[nanos.length - 1] / NANOS_PER_MILLI, maximumSample + 1);
    }

    private static void warmup(Scenario scenario, BodyTimeAdvancement.Plan plan) {
        // 切换到开脉构型会使快照校验重新编译；预热覆盖新的分支及后续 C2 编译。
        var event = new AdvanceSample();
        event.scenario = scenario.name();
        event.mode = plan.mode().name();
        event.phase = "warmup";
        event.begin();
        for (int iteration = 0; iteration < scenario.warmup(); iteration++) {
            consumed = BodyTimeAdvancement.apply(scenario.body(), plan);
        }
        event.end();
        event.commit();
    }

    private static long[] samples(Scenario scenario, BodyTimeAdvancement.Plan plan) {
        // JFR 标记包围同一个 apply，并保留一基序号；验证和记录提交都在计时之外。
        var nanos = new long[scenario.samples()];
        for (int sample = 0; sample < nanos.length; sample++) {
            var event = new AdvanceSample();
            event.scenario = scenario.name();
            event.mode = plan.mode().name();
            event.phase = "sample";
            event.sample = sample + 1;
            event.begin();
            long startedAt = System.nanoTime();
            BodyTimeAdvancement.Result result = BodyTimeAdvancement.apply(scenario.body(), plan);
            nanos[sample] = System.nanoTime() - startedAt;
            event.end();
            OpeningTask task = result.body().openingTask();
            if (task != null) {
                event.openingStatus = task.status().name();
                event.heldMillis = task.heldMillis();
            }
            event.commit();
            consumed = result;
            verifyResult(scenario, plan, result);
        }
        return nanos;
    }

    private static void verifyResult(Scenario scenario, BodyTimeAdvancement.Plan plan,
                                     BodyTimeAdvancement.Result result) {
        // 每个样本都应推进相同区间，保持单版本提交与实际开脉维持进度。
        BodySnapshot settled = result.body();
        long expectedRevision = scenario.body().revision() + 1;
        if (!settled.settledAt().equals(plan.target()) || settled.revision() != expectedRevision) {
            throw new AssertionError("推进没有到达目标或版本递增不为一次");
        }
        if (plan.mode() != BodyTimeAdvancement.Mode.ACTIVE || !scenario.name().equals("opening")) {
            return;
        }
        OpeningTask task = settled.openingTask();
        if (task == null || task.status() != OpeningTask.Status.HOLDING
                || task.heldMillis() != TARGET_MILLIS - START_MILLIS) {
            throw new AssertionError("开脉基准没有实际累计全部适应进度");
        }
    }

    private static BodySnapshot modified(Kind kind, int modifierCount, int pointCount) {
        // 32×16 的绝对时刻交错，共 512 个边界；分散组每个组织恰有一条修正。
        BodySnapshot base = CapabilityModifiersTest.recoveringBody();
        if (kind == Kind.SPREAD) {
            base = spreadBody(base);
        }
        var modifiers = new ArrayList<BodyMessages.CapabilityModifier>();
        for (int index = 0; index < modifierCount; index++) {
            String tissueId = "tendril";
            if (kind == Kind.SPREAD && index > 0) {
                tissueId = "tendril-" + index;
            }
            modifiers.add(modifier(kind, index, tissueId, pointCount));
        }
        BodySnapshot modified = CapabilityModifiersTest.add(base, modifiers);
        if (kind == Kind.OPENING) {
            return CapabilityModifiersTest.holdingOpening(modified);
        }
        return modified;
    }

    private static BodyMessages.CapabilityModifier modifier(Kind kind, int modifierIndex, String tissueId,
                                                             int pointCount) {
        // 各组仅改变组织分布或恢复峰值，点的绝对时刻与其余维度保持相同。
        var modifier = BodyMessages.CapabilityModifier.newBuilder()
                .setId("sample:bench_" + modifierIndex)
                .setSource("sample:technique")
                .setTissueId(tissueId)
                .setStartMillis(START_MILLIS + modifierIndex)
                .setStrengthPeak(PEAK)
                .setBurstPeak(PEAK)
                .setEndurancePeak(PEAK)
                .setRecoveryPeak(PEAK);
        if (kind == Kind.CROSSINGS) {
            modifier.setRecoveryPeak(CROSSING_PEAK);
        }
        for (int pointIndex = 0; pointIndex < pointCount; pointIndex++) {
            double value = pointIndex % 2;
            if (kind == Kind.CROSSINGS) {
                value = crossingValue(modifierIndex, pointIndex);
            }
            var point = BodyMessages.CurvePoint.newBuilder()
                    .setOffsetMillis((long) pointIndex * POINT_SPACING_MILLIS)
                    .setValue(value)
                    .build();
            modifier.addCurve(point);
        }
        return modifier.build();
    }

    private static double crossingValue(int modifierIndex, int pointIndex) {
        // 两侧各自越过总倍率上下限，避免把未触及截断的输入误当截断基准。
        if ((pointIndex + modifierIndex) % 2 != 0) {
            return CROSSING_LOW_VALUE;
        }
        if (pointIndex < POINT_MIDPOINT) {
            return 1;
        }
        return CROSSING_LATE_VALUE;
    }

    private static BodySnapshot spreadBody(BodySnapshot body) {
        // 复用相同基础组织参数，移除新增组织的端口以保持全身端口身份唯一。
        var original = BodyProtobuf.toMessage(body);
        var definition = original.getStructure().getTissues(0);
        var state = original.getTissueStates(0);
        var structure = original.getStructure().toBuilder()
                .clearTissues()
                .addTissues(definition);
        var candidate = original.toBuilder()
                .clearTissueStates()
                .addTissueStates(state);
        for (int index = 1; index < MAX_MODIFIERS; index++) {
            String id = "tendril-" + index;
            var tissue = definition.toBuilder()
                    .setId(id)
                    .clearPorts()
                    .build();
            var tissueState = state.toBuilder().setId(id).build();
            structure.addTissues(tissue);
            candidate.addTissueStates(tissueState);
        }
        var message = candidate.setStructure(structure).build();
        return BodyProtobuf.fromMessage(message);
    }
}
