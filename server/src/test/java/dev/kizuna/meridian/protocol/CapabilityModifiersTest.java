package dev.kizuna.meridian.protocol;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.fixture.pb.SampleOpeningMessages.SampleOpeningConditions;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.ActivityEffort;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.OpeningPolicyRequest;
import dev.kizuna.meridian.server.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static dev.kizuna.meridian.core.BodyTimeAdvancement.Mode.ACTIVE;
import static dev.kizuna.meridian.core.BodyTimeAdvancement.Mode.LIFESPAN_ONLY;
import static org.junit.Assert.*;

/** 保护临时能力修正的公开契约、原子活动及连续负载的到期结算。 */
public class CapabilityModifiersTest {
    private static final Anatomy.TissueId TISSUE = new Anatomy.TissueId("tendril");
    private static final String OTHER_TISSUE = "other";
    private static final String SOURCE = "sample:technique";
    private static final long START_MILLIS = 1_000;
    private static final long EXPIRY_MILLIS = 3_000;
    private static final long END_MILLIS = 5_000;
    private static final long FAR_FUTURE_MILLIS = 100_000;
    private static final long LONG_ADVANCE_MILLIS = 30_000;
    private static final long OPENING_DURATION_MILLIS = 100_000;
    private static final double STRENGTH_NEWTONS = 100;
    private static final double BURST_WATTS = 200;
    private static final double ENDURANCE_JOULES = 1_000;
    private static final double FATIGUE_CAPACITY = 20;
    private static final double BOOST = 2;
    private static final double HALF = 0.5;
    private static final double DEBUFF = 0.1;
    private static final double CONTINUOUS_WATTS = 50;
    private static final double TOLERANCE = 1e-9;
    private static final double MILLIS_PER_SECOND = 1_000;
    private static final long RAMP_MILLIS = 1_000;
    private static final double RECOVERY_HALF_LIFE_SECONDS = 10;
    private static final double INITIAL_FATIGUE = 10;
    private static final int TERMINATION_SCENARIOS = 48;
    private static final long TERMINATION_SEED = 20_261_026;
    private static final long TERMINATION_TIMEOUT_MILLIS = 15_000;
    private static final long SCENARIO_TIMEOUT_SECONDS = 2;
    private static final int FROZEN_SCENARIO = 1;
    private static final int OPENING_SCENARIO = 1 << 1;
    private static final int DEATH_SCENARIO = 1 << 2;
    private static final int STRESS_CLOCK_RANGES = 3;
    private static final int IRREGULAR_OPENING_SCENARIO = 2;
    private static final int SHARED_START_VARIANTS = 3;
    private static final int STRESS_POINT_SPACING_MILLIS = 25;
    private static final int STRESS_TARGET_JITTER_MILLIS = 500;
    private static final long STRESS_TARGET_DELAY_MILLIS = 2_000;
    private static final long STRESS_CLOCK_MARGIN_MILLIS = 3_000;
    private static final int STRESS_LIFESPAN_QUANTUM_MILLIS = 125;
    private static final int STRESS_LIFESPAN_QUANTA = 7;
    private static final double STRESS_PEAK_VARIATION = 0.01;

    @Test
    public void productsClampAfterAllFactorsAndAffectOnlyTheirTissue() {
        // 三个维度独立相乘；先超上限再乘减益，也必须保留全部因子。
        var first = modifier("sample:first", EXPIRY_MILLIS);
        var second = first.toBuilder()
                .setId("sample:second")
                .setBurstPeak(CapabilityModifier.MAX_PEAK)
                .setEndurancePeak(CapabilityModifier.MAX_PEAK)
                .build();
        BodySnapshot multiplied = add(body(), List.of(first, second));
        double strengthProduct = BOOST * BOOST;
        assertCapacity(multiplied, strengthProduct, CapabilityModifier.MAX_TOTAL_MULTIPLIER,
                CapabilityModifier.MAX_TOTAL_MULTIPLIER);
        assertEquals(new Anatomy.TissueCapacity(STRENGTH_NEWTONS, BURST_WATTS, ENDURANCE_JOULES),
                TissueCapabilities.available(multiplied, new Anatomy.TissueId(OTHER_TISSUE)));

        var reduction = first.toBuilder()
                .setId("sample:reduction")
                .setStrengthPeak(DEBUFF)
                .setBurstPeak(DEBUFF)
                .setEndurancePeak(DEBUFF)
                .build();
        BodySnapshot reduced = add(multiplied, List.of(reduction));
        assertCapacity(reduced, strengthProduct * DEBUFF, BOOST, BOOST);
        var reversed = new ArrayList<>(BodyProtobuf.toMessage(reduced).getModifiersList());
        Collections.reverse(reversed);
        var reordered = BodyProtobuf.toMessage(reduced).toBuilder()
                .clearModifiers()
                .addAllModifiers(reversed)
                .build();
        assertEquals(TissueCapabilities.available(reduced, TISSUE),
                TissueCapabilities.available(BodyProtobuf.fromMessage(reordered), TISSUE));

        BodySnapshot floor = add(body(), List.of(reduction,
                reduction.toBuilder().setId("sample:reduction/second").build()));
        assertCapacity(floor, CapabilityModifier.MIN_TOTAL_MULTIPLIER,
                CapabilityModifier.MIN_TOTAL_MULTIPLIER, CapabilityModifier.MIN_TOTAL_MULTIPLIER);
    }

    @Test
    public void invalidMultipliersRejectTheWholeActivityInEveryDimension() {
        // 缺省零、非有限数和越界值在每个倍率字段上都不能绕过整笔拒绝。
        var valid = modifier("sample:valid", EXPIRY_MILLIS);
        double aboveMaximum = StrictMath.nextUp(CapabilityModifier.MAX_PEAK);
        for (String field : List.of("strength_peak", "burst_peak", "endurance_peak", "recovery_peak")) {
            for (double invalid : List.of(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, aboveMaximum)) {
                var descriptor = valid.getDescriptorForType().findFieldByName(field);
                var invalidModifier = valid.toBuilder()
                        .setId("sample:invalid")
                        .setField(descriptor, invalid)
                        .build();
                assertRejected(body(), BodyActivity.newBuilder()
                        .addAddModifiers(valid)
                        .addAddModifiers(invalidModifier)
                        .build());
            }
        }
    }

