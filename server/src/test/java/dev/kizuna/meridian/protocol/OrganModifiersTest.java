package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.core.OrganCapabilities;
import dev.kizuna.meridian.core.OrganIntake;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.ActivityHostMessages.ActivityHostRequest;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyActivityExecution;
import org.junit.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

/** 非人体构型覆盖器官曲线、旧存档兼容、统一上限和可信活动边界。 */
public final class OrganModifiersTest {
    private static final String SOURCE = "sample:organ_effect";
    private static final Anatomy.OrganId ORGAN = new Anatomy.OrganId("filter");
    private static final long SECOND = 1_000;
    private static final long END = 3_000;
    private static final double BOOST = 2;
    private static final double HALF = 0.5;
    private static final double DENSITY = 0.01;
    private static final double TOLERANCE = 1e-9;

    @Test
    public void organCurvesRoundTripAndExpireWithoutTissueTargets() {
        // 仅有器官修正的身体也必须按曲线边界推进，旧 optional 字段缺省保持单位倍率。
        BodySnapshot original = body();
        var modifier = modifier("sample:lung");
        BodySnapshot active = install(original, List.of(modifier));
        assertEquals(active, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(active)));
        assertEquals(BOOST, OrganCapabilities.intakePerSecond(active, ORGAN), TOLERANCE);
        double throughput = original.structure().organs().getFirst().throughputPerSecond();
        assertEquals(throughput * HALF, OrganCapabilities.throughputPerSecond(active, ORGAN), TOLERANCE);
        BodySnapshot expired = BodyTimeAdvancement.apply(active, plan(active, END)).body();
        assertTrue(expired.modifiers().isEmpty());
        assertEquals(1, OrganCapabilities.intakePerSecond(expired, ORGAN), TOLERANCE);
        var legacy = modifier.toBuilder()
                .clearIntakePeak()
                .clearThroughputPeak()
                .build();
        assertEquals(1, CapabilityProtobuf.fromModifier(legacy).peaks().organ().intake(), 0);
    }

    @Test
    public void strongestIntakeMultiplierFollowsTheActiveCurve() {
        // 吐纳窗口显示的倍率随修正生效和到期变化，没有修正时是一。
        BodySnapshot original = body();
        assertEquals(1, OrganCapabilities.strongestIntakeMultiplier(original, List.of(ORGAN)), 0);
        BodySnapshot active = install(original, List.of(modifier("sample:lung")));
        assertEquals(BOOST, OrganCapabilities.strongestIntakeMultiplier(active, List.of(ORGAN)), TOLERANCE);
        BodySnapshot expired = BodyTimeAdvancement.apply(active, plan(active, END)).body();
        assertEquals(1, OrganCapabilities.strongestIntakeMultiplier(expired, List.of(ORGAN)), TOLERANCE);
        assertEquals(1, OrganCapabilities.strongestIntakeMultiplier(active, List.of()), 0);
    }

    @Test
    public void intakeIntegratesRisingAndNegativeCurveSegments() {
        // 一秒上升、随后虚脱下降的面积控制实际摄入；超过区间面积整笔拒绝。
        var curve = modifier("sample:ramp").toBuilder()
                .clearCurve()
                .addCurve(point(0, 0))
                .addCurve(point(SECOND, 1))
                .addCurve(point(SECOND * 2, -HALF))
                .addCurve(point(END, 0))
                .build();
        BodySnapshot active = install(body(), List.of(curve));
        double integral = 1 + HALF;
        var accepted = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, integral, DENSITY)));
        var result = OrganIntake.apply(active, ORGAN, accepted, plan(active, SECOND));
        assertEquals(integral, result.body().organStates().getFirst().buffer().totalQi(), TOLERANCE);
        var excess = new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, BOOST, DENSITY)));
        assertThrows(MeridianErrors.IntakeRejected.class,
                () -> OrganIntake.apply(active, ORGAN, excess, plan(active, SECOND)));
        BodySnapshot exhausted = BodyTimeAdvancement.apply(active, plan(active, SECOND * 2)).body();
        assertEquals(HALF, OrganCapabilities.intakePerSecond(exhausted, ORGAN), TOLERANCE);
    }

    @Test
    public void invalidTargetsPeaksAndMixedLimitCannotPartiallyApply() {
        // 器官不能携带组织峰值，组织不能携带器官峰值，两类共用三十二条上限。
        BodySnapshot original = body();
        var invalidTarget = modifier("sample:missing").toBuilder().setOrganId("missing").build();
        assertThrows(IllegalArgumentException.class, () -> install(original, List.of(invalidTarget)));
        var wrongPeak = modifier("sample:wrong").toBuilder().setTempoPeak(HALF).build();
        assertThrows(IllegalArgumentException.class, () -> install(original, List.of(wrongPeak)));
        var mixed = IntStream.range(0, CapabilityModifier.MAX_PER_BODY).mapToObj(index -> {
            var entry = modifier("sample:entry_" + index).toBuilder();
            if (index % 2 == 0) {
                entry.setTissueId("tendril")
                        .clearIntakePeak()
                        .clearThroughputPeak();
            }
            return entry.build();
        }).toList();
        BodySnapshot full = install(original, mixed);
        assertEquals(CapabilityModifier.MAX_PER_BODY, full.modifiers().size());
        assertThrows(IllegalArgumentException.class, () -> install(full, List.of(modifier("sample:extra"))));
        assertTrue(original.modifiers().isEmpty());
        assertEquals(CapabilityModifier.MAX_PER_BODY, full.modifiers().size());
    }

    @Test
    public void trustedExchangeRestrictsOperationAndSource() {
        // 外层只能封装同身份活动；撤别的来源和同笔非法目标都不改变原身体。
        BodySnapshot original = install(body(), List.of(modifier("sample:old")));
        var plan = BodyActivity.newBuilder().addRemoveModifiers("sample:old").build();
        assertThrows(MeridianErrors.IntakeRejected.class,
                () -> BodyActivityExecution.apply(original, plan, "other:addon"));
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(original.id().value())
                .setExpectedRevision(original.revision())
                .setExecuteActivity(plan)
                .build();
        var envelope = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId("activity")
                .setBodyCommand(command)
                .build();
        var request = ActivityHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId("activity")
                .setSubjectId("subject")
                .setActivity(envelope)
                .build();
        assertEquals(request, ActivityHostProtobuf.decodeRequest(ActivityHostProtobuf.encodeRequest(request)));
        assertThrows(IllegalArgumentException.class,
                () -> ActivityHostProtobuf.encodeRequest(request.toBuilder().setRequestId("other").build()));
        assertTrue(BodyActivityExecution.apply(original, plan, SOURCE).modifiers().isEmpty());
        assertEquals(1, original.modifiers().size());
    }

    @Test
    public void workBudgetScalesRemainingEnduranceByHealthAndMatchesActivityCheck() {
        // 器官半血、已疲劳 20 J：可做功 (100 − 20) × 0.5 = 40 J，功率上限 10 × 40 / 100 = 4 W。
        var original = BodyProtobuf.toMessage(body());
        var endurance = BodyMessages.OrganEndurance.newBuilder()
                .setEnduranceJoules(100).setMaximumWatts(10).setRecoveryHalfLifeSeconds(20);
        var organ = original.getStructure().getOrgans(0).toBuilder().setEndurance(endurance).build();
        var state = original.getOrganStates(0).toBuilder().setFatigueJoules(20).setHp(organ.getMaxHp() / 2).build();
        BodySnapshot tired = BodyProtobuf.fromMessage(original.toBuilder()
                .setStructure(original.getStructure().toBuilder().setOrgans(0, organ))
                .setOrganStates(0, state).build());
        OrganCapabilities.WorkBudget budget = OrganCapabilities.workBudget(tired, ORGAN);
        assertEquals(0.5, budget.health(), TOLERANCE);
        assertEquals(40, budget.workJoules(), TOLERANCE);
        assertEquals(4, budget.powerWatts(), TOLERANCE);
        assertTrue(budget.allows(4, 10));
        assertFalse(budget.allows(4.1, 1));
        assertFalse(budget.allows(4, 10.1));
        assertFalse(OrganCapabilities.workBudget(body(), ORGAN).allows(1, 1));
    }

    private static BodySnapshot body() {
        // 缩短样例时钟并启用摄入，器官参数仍属于非人体内容。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var intake = BodyMessages.OrganIntakeCapability.newBuilder().setMaxQiPerSecond(1).build();
        var organ = original.getStructure().getOrgans(0).toBuilder().setIntake(intake).build();
        var structure = original.getStructure().toBuilder().setOrgans(0, organ).build();
        var time = original.getSettledAt().toBuilder().setElapsedMillis(0).build();
        return BodyProtobuf.fromMessage(original.toBuilder()
                .setStructure(structure)
                .setSettledAt(time)
                .setRevision(0)
                .clearTransits()
                .build());
    }

    private static BodyMessages.CapabilityModifier modifier(String id) {
        // 非目标维度显式为一，器官摄入和吞吐可独立调整。
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId(id)
                .setSource(SOURCE)
                .setOrganId(ORGAN.value())
                .setStartMillis(0)
                .setStrengthPeak(1)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .setIntakePeak(BOOST)
                .setThroughputPeak(HALF)
                .addCurve(point(0, 1))
                .addCurve(point(END, 1))
                .build();
    }

    private static BodyMessages.CurvePoint point(long offset, double value) {
        // 固定曲线点供各测试声明边界。
        return BodyMessages.CurvePoint.newBuilder()
                .setOffsetMillis(offset)
                .setValue(value)
                .build();
    }

    private static BodySnapshot install(BodySnapshot body, List<BodyMessages.CapabilityModifier> modifiers) {
        // 走真实原子活动而不是直接塞进快照，以覆盖来源和总量校验。
        var activity = BodyActivity.newBuilder().addAllAddModifiers(modifiers).build();
        return BodyActivityExecution.apply(body, activity, SOURCE);
    }

    private static BodyTimeAdvancement.Plan plan(BodySnapshot body, long millis) {
        // 与实际身体保持同域同世代，只推进模拟时间。
        var target = new TimeCursor(body.settledAt().domain(), body.settledAt().epoch(), millis);
        return new BodyTimeAdvancement.Plan(target, 1, BodyTimeAdvancement.Mode.ACTIVE);
    }
}