    @Test
    public void curvesInterpolateAtArbitraryTimesIncludingNegativeValues() {
        // 负振幅表达虚脱期；任意时刻按分段线性插值，首尾非零也可保存。
        var curve = modifier("sample:curve", END_MILLIS).toBuilder()
                .clearCurve()
                .addCurve(point(0, 0))
                .addCurve(point(RAMP_MILLIS, 1))
                .addCurve(point(RAMP_MILLIS * 2, -HALF))
                .addCurve(point(RAMP_MILLIS * 3, 0))
                .build();
        BodySnapshot initial = add(body(), List.of(curve));
        long quarterRamp = RAMP_MILLIS / 4;
        double quarter = 0.25;
        for (int index = 1; index <= 3; index++) {
            long millis = START_MILLIS + quarterRamp * index;
            assertCapacity(at(initial, millis), 1 + quarter * index, 1 + quarter * index, 1 + quarter * index);
        }
        double middleMultiplier = 1 + quarter;
        BodySnapshot falling = at(initial, START_MILLIS + RAMP_MILLIS + RAMP_MILLIS / 2);
        assertCapacity(falling, middleMultiplier, middleMultiplier, middleMultiplier);
        BodySnapshot trough = at(initial, START_MILLIS + RAMP_MILLIS * 2);
        assertCapacity(trough, HALF, HALF, HALF);
        assertEquals(initial, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(initial)));
    }

    @Test
    public void invalidIdentityTissueAndExpiryRejectTheWholeActivity() {
        // 内容身份沿用命名空间语法，组织引用沿用局部 ID，期限必须严格晚于游标。
        var valid = modifier("sample:valid", EXPIRY_MILLIS);
        var invalid = List.of(
                valid.toBuilder().setId("").build(),
                valid.toBuilder().setId("Bad:identity").build(),
                valid.toBuilder().setId("sample:cast:0").build(),
                valid.toBuilder().setSource("").build(),
                valid.toBuilder().setSource("invalid").build(),
                valid.toBuilder().setTissueId("").build(),
                valid.toBuilder().setTissueId("sample:tendril").build(),
                valid.toBuilder().setTissueId("missing").build(),
                valid.toBuilder().setStartMillis(-1).build(),
                valid.toBuilder().setStartMillis(Long.MAX_VALUE).build());
        for (BodyMessages.CapabilityModifier modifier : invalid) {
            assertRejected(body(), BodyActivity.newBuilder().addAddModifiers(modifier).build());
            var snapshot = BodyProtobuf.toMessage(body()).toBuilder().addModifiers(modifier).build();
            assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.decodeSnapshot(snapshot.toByteArray()));
        }
        var pastStart = valid.toBuilder().setStartMillis(START_MILLIS - 1).build();
        assertRejected(body(), BodyActivity.newBuilder().addAddModifiers(pastStart).build());
    }

    @Test
    public void duplicateIdsAndTheBodyLimitAreAtomic() {
        // 同笔重复、与既有 ID 冲突以及合计超限均拒绝，恰好 32 条可以保存。
        var modifier = modifier("sample:existing", FAR_FUTURE_MILLIS);
        BodySnapshot existing = add(body(), List.of(modifier));
        assertRejected(body(), BodyActivity.newBuilder()
                .addAddModifiers(modifier)
                .addAddModifiers(modifier)
                .build());
        assertRejected(existing, BodyActivity.newBuilder().addAddModifiers(modifier).build());
        var additions = new ArrayList<BodyMessages.CapabilityModifier>();
        for (int index = 0; index < CapabilityModifier.MAX_PER_BODY; index++) {
            additions.add(modifier.toBuilder().setId("sample:modifier/" + index).build());
        }
        BodySnapshot full = add(body(), additions);
        assertEquals(CapabilityModifier.MAX_PER_BODY, full.modifiers().size());
        assertEquals(full, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(full)));
        assertRejected(existing, BodyActivity.newBuilder().addAllAddModifiers(additions).build());
        additions.add(modifier);
        assertRejected(body(), BodyActivity.newBuilder().addAllAddModifiers(additions).build());
    }

    @Test
    public void removalRequiresAnExistingUniqueIdAndCannotReplaceIt() {
        // 撤销不存在的 ID 与停止不存在的动作一样失败，不允许同笔撤销后覆盖旧来源。
        var modifier = modifier("sample:existing", FAR_FUTURE_MILLIS);
        BodySnapshot initial = add(body(), List.of(modifier));
        for (List<String> removals : List.of(List.of("sample:missing"), List.of("invalid"),
                List.of(modifier.getId(), modifier.getId()))) {
            assertRejected(initial, BodyActivity.newBuilder().addAllRemoveModifiers(removals).build());
        }
        assertRejected(initial, BodyActivity.newBuilder()
                .addRemoveModifiers(modifier.getId())
                .addAddModifiers(modifier)
                .build());
        BodySnapshot removed = BodyActivityExecution.apply(initial,
                BodyActivity.newBuilder().addRemoveModifiers(modifier.getId()).build(), SOURCE);
        assertTrue(removed.modifiers().isEmpty());
        assertCapacity(removed, 1, 1, 1);
        assertEquals(initial.revision() + 1, removed.revision());
    }

    @Test
    public void queryAndExertTissueUseModifiedHealthAndFatigue() {
        // 受伤和疲劳仍先限制基础能力，修正只放大目标组织的有效值。
        var message = BodyProtobuf.toMessage(body());
        var hurtTissue = message.getTissueStates(0).toBuilder()
                .setHp(message.getTissueStates(0).getHp() * HALF)
                .setFatigue(FATIGUE_CAPACITY * HALF)
                .build();
        BodySnapshot hurt = BodyProtobuf.fromMessage(message.toBuilder().setTissueStates(0, hurtTissue).build());
        BodySnapshot boosted = add(hurt, List.of(modifier("sample:boost", EXPIRY_MILLIS)));
        double effectiveRatio = HALF * HALF * BOOST;
        assertCapacity(boosted, effectiveRatio, effectiveRatio, effectiveRatio);

        var store = new InMemoryBodyStore(List.of(boosted), List.of());
        var query = MeridianMessages.GetTissueCapability.newBuilder()
                .setBodyId(boosted.id().value())
                .setTissueId(TISSUE.value())
                .build();
        var request = envelope("query").setGetTissueCapability(query).build();
        var reader = new BodyQueryHandler<String>((caller, id) -> true, store);
        var response = MeridianProtocol.decodeResponse(reader.handle("host", MeridianProtocol.encodeRequest(request)));
        assertEquals(ENDURANCE_JOULES * effectiveRatio,
                response.getTissueCapability().getAvailable().getEnduranceJoules(), 0);

        var exertion = MeridianMessages.ExertTissue.newBuilder()
                .setTissueId(TISSUE.value())
                .setForceNewtons(STRENGTH_NEWTONS * effectiveRatio)
                .setPowerWatts(BURST_WATTS * effectiveRatio)
                .setDurationSeconds(1)
                .build();
        var command = command(boosted).setExertTissue(exertion).build();
        assertCommitted(handler(store).handle("host", packet("exert", command)));
        assertEquals(boosted.modifiers(), store.find(boosted.id()).orElseThrow().modifiers());
    }

    @Test
    public void activityEffortsUseAddedAndRemovedModifiersAndRollbackOnFailure() {
        // 同笔新增先用于做功校验；后续负载失败时，新增和撤销都不能部分提交。
        var modifier = modifier("sample:boost", EXPIRY_MILLIS);
        double boostedWatts = BURST_WATTS * BOOST;
        var plan = BodyActivity.newBuilder()
                .addAddModifiers(modifier)
                .addEfforts(effort(TISSUE.value(), boostedWatts))
                .build();
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store);
        byte[] request = packet("activity", command(initial).setExecuteActivity(plan).build());
        assertEquals(MeridianMessages.FailureCode.ACCESS_DENIED,
                MeridianProtocol.decodeResponse(handler.handle("host", request)).getFailure().getCode());
        byte[] committed = handler.handleActivity(SOURCE, request);
        assertCommitted(committed);
        assertArrayEquals(committed, handler.handleActivity(SOURCE, request));
        BodySnapshot updated = store.find(initial.id()).orElseThrow();
        assertEquals(1, updated.revision());
        assertEquals(FATIGUE_CAPACITY * BURST_WATTS / ENDURANCE_JOULES,
                updated.tissueStates().get(0).fatigue(), TOLERANCE);

        var replacement = modifier.toBuilder().setId("sample:new").build();
        var invalid = BodyActivity.newBuilder()
                .addRemoveModifiers(modifier.getId())
                .addAddModifiers(replacement)
                .addEfforts(effort(OTHER_TISSUE, boostedWatts))
                .build();
        byte[] rejected = handler.handleActivity(SOURCE,
                packet("invalid-effort", command(updated).setExecuteActivity(invalid).build()));
        assertEquals(MeridianMessages.FailureCode.TISSUE_LOAD_EXCEEDED,
                MeridianProtocol.decodeResponse(rejected).getFailure().getCode());
        assertEquals(updated, store.find(updated.id()).orElseThrow());
        assertEquals(1, store.receipts().size());
    }

    @Test
    public void effectiveLimitsRejectExcessForcePowerEnergyAndRemovedBoosts() {
        // 三项预算各自受有效能力约束；同笔移除后也不能继续借用已撤销的功率。
        var modifier = modifier("sample:boost", EXPIRY_MILLIS);
        BodySnapshot initial = add(body(), List.of(modifier));
        var excessive = List.of(
                new TissueCapabilities.Exertion(TISSUE, STRENGTH_NEWTONS * BOOST + 1, 1, 1),
                new TissueCapabilities.Exertion(TISSUE, 1, BURST_WATTS * BOOST + 1, 1),
                new TissueCapabilities.Exertion(TISSUE, 1, 1, ENDURANCE_JOULES * BOOST + 1));
        for (TissueCapabilities.Exertion exertion : excessive) {
            assertEquals(MeridianErrors.CapabilityRejectionReason.TISSUE_LOAD_EXCEEDED,
                    assertThrows(MeridianErrors.CapabilityRejected.class,
                            () -> TissueCapabilities.exert(initial, exertion)).reason());
        }
        var removedBoost = BodyActivity.newBuilder()
                .addRemoveModifiers(modifier.getId())
                .addEfforts(effort(TISSUE.value(), BURST_WATTS * BOOST))
                .build();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        byte[] response = handler(store).handleActivity(SOURCE,
                packet("removed-boost", command(initial).setExecuteActivity(removedBoost).build()));
        assertEquals(MeridianMessages.FailureCode.TISSUE_LOAD_EXCEEDED,
                MeridianProtocol.decodeResponse(response).getFailure().getCode());
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertTrue(store.receipts().isEmpty());
    }

    @Test
    public void expirationSplitsContinuousWorkLikeTwoAdvances() {
        // 做功闭区间包含到期点，跨阶跃的负载按较低倍率扣费，拆分结算仍保持一致。
        BodySnapshot started = opening(add(body(), List.of(modifier("sample:boost", EXPIRY_MILLIS))));
        var whole = BodyTimeAdvancement.apply(started, plan(END_MILLIS, ACTIVE));
        var first = BodyTimeAdvancement.apply(started, plan(EXPIRY_MILLIS, ACTIVE));
        var second = BodyTimeAdvancement.apply(first.body(), plan(END_MILLIS, ACTIVE));
        assertTrue(first.body().modifiers().isEmpty());
        var splitSnapshot = BodyProtobuf.toMessage(second.body()).toBuilder()
                .setRevision(whole.body().revision())
                .build();
        assertEquals(BodyProtobuf.fromMessage(splitSnapshot), whole.body());
        assertEquals(QiInventory.combine(List.of(first.releasedQi(), second.releasedQi())), whole.releasedQi());
        assertEquals(started.revision() + 1, whole.body().revision());
        double segmentSeconds = (EXPIRY_MILLIS - START_MILLIS) / MILLIS_PER_SECOND;
        double beforeExpiryFatigue = CONTINUOUS_WATTS * segmentSeconds / ENDURANCE_JOULES;
        double afterExpiryFatigue = CONTINUOUS_WATTS * segmentSeconds / ENDURANCE_JOULES;
        assertEquals(FATIGUE_CAPACITY * (beforeExpiryFatigue + afterExpiryFatigue),
                whole.body().tissueStates().get(0).fatigue(), TOLERANCE);
    }

    @Test
    public void independentBurstAndEnduranceModifiersSetTheContinuousBudget() {
        // 功率减半、耐力翻倍时应在有效功率降至维持值的时刻停下。
        var modifier = modifier("sample:unbalanced", FAR_FUTURE_MILLIS).toBuilder()
                .setBurstPeak(HALF)
                .build();
        BodySnapshot started = opening(add(body(), List.of(modifier)));
        var result = BodyTimeAdvancement.apply(started, plan(LONG_ADVANCE_MILLIS, ACTIVE));
        assertEquals(OpeningTask.Status.EXHAUSTED, result.body().openingTask().status());
        assertEquals(FATIGUE_CAPACITY * HALF, result.body().tissueStates().get(0).fatigue(), TOLERANCE);
        assertEquals(started.modifiers(), result.body().modifiers());
    }

    @Test
    public void expirationRunsAtTheExactBoundaryAndWhileFrozen() {
        // 身体时钟决定期限；冻结资源推进不会延长修正，也不需要宿主另发撤销请求。
        BodySnapshot initial = add(body(), List.of(modifier("sample:boost", EXPIRY_MILLIS)));
        for (BodyTimeAdvancement.Mode mode : List.of(ACTIVE, LIFESPAN_ONLY)) {
            assertEquals(EXPIRY_MILLIS,
                    BodyTimeAdvancement.nextEventBoundary(initial, 0, mode).orElseThrow().elapsedMillis());
            BodySnapshot beforeExpiry = BodyTimeAdvancement.apply(initial, plan(EXPIRY_MILLIS - 1, mode)).body();
            assertEquals(initial.modifiers(), beforeExpiry.modifiers());
            BodySnapshot expired = BodyTimeAdvancement.apply(beforeExpiry, plan(EXPIRY_MILLIS, mode)).body();
            assertTrue(expired.modifiers().isEmpty());
            assertCapacity(expired, 1, 1, 1);
            var excessiveForce = new TissueCapabilities.Exertion(TISSUE, STRENGTH_NEWTONS * BOOST, 1, 1);
            assertThrows(MeridianErrors.CapabilityRejected.class,
                    () -> TissueCapabilities.exert(expired, excessiveForce));
        }
    }

    @Test
    public void snapshotsReceiptsAndOrdinaryOperationsPreserveModifiers() {
        // 保存快照和成功回执后重试不重复添加；其他身体操作也必须传递既有修正。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var addition = BodyActivity.newBuilder().addAddModifiers(modifier("sample:boost", FAR_FUTURE_MILLIS)).build();
        byte[] request = packet("persist", command(initial).setExecuteActivity(addition).build());
        byte[] response = handler(store).handleActivity(SOURCE, request);
        assertCommitted(response);
        BodySnapshot saved = store.find(initial.id()).orElseThrow();
        BodySnapshot restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(saved));
        var receipts = store.receipts().stream()
                .map(receipt -> MeridianProtocol.decodeReceipt(MeridianProtocol.encodeReceipt(receipt)))
                .toList();
        var restoredStore = new InMemoryBodyStore(List.of(restored), receipts, store.environmentQi());
        assertEquals(saved, restored);
        assertArrayEquals(response, handler(restoredStore).handleActivity(SOURCE, request));

        BodySnapshot occupied = BodyActions.start(restored, "focus", ActionProtobuf.fromDefinition(action()));
        BodySnapshot stopped = BodyActions.stop(occupied, "focus");
        var progress = Any.pack(BodyProtobuf.toMessage(restored).getTemplate());
        BodySnapshot progressed = AddonProgress.replace(stopped, "sample:state", progress);
        BodySnapshot spent = LifespanSpending.apply(progressed, 1);
        assertEquals(saved.modifiers(), spent.modifiers());
        assertEquals(saved.modifiers(), TissueCapabilities.exertAll(spent,
                List.of(new TissueCapabilities.Exertion(TISSUE, 1, 1, 1))).modifiers());
    }

    @Test
    public void lifespanAndHealthDeathsClearModifiers() {
        // 寿尽、伤害致死和外部死亡确认都清理修正，不把临时增益带入死体存档。
        BodySnapshot boosted = add(body(), List.of(modifier("sample:boost", FAR_FUTURE_MILLIS)));
        var message = BodyProtobuf.toMessage(boosted);
        var vitality = message.getVitality().toBuilder().setLifespanLimitSeconds(1).build();
        BodySnapshot mortal = BodyProtobuf.fromMessage(message.toBuilder().setVitality(vitality).build());
        var aging = new BodyTimeAdvancement.Plan(plan(EXPIRY_MILLIS, ACTIVE).target(), 1, ACTIVE);
        var died = BodyTimeAdvancement.apply(mortal, aging);
        assertTrue(died.died());
        assertTrue(died.body().modifiers().isEmpty());
        assertCapacity(died.body(), 0, 0, 0);

        var damage = HealthMessages.DamageBody.newBuilder()
                .setSourceId("sample:attack")
                .setDamageType("sample:impact")
                .setKind(HealthMessages.DamageKind.BLUNT)
                .setDamageHp(STRENGTH_NEWTONS * BOOST)
                .build();
        var confirm = HealthMessages.ConfirmBodyDeath.newBuilder().setSourceId("sample:host").build();
        for (var command : List.of(command(boosted).setDamageBody(damage).build(),
                command(boosted).setConfirmBodyDeath(confirm).build())) {
            var store = new InMemoryBodyStore(List.of(boosted), List.of());
            assertCommitted(handler(store).handleHealthEffect("host", packet("death", command)));
            BodySnapshot dead = store.find(boosted.id()).orElseThrow();
            assertTrue(dead.vitality().dead());
            assertTrue(dead.modifiers().isEmpty());
            assertEquals(dead, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(dead)));
        }
    }

    @Test
    public void invalidCurvesAndPointMultipliersRejectAtomically() {
        // 点数、时间、振幅及每个维度的最低倍率都属于同一活动的输入边界。
        var valid = modifier("sample:valid", EXPIRY_MILLIS);
        var tooMany = new ArrayList<BodyMessages.CurvePoint>();
        for (int index = 0; index <= CapabilityModifier.MAX_CURVE_POINTS; index++) {
            tooMany.add(point(index, 0));
        }
        for (List<BodyMessages.CurvePoint> points : invalidCurveCases(tooMany)) {
            var invalid = valid.toBuilder().setId("sample:invalid").clearCurve().addAllCurve(points).build();
            assertRejected(body(), BodyActivity.newBuilder().addAddModifiers(valid).addAddModifiers(invalid).build());
        }
        assertInvalidPointMultipliers(valid);

        var maximumPoints = tooMany.subList(0, CapabilityModifier.MAX_CURVE_POINTS);
        var accepted = valid.toBuilder().clearCurve().addAllCurve(maximumPoints).build();
        BodySnapshot acceptedBody = add(body(), List.of(accepted));
        assertEquals(CapabilityModifier.MAX_CURVE_POINTS, acceptedBody.modifiers().get(0).curve().points().size());
    }

    private static List<List<BodyMessages.CurvePoint>> invalidCurveCases(List<BodyMessages.CurvePoint> tooMany) {
        // 独立列出曲线结构错误，测试流程只负责整笔拒绝与原子性断言。
        return List.of(
                List.<BodyMessages.CurvePoint>of(), List.of(point(0, 1)), tooMany,
                List.of(point(1, 0), point(RAMP_MILLIS, 0)),
                List.of(point(0, 0), point(0, 1)),
                List.of(point(0, 0), point(RAMP_MILLIS, 1), point(1, 0)),
                List.of(point(0, 0), point(CapabilityModifier.MAX_CURVE_DURATION_MILLIS + 1, 0)),
                List.of(point(-1, 0), point(RAMP_MILLIS, 0)),
                List.of(point(0, -BOOST), point(RAMP_MILLIS, 0)),
                List.of(point(0, BOOST), point(RAMP_MILLIS, 0)),
                List.of(point(0, Double.NaN), point(RAMP_MILLIS, 0)),
                List.of(point(0, Double.NEGATIVE_INFINITY), point(RAMP_MILLIS, 0)),
                List.of(point(0, 0), point(RAMP_MILLIS, Double.POSITIVE_INFINITY)));
    }

    private static void assertInvalidPointMultipliers(BodyMessages.CapabilityModifier valid) {
        // 四项分别越过单条最低倍率，其余项保持 1，避免错误被别的维度提前触发。
        for (String field : List.of("strength_peak", "burst_peak", "endurance_peak", "recovery_peak")) {
            var negative = valid.toBuilder().clearCurve()
                    .setStrengthPeak(1)
                    .setBurstPeak(1)
                    .setEndurancePeak(1)
                    .setRecoveryPeak(1)
                    .addCurve(point(0, -1))
                    .addCurve(point(RAMP_MILLIS, 0))
                    .setField(valid.getDescriptorForType().findFieldByName(field), BOOST)
                    .build();
            assertRejected(body(), BodyActivity.newBuilder().addAddModifiers(negative).build());
        }
        var tooLow = valid.toBuilder().setRecoveryPeak(CapabilityModifier.MIN_MULTIPLIER * HALF).build();
        assertRejected(body(), BodyActivity.newBuilder().addAddModifiers(tooLow).build());
    }

    @Test
    public void constantCurvesHaveStartAndExpirySteps() {
        // 常值曲线首尾为 1，在开始前和到期后都使用基础倍率。
        long activationMillis = START_MILLIS + RAMP_MILLIS;
        var fixed = constant("sample:fixed", activationMillis, END_MILLIS, BOOST);
        BodySnapshot initial = add(body(), List.of(fixed));
        assertCapacity(initial, 1, 1, 1);
        assertEquals(activationMillis, BodyTimeAdvancement.nextEventBoundary(initial, 0, ACTIVE)
                .orElseThrow().elapsedMillis());
        assertCapacity(at(initial, activationMillis - 1), 1, 1, 1);
        assertCapacity(at(initial, activationMillis), BOOST, BOOST, BOOST);
        assertCapacity(at(initial, END_MILLIS - 1), BOOST, BOOST, BOOST);
        BodySnapshot expired = at(initial, END_MILLIS);
        assertCapacity(expired, 1, 1, 1);
        assertTrue(expired.modifiers().isEmpty());
    }

    @Test
    public void exertionUsesTheMinimumAcrossKnotsAndBothStepDirections() {
        // 峰值处无法提交穿过低谷的高负载，生效和到期阶跃两侧也必须检查。
        var dipping = modifier("sample:dip", END_MILLIS).toBuilder().clearCurve()
                .addCurve(point(0, 1))
                .addCurve(point(RAMP_MILLIS / 2, 0))
                .addCurve(point(RAMP_MILLIS, 1))
                .build();
        assertForceRejected(add(body(), List.of(dipping)), STRENGTH_NEWTONS * (1 + HALF), 1);

        long activationMillis = START_MILLIS + RAMP_MILLIS;
        long expiryMillis = activationMillis + RAMP_MILLIS;
        BodySnapshot boosted = add(body(), List.of(constant("sample:fixed", activationMillis, expiryMillis, BOOST)));
        assertForceRejected(boosted, STRENGTH_NEWTONS * (1 + HALF), 1 + HALF);
        BodySnapshot activeBoost = at(boosted, activationMillis);
        assertForceRejected(activeBoost, STRENGTH_NEWTONS * (1 + HALF), 1);
        TissueCapabilities.exert(activeBoost, new TissueCapabilities.Exertion(TISSUE,
                STRENGTH_NEWTONS * (1 + HALF), 1, HALF));

        BodySnapshot reduced = add(body(), List.of(constant("sample:weak", activationMillis, expiryMillis, HALF)));
        assertForceRejected(reduced, STRENGTH_NEWTONS * (1 - HALF * HALF), 1 + HALF);
        assertForceRejected(at(reduced, activationMillis), STRENGTH_NEWTONS, 1 + HALF);
        assertCapacity(at(reduced, expiryMillis), 1, 1, 1);
    }

    @Test
    public void exertionKeepsFractionalMillisecondsAtLargeClockValues() {
        // 不能将绝对 long 时刻先转 double，否则临近上限的短曲线和亚毫秒做功会丢失。
        long startMillis = Long.MAX_VALUE - RAMP_MILLIS;
        var original = BodyProtobuf.toMessage(body()).toBuilder()
                .setSettledAt(BodyProtobuf.toTime(plan(startMillis, ACTIVE).target()))
                .build();
        BodySnapshot late = BodyProtobuf.fromMessage(original);
        BodySnapshot boosted = add(late, List.of(constant("sample:late", startMillis, startMillis + 1, BOOST)));
        double halfMillisecondSeconds = HALF / MILLIS_PER_SECOND;
        TissueCapabilities.exert(boosted,
                new TissueCapabilities.Exertion(TISSUE, STRENGTH_NEWTONS * BOOST, 1, halfMillisecondSeconds));
        assertForceRejected(boosted, STRENGTH_NEWTONS * BOOST, 1 / MILLIS_PER_SECOND);
        assertCapacity(at(boosted, startMillis + 1), 1, 1, 1);
    }

    @Test
    public void modifierSourceIsBoundToTheTrustedCallerForAddAndRemove() {
        // 同一来源可以撤销自己的修正；自报其他来源和跨来源删除都不能提交。
        var modifier = modifier("sample:owned", FAR_FUTURE_MILLIS);
        BodySnapshot initial = add(body(), List.of(modifier));
        var removal = BodyActivity.newBuilder().addRemoveModifiers(modifier.getId()).build();
        String otherSource = "other:technique";
        assertThrows(MeridianErrors.IntakeRejected.class,
                () -> BodyActivityExecution.apply(initial, removal, otherSource));
        var forged = modifier.toBuilder().setSource(otherSource).build();
        var addition = BodyActivity.newBuilder().addAddModifiers(forged).build();
        assertThrows(MeridianErrors.IntakeRejected.class, () -> BodyActivityExecution.apply(body(), addition, SOURCE));
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        byte[] response = handler(store).handleActivity(otherSource,
                packet("foreign-remove", command(initial).setExecuteActivity(removal).build()));
        assertEquals(MeridianMessages.FailureCode.ACCESS_DENIED,
                MeridianProtocol.decodeResponse(response).getFailure().getCode());
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        assertTrue(store.receipts().isEmpty());
        assertTrue(BodyActivityExecution.apply(initial, removal, SOURCE).modifiers().isEmpty());
    }

    @Test
    public void recoveryConstantStepsMatchSingleAndPartitionedAdvances() {
        // 起止阶跃不占时间，恢复积分等于基础两秒加翻倍两秒。
        long activationMillis = START_MILLIS + RAMP_MILLIS;
        long expiryMillis = END_MILLIS - RAMP_MILLIS;
        var fixed = constant("sample:recovery", activationMillis, expiryMillis, BOOST);
        double effectiveSeconds = (END_MILLIS - START_MILLIS + expiryMillis - activationMillis) / MILLIS_PER_SECOND;
        assertRecoveryAcrossPartitions(List.of(fixed), effectiveSeconds);
    }

    @Test
    public void recoveryIntegratesProductsWithUpperAndLowerClamps() {
        // 两条斜线相乘后截断，解析积分保护交点定位及任意拆段的一致性。
        double durationSeconds = (END_MILLIS - START_MILLIS) / MILLIS_PER_SECOND;
        for (double peak : List.of(CapabilityModifier.MAX_PEAK, DEBUFF)) {
            var first = recoveryCurve("sample:first", peak, List.of(point(0, 0), point(END_MILLIS - START_MILLIS, 1)));
            var second = first.toBuilder().setId("sample:second").build();
            double bound = CapabilityModifier.MAX_TOTAL_MULTIPLIER;
            if (peak < 1) {
                bound = CapabilityModifier.MIN_TOTAL_MULTIPLIER;
            }
            double root = StrictMath.sqrt(bound);
            double crossing = (root - 1) / (peak - 1);
            double antiderivativePower = 3;
            double polynomialArea = (StrictMath.pow(root, antiderivativePower) - 1)
                    / (antiderivativePower * (peak - 1));
            double effectiveSeconds = durationSeconds * (polynomialArea + bound * (1 - crossing));
            assertRecoveryAcrossPartitions(List.of(first, second), effectiveSeconds);
        }
    }

    @Test
    public void recoveryHandlesInteriorPeaksAndTheMaximumPolynomialDegree() {
        // 相反斜率产生内峰；32 条共同变化时仍用固定阶数积分覆盖整个多项式。
        long durationMillis = END_MILLIS - START_MILLIS;
        double interiorPeak = 6;
        var increasing = recoveryCurve("sample:up", interiorPeak, List.of(point(0, 0), point(durationMillis, 1)));
        var decreasing = recoveryCurve("sample:down", interiorPeak, List.of(point(0, 1), point(durationMillis, 0)));
        double expectedClampedArea = 139.0 / 15;
        double durationSeconds = durationMillis / MILLIS_PER_SECOND;
        assertRecoveryAcrossPartitions(List.of(increasing, decreasing), expectedClampedArea * durationSeconds);

        double peakIncrement = 0.01;
        var many = new ArrayList<BodyMessages.CapabilityModifier>();
        for (int index = 0; index < CapabilityModifier.MAX_PER_BODY; index++) {
            many.add(recoveryCurve("sample:polynomial/" + index, 1 + peakIncrement,
                    List.of(point(0, 0), point(durationMillis, 1))));
        }
        double antiderivativePower = CapabilityModifier.MAX_PER_BODY + 1;
        double integral = (StrictMath.pow(1 + peakIncrement, antiderivativePower) - 1)
                / (peakIncrement * antiderivativePower);
        assertRecoveryAcrossPartitions(many, integral * durationSeconds);
    }

    @Test
    public void recoveryModifiersRespectOccupationDisabilityAndFrozenTime() {
        // 提高恢复倍率不绕过占用、失能或冻结，其他空闲组织仍按基础速率恢复。
        BodySnapshot initial = add(recoveringBody(), List.of(constant("sample:recover", START_MILLIS,
                END_MILLIS, BOOST)));
        BodySnapshot occupied = BodyActions.start(initial, "focus", ActionProtobuf.fromDefinition(action()));
        assertEquals(INITIAL_FATIGUE, BodyTimeAdvancement.apply(occupied, plan(END_MILLIS, ACTIVE))
                .body().tissueStates().get(0).fatigue(), 0);
        assertEquals(INITIAL_FATIGUE, at(initial, END_MILLIS).tissueStates().get(0).fatigue(), 0);
        var message = BodyProtobuf.toMessage(initial);
        var disabled = message.getTissueStates(0).toBuilder().setHp(0).build();
        BodySnapshot hurt = BodyProtobuf.fromMessage(message.toBuilder().setTissueStates(0, disabled).build());
        assertEquals(INITIAL_FATIGUE, BodyTimeAdvancement.apply(hurt, plan(END_MILLIS, ACTIVE))
                .body().tissueStates().get(0).fatigue(), 0);
    }

    @Test(timeout = 5_000)
    public void hugeTimeIntervalsRemoveCurvesWithoutPerSecondIteration() {
        // 大时间跨度只处理已有曲线边界，不能循环模拟经过的秒数。
        BodySnapshot initial = add(recoveringBody(), List.of(modifier("sample:short", EXPIRY_MILLIS)));
        BodySnapshot advanced = BodyTimeAdvancement.apply(initial, plan(Long.MAX_VALUE / 2, ACTIVE)).body();
        assertTrue(advanced.modifiers().isEmpty());
        assertEquals(0, advanced.tissueStates().get(0).fatigue(), 0);
    }

    @Test
    public void openingPoliciesSeeIdenticalSnapshotsDuringWholeAndSavedSegmentAdvances() {
        // 整次推进复用消息投影，逐段推进重建完整输入；两者每次策略可见事实必须逐字段相同。
        var ramp = modifier("sample:projection", EXPIRY_MILLIS).toBuilder()
                .clearCurve()
                .addCurve(point(0, 1))
                .addCurve(point(RAMP_MILLIS, HALF))
                .addCurve(point(EXPIRY_MILLIS - START_MILLIS, 1))
                .build();
        var future = constant("sample:future", EXPIRY_MILLIS, END_MILLIS, BOOST);
        BodySnapshot initial = holdingOpening(add(recoveringBody(), List.of(ramp, future)));
        var wholeRequests = new ArrayList<OpeningPolicyRequest>();
        var splitRequests = new ArrayList<OpeningPolicyRequest>();
        BodySnapshot whole = advanceRecordingPolicies(initial, List.of(END_MILLIS), wholeRequests);
        BodySnapshot split = advanceRecordingPolicies(initial,
                List.of(START_MILLIS + RAMP_MILLIS, EXPIRY_MILLIS, END_MILLIS), splitRequests);

        assertFalse(wholeRequests.isEmpty());
        assertEquals(wholeRequests, splitRequests);
        assertEquals(OpeningTask.Status.HOLDING, whole.openingTask().status());
        assertEquals(END_MILLIS - START_MILLIS, whole.openingTask().heldMillis());
        var normalizedSplit = BodyProtobuf.toMessage(split).toBuilder().setRevision(whole.revision()).build();
        assertEquals(BodyProtobuf.toMessage(whole), normalizedSplit);
        assertEquals(initial.revision() + 1, whole.revision());
    }

    private static BodySnapshot advanceRecordingPolicies(BodySnapshot initial, List<Long> boundaries,
                                                         List<OpeningPolicyRequest> requests) {
        // 保留不可变请求，验证后续推进不会修改先前回调看到的消息。
        SampleOpeningPolicy.observeRequests(request -> recordPolicyRequest(requests, request));
        try {
            BodySnapshot current = initial;
            for (long boundary : boundaries) {
                BodySnapshot advanced = BodyTimeAdvancement.apply(current, plan(boundary, ACTIVE)).body();
                current = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(advanced));
            }
            return current;
        } finally {
            SampleOpeningPolicy.stopObservingRequests();
        }
    }

    private static void recordPolicyRequest(List<OpeningPolicyRequest> requests, OpeningPolicyRequest request) {
        // 完整协议往返校验内部候选；分次事务的版本差异不影响策略业务事实。
        var body = request.getBody();
        BodySnapshot decoded = BodyProtobuf.fromMessage(body);
        assertEquals(body, BodyProtobuf.toMessage(decoded));
        var normalizedBody = body.toBuilder().setRevision(0).build();
        requests.add(request.toBuilder().setBody(normalizedBody).build());
    }

    @Test(timeout = TERMINATION_TIMEOUT_MILLIS)
    public void randomizedFullModifierBodiesAlwaysTerminateWithinTheirCurveBoundaries() {
        // 固定种子和位标记交叉覆盖冻结、开脉、寿尽以及三种时钟范围，失败可以精确重放。
        var random = new Random(TERMINATION_SEED);
        long startedNanos = System.nanoTime();
        int maximumIterations = 0;
        long maximumScenarioNanos = 0;
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            // 超时失败后仍允许测试进程退出，不让忽略中断的死循环占住 JVM。
            var worker = new Thread(task, "capability-termination");
            worker.setDaemon(true /* daemon */);
            return worker;
        });
        try {
            for (int index = 0; index < TERMINATION_SCENARIOS; index++) {
                TerminationScenario scenario = terminationScenario(random, index);
                long scenarioStartedNanos = System.nanoTime();
                int iterations = assertTimedTermination(executor, scenario, index);
                maximumIterations = Math.max(maximumIterations, iterations);
                maximumScenarioNanos = Math.max(maximumScenarioNanos, System.nanoTime() - scenarioStartedNanos);
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(CapabilityModifier.MAX_PER_BODY * CapabilityModifier.MAX_CURVE_POINTS, maximumIterations);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        long maximumScenarioMillis = TimeUnit.NANOSECONDS.toMillis(maximumScenarioNanos);
        System.out.printf("termination-stress seed=%d scenarios=%d maxIterations=%d elapsedMillis=%d "
                        + "maxScenarioMillis=%d%n", TERMINATION_SEED, TERMINATION_SCENARIOS, maximumIterations,
                elapsedMillis, maximumScenarioMillis);
    }

    private static int assertTimedTermination(ExecutorService executor, TerminationScenario scenario, int index) {
        // 整次推进和逐边界对照共用时限，任一内部循环停滞都会使该场景失败。
        Future<Integer> advancement = executor.submit(() -> assertTermination(scenario));
        try {
            return advancement.get(SCENARIO_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("终止性测试被中断：index=" + index, failure);
        } catch (ExecutionException | TimeoutException failure) {
            advancement.cancel(true /* mayInterruptIfRunning */);
            throw new AssertionError("终止性场景失败：seed=" + TERMINATION_SEED + ", index=" + index, failure);
        }
    }

    private static int assertTermination(TerminationScenario scenario) {
        // 先实际运行完整请求，不能仅由曲线数量推断它一定会结束。
        BodySnapshot initial = scenario.body();
        BodyTimeAdvancement.Plan requestedPlan = scenario.plan();
        TimeCursor start = initial.settledAt();
        BodyTimeAdvancement.Result whole = assertSingleAdvance(initial, requestedPlan);
        BodySnapshot settled = whole.body();

        // 独立枚举输入中的全部绝对拐点，逐段实推并计数，不向生产循环添加测试状态。
        BodySnapshot current = initial;
        QiInventory released = QiInventory.empty();
        int iterations = 0;
        for (long cutMillis : terminationBoundaries(scenario)) {
            var cut = new TimeCursor(start.domain(), start.epoch(), cutMillis);
            var plan = new BodyTimeAdvancement.Plan(cut, requestedPlan.biologicalSecondsPerSimulationSecond(),
                    requestedPlan.mode());
            BodyTimeAdvancement.Result interval = BodyTimeAdvancement.apply(current, plan);
            BodySnapshot advanced = interval.body();
            assertTrue("每段必须严格推进时钟", advanced.settledAt().elapsedMillis() > current.settledAt().elapsedMillis());
            iterations++;
            assertTrue("外层分段数超出曲线点数上界", iterations <= scenario.pointCount() + 1);
            released = QiAccounting.credit(released, interval.releasedQi());
            current = advanced;
            if (interval.died()) {
                break;
            }
        }

        // 分段请求各自递增 revision；其余全部身体状态与资源释放必须和一次结算相同。
        var normalized = BodyProtobuf.toMessage(current).toBuilder().setRevision(settled.revision()).build();
        assertEquals(BodyProtobuf.toMessage(settled), normalized);
        assertEquals(whole.releasedQi(), released);
        return iterations;
    }

    private static BodyTimeAdvancement.Result assertSingleAdvance(BodySnapshot initial,
                                                                  BodyTimeAdvancement.Plan plan) {
        // 满载身体的一次推进必须达到目标或在途中死亡，全部切段只增加一个版本。
        assertEquals(CapabilityModifier.MAX_PER_BODY, initial.modifiers().size());
        BodyTimeAdvancement.Result result = BodyTimeAdvancement.apply(initial, plan);
        BodySnapshot settled = result.body();
        long targetMillis = plan.target().elapsedMillis();
        long settledMillis = settled.settledAt().elapsedMillis();
        boolean died = result.died();
        assertTrue(settledMillis == targetMillis || settled.vitality().dead());
        assertEquals(plan.biologicalSecondsPerSimulationSecond() > 0, died);
        assertEquals(initial.revision() + 1, settled.revision());
        if (died) {
            assertTrue(settledMillis < targetMillis);
        }
        return result;
    }

    private static SortedSet<Long> terminationBoundaries(TerminationScenario scenario) {
        // 重合拐点只结算一次；当前时刻的点已经生效，目标时刻的点归最后一段。
        var boundaries = new TreeSet<Long>();
        BodySnapshot initial = scenario.body();
        long nowMillis = initial.settledAt().elapsedMillis();
        long targetMillis = scenario.plan().target().elapsedMillis();
        for (CapabilityModifier modifier : initial.modifiers()) {
            for (CapabilityModifier.CurvePoint point : modifier.curve().points()) {
                long boundaryMillis = modifier.curve().startMillis() + point.offsetMillis();
                if (boundaryMillis > nowMillis && boundaryMillis < targetMillis) {
                    boundaries.add(boundaryMillis);
                }
            }
        }
        boundaries.add(targetMillis);
        return boundaries;
    }

    private static TerminationScenario terminationScenario(Random random, int index) {
        // 首个场景将 32 × 16 个点交错排列，其余场景混合随机点数和重合边界。
        StressTimes times = terminationTimes(random, index);
        List<BodyMessages.CapabilityModifier> modifiers = IntStream.range(0, CapabilityModifier.MAX_PER_BODY)
                .mapToObj(modifierIndex -> terminationModifier(random, index, modifierIndex, times))
                .toList();
        BodySnapshot base = terminationBody(random, index, times);
        var message = BodyProtobuf.toMessage(base).toBuilder().addAllModifiers(modifiers).build();
        BodySnapshot modified = BodyProtobuf.fromMessage(message);
        BodySnapshot initial;
        if ((index & OPENING_SCENARIO) != 0) {
            initial = opening(modified);
            assertTrue(initial.openingTask().active());
        } else {
            initial = modified;
        }
        BodyTimeAdvancement.Mode mode = ACTIVE;
        if ((index & FROZEN_SCENARIO) != 0) {
            mode = LIFESPAN_ONLY;
        }
        double agingRate = 0;
        if ((index & DEATH_SCENARIO) != 0) {
            agingRate = 1;
        }
        var plan = new BodyTimeAdvancement.Plan(plan(times.targetMillis(), mode).target(), agingRate, mode);
        int pointCount = modifiers.stream().mapToInt(BodyMessages.CapabilityModifier::getCurveCount).sum();
        return new TerminationScenario(initial, plan, pointCount);
    }

    private static StressTimes terminationTimes(Random random, int index) {
        // 小跨度、大跨度和大绝对时钟分别覆盖时长、加法溢出与浮点时间精度边界。
        long jitterMillis;
        if ((index & DEATH_SCENARIO) != 0) {
            int jitterQuanta = STRESS_TARGET_JITTER_MILLIS / STRESS_LIFESPAN_QUANTUM_MILLIS;
            jitterMillis = random.nextInt(jitterQuanta) * STRESS_LIFESPAN_QUANTUM_MILLIS;
        } else {
            jitterMillis = random.nextInt(STRESS_TARGET_JITTER_MILLIS);
        }
        return switch (index % STRESS_CLOCK_RANGES) {
            case 0 -> new StressTimes(START_MILLIS, START_MILLIS + STRESS_TARGET_DELAY_MILLIS);
            case 1 -> new StressTimes(START_MILLIS, Long.MAX_VALUE - jitterMillis);
            default -> new StressTimes(Long.MAX_VALUE - STRESS_CLOCK_MARGIN_MILLIS, Long.MAX_VALUE - jitterMillis);
        };
    }

    private static BodySnapshot terminationBody(Random random, int index, StressTimes times) {
        // 保存中的曲线可早于当前游标；组织带疲劳，使 ACTIVE 场景实际执行恢复积分。
        var original = BodyProtobuf.toMessage(recoveringBody());
        var vitality = original.getVitality().toBuilder();
        if ((index & DEATH_SCENARIO) != 0) {
            // 八分之一秒可以精确表示，寿尽样本不混入已有的浮点寿命数值拒绝。
            long lifespanMillis = (1 + random.nextInt(STRESS_LIFESPAN_QUANTA)) * STRESS_LIFESPAN_QUANTUM_MILLIS;
            vitality.setLifespanLimitSeconds(lifespanMillis / MILLIS_PER_SECOND);
        }
        var message = original.toBuilder()
                .setSettledAt(BodyProtobuf.toTime(plan(times.nowMillis(), ACTIVE).target()))
                .setVitality(vitality)
                .build();
        return BodyProtobuf.fromMessage(message);
    }

    private static BodyMessages.CapabilityModifier terminationModifier(Random random, int scenarioIndex,
                                                                       int modifierIndex, StressTimes times) {
        // 温和功率与耐力峰值保证开脉能启动；恢复峰值独立随机，仍会实际测试完整乘积积分。
        StressCurve curve = terminationCurve(random, scenarioIndex, modifierIndex, times);
        List<BodyMessages.CurvePoint> points = terminationPoints(random, curve, modifierIndex);
        return modifier("sample:termination/" + modifierIndex, END_MILLIS).toBuilder()
                .setStartMillis(curve.startMillis())
                .clearCurve()
                .addAllCurve(points)
                .setStrengthPeak(1 + random.nextDouble(-STRESS_PEAK_VARIATION, STRESS_PEAK_VARIATION))
                .setBurstPeak(1 + random.nextDouble(-STRESS_PEAK_VARIATION, STRESS_PEAK_VARIATION))
                .setEndurancePeak(1 + random.nextDouble(-STRESS_PEAK_VARIATION, STRESS_PEAK_VARIATION))
                .setRecoveryPeak(1 + random.nextDouble(-HALF, HALF))
                .build();
    }

    private static StressCurve terminationCurve(Random random, int scenarioIndex, int modifierIndex,
                                                StressTimes times) {
        // 最大点数场景没有重合时刻，迫使完整请求经过接近理论上限的外层分段。
        if (scenarioIndex == 0) {
            return new StressCurve(times.nowMillis() + modifierIndex, CapabilityModifier.MAX_PER_BODY,
                    CapabilityModifier.MAX_CURVE_POINTS);
        }
        if (modifierIndex == 0) {
            // 常值曲线在目标时刻到期；极大目标也保留合法的短时曲线，不突破时长上限。
            return new StressCurve(times.targetMillis() - STRESS_TARGET_JITTER_MILLIS,
                    STRESS_TARGET_JITTER_MILLIS, CapabilityModifier.MIN_CURVE_POINTS);
        }
        if (modifierIndex == 1) {
            // 已开始曲线的内部拐点恰在当前时刻，不能再次停留于这个边界。
            long spacingMillis = terminationSpacing(scenarioIndex);
            return new StressCurve(times.nowMillis() - spacingMillis,
                    spacingMillis, CapabilityModifier.MAX_CURVE_POINTS);
        }
        int pointVariants = CapabilityModifier.MAX_CURVE_POINTS - CapabilityModifier.MIN_CURVE_POINTS + 1;
        int pointCount = CapabilityModifier.MIN_CURVE_POINTS + random.nextInt(pointVariants);
        if (modifierIndex == 2 || modifierIndex == 3) {
            // 两条曲线共享起点和拐点，其中常值曲线在起点产生阶跃。
            return new StressCurve(times.nowMillis(), terminationSpacing(scenarioIndex), pointCount);
        }
        return randomTerminationCurve(random, scenarioIndex, pointCount, times);
    }

    private static StressCurve randomTerminationCurve(Random random, int scenarioIndex, int pointCount,
                                                       StressTimes times) {
        // 寿尽样本的区间也对齐八分之一秒，保证每段累计年龄都能精确表示。
        if ((scenarioIndex & DEATH_SCENARIO) != 0) {
            long delayMillis = random.nextInt(SHARED_START_VARIANTS) * STRESS_LIFESPAN_QUANTUM_MILLIS;
            return new StressCurve(times.nowMillis() + delayMillis, STRESS_LIFESPAN_QUANTUM_MILLIS, pointCount);
        }
        // 保留一个边界不对齐的开脉场景，其余活动开脉聚合边界，覆盖同时到达而避免重复预算计算。
        boolean activeOpening = (scenarioIndex & OPENING_SCENARIO) != 0 && (scenarioIndex & FROZEN_SCENARIO) == 0;
        if (activeOpening && scenarioIndex != IRREGULAR_OPENING_SCENARIO) {
            long sharedDelayMillis = random.nextInt(SHARED_START_VARIANTS) * STRESS_POINT_SPACING_MILLIS;
            return new StressCurve(times.nowMillis() + sharedDelayMillis, STRESS_POINT_SPACING_MILLIS, pointCount);
        }
        long startDelayMillis = random.nextInt(CapabilityModifier.MAX_PER_BODY) * STRESS_POINT_SPACING_MILLIS;
        long spacingMillis = 1 + random.nextInt(STRESS_POINT_SPACING_MILLIS);
        return new StressCurve(times.nowMillis() + startDelayMillis, spacingMillis, pointCount);
    }

    private static long terminationSpacing(int scenarioIndex) {
        // 寿尽分段使用精确的生物秒步长，其他样本继续保留更密集的曲线边界。
        if ((scenarioIndex & DEATH_SCENARIO) != 0) {
            return STRESS_LIFESPAN_QUANTUM_MILLIS;
        }
        return STRESS_POINT_SPACING_MILLIS;
    }

    private static List<BodyMessages.CurvePoint> terminationPoints(Random random, StressCurve curve,
                                                                  int modifierIndex) {
        // 常值与正负振幅曲线交替出现，首尾非零使生效、到期阶跃走相同推进路径。
        var points = new ArrayList<BodyMessages.CurvePoint>();
        for (int index = 0; index < curve.pointCount(); index++) {
            double amplitude = 1;
            if (modifierIndex % 2 != 0) {
                amplitude = random.nextDouble(-1, 1);
            }
            points.add(point(index * curve.spacingMillis(), amplitude));
        }
        return points;
    }

    private record StressTimes(long nowMillis, long targetMillis) {}

    private record StressCurve(long startMillis, long spacingMillis, int pointCount) {}

    private record TerminationScenario(BodySnapshot body, BodyTimeAdvancement.Plan plan, int pointCount) {}

    private static void assertRecoveryAcrossPartitions(List<BodyMessages.CapabilityModifier> modifiers,
                                                       double effectiveSeconds) {
        // 用解析值和不对齐曲线拐点的多次推进同时验证积分，避免只复刻内部算法。
        BodySnapshot initial = add(recoveringBody(), modifiers);
        BodySnapshot whole = BodyTimeAdvancement.apply(initial, plan(END_MILLIS, ACTIVE)).body();
        BodySnapshot split = initial;
        long[] cuts = {1_537, 2_000, 2_319, 3_000, 3_887, 4_000, 4_701, END_MILLIS};
        for (long cut : cuts) {
            split = BodyTimeAdvancement.apply(split, plan(cut, ACTIVE)).body();
        }
        double expected = INITIAL_FATIGUE * StrictMath.exp(-StrictMath.log(2)
                * effectiveSeconds / RECOVERY_HALF_LIFE_SECONDS);
        assertEquals(expected, whole.tissueStates().get(0).fatigue(), TOLERANCE);
        assertEquals(whole.tissueStates().get(0).fatigue(), split.tissueStates().get(0).fatigue(), TOLERANCE);
        double baseSeconds = (END_MILLIS - START_MILLIS) / MILLIS_PER_SECOND;
        double expectedOther = INITIAL_FATIGUE * StrictMath.exp(-StrictMath.log(2)
                * baseSeconds / RECOVERY_HALF_LIFE_SECONDS);
        assertEquals(expectedOther, whole.tissueStates().get(1).fatigue(), TOLERANCE);
        assertTrue(whole.modifiers().isEmpty());
    }

    private static void assertForceRejected(BodySnapshot body, double force, double seconds) {
        // 保持能量和功率足够小，单独观察区间最低力量的拒绝规则。
        var load = new TissueCapabilities.Exertion(TISSUE, force, 1, seconds);
        var rejected = assertThrows(MeridianErrors.CapabilityRejected.class,
                () -> TissueCapabilities.exert(body, load));
        assertEquals(MeridianErrors.CapabilityRejectionReason.TISSUE_LOAD_EXCEEDED, rejected.reason());
    }

    private static BodySnapshot at(BodySnapshot body, long millis) {
        // 冻结疲劳只推进曲线时钟，派生能力断言不会混入自然恢复。
        return BodyTimeAdvancement.apply(body, plan(millis, LIFESPAN_ONLY)).body();
    }

    private static BodyMessages.CurvePoint point(long offsetMillis, double value) {
        // 测试输入也使用生成消息，以覆盖负曲线值的线协议校验。
        return BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(offsetMillis)
                .setValue(value)
                .build();
    }

    private static BodyMessages.CapabilityModifier constant(String id, long start, long end, double peak) {
        // 固定能力与固定恢复倍率共用两点常值曲线，不另设模式。
        return modifier(id, END_MILLIS).toBuilder()
                .setStartMillis(start)
                .clearCurve()
                .addCurve(point(0, 1))
                .addCurve(point(end - start, 1))
                .setStrengthPeak(peak)
                .setBurstPeak(peak)
                .setEndurancePeak(peak)
                .setRecoveryPeak(peak)
                .build();
    }

    private static BodyMessages.CapabilityModifier recoveryCurve(String id, double peak,
                                                                 List<BodyMessages.CurvePoint> points) {
        // 只修改恢复维度，让积分断言不受做功能力影响。
        return modifier(id, END_MILLIS).toBuilder()
                .clearCurve()
                .addAllCurve(points)
                .setStrengthPeak(1)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(peak)
                .build();
    }

    static BodySnapshot recoveringBody() {
        // 两块同参数组织便于同时验证目标倍率和组织隔离。
        var message = BodyProtobuf.toMessage(body());
        var structure = message.getStructure().toBuilder();
        var candidate = message.toBuilder();
        for (int index = 0; index < message.getTissueStatesCount(); index++) {
            var definition = structure.getTissues(index).toBuilder()
                    .setRecoveryHalfLifeSeconds(RECOVERY_HALF_LIFE_SECONDS)
                    .build();
            var state = message.getTissueStates(index).toBuilder().setFatigue(INITIAL_FATIGUE).build();
            structure.setTissues(index, definition);
            candidate.setTissueStates(index, state);
        }
        return BodyProtobuf.fromMessage(candidate.setStructure(structure).build());
    }

    private static BodySnapshot body() {
        // 从现有非人构型创建短时钟健康身体，第二块组织用于检查修正隔离。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var other = original.getStructure().getTissues(0).toBuilder()
                .setId(OTHER_TISSUE)
                .clearPorts()
                .build();
        var structure = original.getStructure().toBuilder().addTissues(other).build();
        var part = original.getPartStates(0).toBuilder().setHp(STRENGTH_NEWTONS).build();
        var tissue = original.getTissueStates(0).toBuilder()
                .setHp(other.getMaxHp())
                .setFatigue(0)
                .build();
        var otherState = tissue.toBuilder().setId(OTHER_TISSUE).build();
        var vitality = original.getVitality().toBuilder()
                .setAgeSeconds(0)
                .setSpentLifespanSeconds(0)
                .build();
        var message = original.toBuilder()
                .setStructure(structure)
                .setPartStates(0, part)
                .setTissueStates(0, tissue)
                .addTissueStates(otherState)
                .setVitality(vitality)
                .setSettledAt(BodyProtobuf.toTime(plan(START_MILLIS, ACTIVE).target()))
                .setRevision(0)
                .clearTransits()
                .build();
        return BodyProtobuf.fromMessage(message);
    }

    private static BodyMessages.CapabilityModifier modifier(String id, long expiresMillis) {
        // 固定倍率用常值曲线表达，单个回归可改写曲线或任一峰值。
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId(id)
                .setSource(SOURCE)
                .setTissueId(TISSUE.value())
                .setStartMillis(START_MILLIS)
                .addCurve(point(0, 1))
                .addCurve(point(expiresMillis - START_MILLIS, 1))
                .setStrengthPeak(BOOST)
                .setBurstPeak(BOOST)
                .setEndurancePeak(BOOST)
                .setRecoveryPeak(1)
                .build();
    }

    static BodySnapshot add(BodySnapshot body, List<BodyMessages.CapabilityModifier> modifiers) {
        // 通过活动算子添加，避免测试直接构造一条生产入口无法生成的修正状态。
        var activity = BodyActivity.newBuilder().addAllAddModifiers(modifiers).build();
        return BodyActivityExecution.apply(body, activity, SOURCE);
    }

    private static void assertCapacity(BodySnapshot body, double strength, double burst, double endurance) {
        // 对公开派生能力断言三个维度，不从实现读取中间乘积。
        Anatomy.TissueCapacity capacity = TissueCapabilities.available(body, TISSUE);
        assertEquals(STRENGTH_NEWTONS * strength, capacity.strengthNewtons(), TOLERANCE);
        assertEquals(BURST_WATTS * burst, capacity.burstWatts(), TOLERANCE);
        assertEquals(ENDURANCE_JOULES * endurance, capacity.enduranceJoules(), TOLERANCE);
    }

    private static void assertRejected(BodySnapshot body, BodyActivity plan) {
        // 任意非法条目使事务回调失败，身体、环境与成功回执均保持原样。
        var store = new InMemoryBodyStore(List.of(body), List.of());
        assertThrows(IllegalArgumentException.class, () -> store.transact(body.id(), transaction -> {
            BodyActivityExecution.apply(transaction.snapshot().orElseThrow(), plan, SOURCE);
            throw new AssertionError("非法活动被接受");
        }));
        assertEquals(body, store.find(body.id()).orElseThrow());
        assertTrue(store.receipts().isEmpty());
        assertEquals(QiInventory.empty(), store.environmentQi());
    }

    static BodySnapshot opening(BodySnapshot body) {
        // 非人体测试策略只维持一块组织的负载，排除资源流转对到期扣费断言的干扰。
        var unopened = BodyMessages.MeridianOpening.newBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.UNOPENED)
                .setProgress(0)
                .build();
        var original = BodyProtobuf.toMessage(body);
        var meridian = original.getMeridianStates(0).toBuilder()
                .setCondition(BodyMessages.MeridianCondition.INTACT)
                .setOpening(unopened)
                .build();
        BodySnapshot available = BodyProtobuf.fromMessage(original.toBuilder().setMeridianStates(0, meridian).build());
        var rule = openingRule();
        return OpeningRuntime.start(available, OpeningProtobuf.fromRule(rule),
                ActionProtobuf.fromDefinition(action()));
    }

    static BodySnapshot holdingOpening(BodySnapshot body) {
        // 目标内腔先填满，使策略始终进入维持阶段，推进时实际更新适应进度。
        var original = BodyProtobuf.toMessage(body);
        double lumenVolume = original.getStructure().getMeridians(0).getLumenVolume();
        var qi = BodyMessages.Qi.newBuilder()
                .setAttribute("sample:opening_fuel")
                .setAmount(lumenVolume)
                .setDensity(1)
                .build();
        var inventory = BodyMessages.QiInventory.newBuilder().addEntries(qi).build();
        var meridian = original.getMeridianStates(0).toBuilder().setLumenQi(inventory).build();
        var message = original.toBuilder().setMeridianStates(0, meridian).build();
        return opening(BodyProtobuf.fromMessage(message));
    }

    private static BodyMessages.MeridianOpeningRule openingRule() {
        // 长时非人体规则固定维持功率，让测试观察疲劳阈值及修正到期。
        BodyMessages.ActionDefinition action = action();
        var conditions = SampleOpeningConditions.newBuilder()
                .setDurationMillis(OPENING_DURATION_MILLIS)
                .setStrict(false /* strict */)
                .setRequiresIntake(false /* requiresIntake */)
                .build();
        var cost = BodyMessages.OpeningEffort.newBuilder()
                .setTissueId(TISSUE.value())
                .setWatts(CONTINUOUS_WATTS)
                .build();
        return BodyMessages.MeridianOpeningRule.newBuilder()
                .setId("sample:opening_test")
                .setVersion(1)
                .setDisplayName("持续组织负载")
                .setTargetMeridianId("channel")
                .setActionId(action.getId())
                .setActionVersion(1)
                .setPolicyId("sample:opening")
                .setPolicyConfig(Any.pack(conditions))
                .addEfforts(cost)
                .build();
    }

    private static BodyMessages.ActionDefinition action() {
        // 动作只声明目标组织占用，持续费用由开脉负载提供。
        return BodyMessages.ActionDefinition.newBuilder()
                .setId("sample:focus")
                .setVersion(1)
                .setDisplayName("专注")
                .addTissueIds(TISSUE.value())
                .build();
    }

    private static ActivityEffort effort(String tissue, double watts) {
        // 瞬时活动负载不推进身体时钟。
        return ActivityEffort.newBuilder()
                .setTissueId(tissue)
                .setWatts(watts)
                .setSeconds(1)
                .build();
    }

    private static BodyTimeAdvancement.Plan plan(long millis, BodyTimeAdvancement.Mode mode) {
        // 固定时钟域和零老化，寿尽测试单独给出老化倍率。
        var time = new TimeCursor(new DefinitionId("sample:world"), "epoch-1", millis);
        return new BodyTimeAdvancement.Plan(time, 0, mode);
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store) {
        // 瞬时命令的可信时刻与已保存游标一致，真实 Store 仍会复算每笔候选。
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true,
                (caller, body) -> TimeProtobuf.toMessage(plan(body.settledAt().elapsedMillis(), ACTIVE)), store,
                (caller, receipt, before, after) -> { /* 结果由权威 Store 读取。 */ });
    }

    private static MeridianMessages.BodyCommand.Builder command(BodySnapshot body) {
        // 每笔新命令引用当前版本，重试则复用原始字节。
        return MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision());
    }

    private static MeridianMessages.RequestEnvelope.Builder envelope(String requestId) {
        // 公共请求统一使用当前线协议版本。
        return MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId);
    }

    private static byte[] packet(String id, MeridianMessages.BodyCommand command) {
        // 使用真实编解码校验，测试不绕过请求的格式边界。
        return MeridianProtocol.encodeRequest(envelope(id).setBodyCommand(command).build());
    }

    private static void assertCommitted(byte[] response) {
        // 仅收到字节不足以证明提交，必须确认成功响应。
        var message = MeridianProtocol.decodeResponse(response);
        assertTrue(message.toString(), message.hasCommandCommitted());
    }
}
