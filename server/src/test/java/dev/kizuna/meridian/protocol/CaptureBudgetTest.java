package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.server.BodyTimeSource;
import dev.kizuna.meridian.server.BreathingModifiers;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.server.OrganRecipeCatalog;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 接触摄入的每秒额度属于身体存档，不属于某次吐纳会话。 */
public class CaptureBudgetTest {
    private static final String SOURCE = "sample:capture_budget";
    private static final String ORGAN = "filter";
    private static final String RECIPE = "sample:contact";
    private static final long SECOND = 1_000;
    private static final long SOURCE_EXPIRY = 10_000;
    private static final double SOURCE_VOLUME = 100;
    private static final double SOURCE_QI = 1_000;
    private static final double REFINED_FRACTION = 0.2;
    private static final double DENSITY = 0.01;
    private static final double EPSILON = 1e-9;
    private static final double NORMAL_DOSE = 0.2;
    private static final double SMALL_SAMPLE = NORMAL_DOSE / (SOURCE_QI / SOURCE_VOLUME);
    private static final double INTAKE_BOOST = 1.5;
    private static final double RESPIRATORY_DISCOUNT = 0.5;
    private static final double EXHAUSTED_INTAKE = 0.8;

    @Test
    public void contactSamplingAndPerOrganLimitBothComposeWithOrganCurves() {
        // 小采样量由浓度限制，大采样量用完器官当秒限额；聚气和肺虚各验一次。
        for (double medicine : List.of(1.0, INTAKE_BOOST, EXHAUSTED_INTAKE)) {
            for (double technique : List.of(1.0, INTAKE_BOOST)) {
                var modifiers = new BreathingModifiers(technique, RESPIRATORY_DISCOUNT);
                var initial = initial().toBuilder()
                        .addModifiers(organModifier(medicine))
                        .putAddonProgress(BreathingModifiers.KEY, modifiers.pack())
                        .build();
                double effective = medicine * technique;
                assertEquals(NORMAL_DOSE * effective, absorbed(initial, SMALL_SAMPLE), EPSILON);
                assertEquals(effective, absorbed(initial, 1), EPSILON);
            }
        }
    }

    private static BodyMessages.CapabilityModifier organModifier(double medicine) {
        // 用聚气汤同种器官曲线隔离两条收益来源。
        var start = BodyMessages.CurvePoint.newBuilder().setValue(1).build();
        var end = start.toBuilder().setOffsetMillis(SECOND).build();
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId("sample:medicine")
                .setSource(SOURCE)
                .setOrganId(ORGAN)
                .setStrengthPeak(1)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .setIntakePeak(medicine)
                .addCurve(start)
                .addCurve(end)
                .build();
    }

    @Test
    public void eitherSourceCanStartOrEndWhileTheOtherRemains() {
        // 用真实身体活动分别挂、撤运功和药力。每个边界都用固定小粒子测原料摄入。
        var normal = BodyProtobuf.fromMessage(initial());
        var factors = new BreathingModifiers(INTAKE_BOOST, RESPIRATORY_DISCOUNT);
        var begin = BodyActivity.newBuilder().putProgress(BreathingModifiers.KEY, factors.pack()).build();
        var end = BodyActivity.newBuilder()
                .putProgress(BreathingModifiers.KEY, BreathingModifiers.NORMAL.pack())
                .build();
        for (double peak : List.of(INTAKE_BOOST, EXHAUSTED_INTAKE)) {
            var curve = organModifier(peak);
            var drink = BodyActivity.newBuilder().addAddModifiers(curve).build();
            var clear = BodyActivity.newBuilder().addRemoveModifiers(curve.getId()).build();
            var medicine = BodyActivityExecution.apply(normal, drink, SOURCE);
            var practice = BodyActivityExecution.apply(normal, begin, SOURCE);
            var fromMedicine = BodyActivityExecution.apply(medicine, begin, SOURCE);
            var fromPractice = BodyActivityExecution.apply(practice, drink, SOURCE);
            for (var both : List.of(fromMedicine, fromPractice)) {
                assertEquals(NORMAL_DOSE * INTAKE_BOOST * peak, absorbed(BodyProtobuf.toMessage(both), SMALL_SAMPLE),
                        EPSILON);
                var onlyMedicine = BodyActivityExecution.apply(both, end, SOURCE);
                var onlyPractice = BodyActivityExecution.apply(both, clear, SOURCE);
                assertEquals(NORMAL_DOSE * peak, absorbed(BodyProtobuf.toMessage(onlyMedicine), SMALL_SAMPLE), EPSILON);
                assertEquals(NORMAL_DOSE * INTAKE_BOOST, absorbed(BodyProtobuf.toMessage(onlyPractice), SMALL_SAMPLE),
                        EPSILON);
            }
        }
    }

    private static double absorbed(BodyMessages.BodySnapshot initial, double sample) {
        // 从真实环境扣账、器官得到产物后反推原料量。
        var body = BodyProtobuf.fromMessage(initial);
        var environment = source(initial.getSettledAt());
        var store = new InMemoryBodyStore(List.of(body), List.of(), QiInventory.empty(), List.of(environment));
        var handler = handler(store, initial, new AtomicLong());
        var reply = capture(handler, store, body.id(), "contact", sample);
        assertTrue(reply.toString(), reply.hasCommandCommitted());
        var after = store.find(body.id()).orElseThrow();
        return after.organStates().getFirst().buffer().totalQi() / REFINED_FRACTION;
    }

    @Test
    public void togglingInOneSecondDoesNotRestoreSpentRawAllowance() {
        // 聚气汤单独耗尽额度，起式只补两项乘积的差额。
        // 收法和重新起式都不能重复领取。
        var initial = initial().toBuilder().addModifiers(organModifier(INTAKE_BOOST)).build();
        var body = BodyProtobuf.fromMessage(initial);
        var source = source(initial.getSettledAt());
        var store = new InMemoryBodyStore(List.of(body), List.of(), QiInventory.empty(), List.of(source));
        var handler = handler(store, initial, new AtomicLong());
        assertTrue(capture(handler, store, body.id(), "medicine").hasCommandCommitted());
        var improved = new BreathingModifiers(INTAKE_BOOST, RESPIRATORY_DISCOUNT);
        setFactors(handler, store, body.id(), improved);
        assertTrue(capture(handler, store, body.id(), "both").hasCommandCommitted());
        var full = store.find(body.id()).orElseThrow();
        assertEquals(REFINED_FRACTION * INTAKE_BOOST * INTAKE_BOOST,
                full.organStates().getFirst().buffer().totalQi(), EPSILON);
        setFactors(handler, store, body.id(), BreathingModifiers.NORMAL);
        var normal = capture(handler, store, body.id(), "after-end");
        assertEquals(MeridianMessages.FailureCode.CAPACITY_EXCEEDED, normal.getFailure().getCode());
        setFactors(handler, store, body.id(), improved);
        var again = capture(handler, store, body.id(), "after-restart");
        assertEquals(MeridianMessages.FailureCode.CAPACITY_EXCEEDED, again.getFailure().getCode());
    }

    private static void setFactors(BodyCommandHandler<String> handler, InMemoryBodyStore store,
                                   BodyId id, BreathingModifiers factors) {
        // 同一权威身体上只切换呼吸效果，保留药力和每侧器官已使用的秒额度。
        var progress = MeridianMessages.SetAddonProgress.newBuilder()
                .setId(BreathingModifiers.KEY)
                .setProgress(factors.pack())
                .build();
        long revision = store.find(id).orElseThrow().revision();
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(revision)
                .setSetAddonProgress(progress)
                .build();
        var request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("factors-" + revision)
                .setBodyCommand(command)
                .build();
        var reply = MeridianProtocol.decodeResponse(handler.handleAddonProgress("owner", request.toByteArray()));
        assertTrue(reply.toString(), reply.hasCommandCommitted());
    }

    @Test
    public void limitPersistsAcrossReloadAndRefreshesInNextSecond() {
        // 每个窗口内所有请求共用限额；存档往返后不能再领，下一秒才恢复。
        BodyMessages.BodySnapshot initial = initial();
        var body = BodyProtobuf.fromMessage(initial);
        var source = source(initial.getSettledAt());
        var store = new InMemoryBodyStore(List.of(body), List.of(), QiInventory.empty(), List.of(source));
        var now = new AtomicLong();
        BodyCommandHandler<String> handler = handler(store, initial, now);
        assertTrue(capture(handler, store, body.id(), "first").hasCommandCommitted());
        var after = store.find(body.id()).orElseThrow();
        assertEquals(REFINED_FRACTION, after.organStates().getFirst().buffer().totalQi(), EPSILON);
        var rejected = capture(handler, store, body.id(), "same-second");
        assertEquals(MeridianMessages.FailureCode.CAPACITY_EXCEEDED, rejected.getFailure().getCode());
        assertEquals(after, store.find(body.id()).orElseThrow());

        var persisted = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(after));
        var environment = store.environmentSource(SOURCE).orElseThrow();
        var restored = new InMemoryBodyStore(List.of(persisted), store.receipts(),
                store.environmentQi(), List.of(environment));
        BodyCommandHandler<String> resumed = handler(restored, initial, now);
        assertEquals(MeridianMessages.FailureCode.CAPACITY_EXCEEDED,
                capture(resumed, restored, body.id(), "reloaded").getFailure().getCode());
        assertEquals(environment, restored.environmentSource(SOURCE).orElseThrow());
        now.set(SECOND);
        advance(resumed, restored, body.id());
        assertTrue(capture(resumed, restored, body.id(), "next-second").hasCommandCommitted());
        var finalBody = restored.find(body.id()).orElseThrow();
        assertEquals(REFINED_FRACTION * 2, finalBody.organStates().getFirst().buffer().totalQi(), EPSILON);
        double remaining = BodyProtobuf.fromInventory(restored.environmentSource(SOURCE)
                .orElseThrow().getInventory()).totalQi();
        assertEquals(body.totalQi() + SOURCE_QI, finalBody.totalQi() + remaining, EPSILON);
    }

    private static BodyMessages.BodySnapshot initial() {
        // 非人器官使用同一规则，限额与模板种类无关。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var intake = BodyMessages.OrganIntakeCapability.newBuilder().setMaxQiPerSecond(1).build();
        var organ = original.getStructure().getOrgans(0).toBuilder().setIntake(intake).build();
        var structure = original.getStructure().toBuilder().setOrgans(0, organ).build();
        var time = original.getSettledAt().toBuilder().setElapsedMillis(0).build();
        return original.toBuilder()
                .setStructure(structure)
                .setSettledAt(time)
                .setRevision(0)
                .clearTransits()
                .build();
    }

    private static EnvironmentMessages.EnvironmentQiSource source(BodyMessages.TimeCursor time) {
        // 浓度足够高以触达摄入上限，剩余库存用于验证事务守恒。
        var space = EnvironmentMessages.EnvironmentSpace.newBuilder()
                .setProtocolVersion(1)
                .setSourceId(SOURCE)
                .setKind(EnvironmentMessages.EnvironmentSpaceKind.REGION_BACKGROUND)
                .setVolumeBlocks(SOURCE_VOLUME)
                .build();
        var expiry = time.toBuilder().setElapsedMillis(SOURCE_EXPIRY).build();
        return EnvironmentAbsorption.initialize(space, BodyProtobuf.fromInventory(qi("addon:mist", SOURCE_QI)),
                time, expiry, new EnvironmentQiRules(EnvironmentConfigLoader.defaults()));
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, BodyMessages.BodySnapshot initial,
                                                       AtomicLong now) {
        // 时钟由测试宿主推进，命令本身不能上报时间或刷新额度。
        BodyTimeSource<String> clock = (caller, body) -> {
            var time = initial.getSettledAt().toBuilder().setElapsedMillis(now.get()).build();
            return MeridianMessages.TimeAdvancePlan.newBuilder()
                    .setTarget(time)
                    .setMode(MeridianMessages.TimeAdvanceMode.ACTIVE)
                    .setBiologicalSecondsPerSimulationSecond(1)
                    .build();
        };
        var recipe = BodyMessages.OrganRecipe.newBuilder()
                .setId(RECIPE)
                .setVersion(1)
                .setOrganKind("sample:filter")
                .setInput(qi("addon:mist", 1))
                .setOutput(qi("meridian:none", REFINED_FRACTION))
                .setDurationMillis(1)
                .setMinimumRealm(BodyMessages.Realm.WANDERING_QI)
                .build();
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true, clock, store,
                (caller, receipt, before, after) -> { /* 内存 Store 已保存成功事务。 */ },
                new OrganRecipeCatalog(List.of(recipe)), (caller, body, plan) -> Optional.of(SOURCE));
    }

    private static MeridianMessages.ResponseEnvelope capture(BodyCommandHandler<String> handler,
            InMemoryBodyStore store, BodyId id, String requestId) {
        // 原有的整格采样用于触达单肺上限。
        return capture(handler, store, id, requestId, 1);
    }

    private static MeridianMessages.ResponseEnvelope capture(BodyCommandHandler<String> handler,
            InMemoryBodyStore store, BodyId id, String requestId, double sample) {
        // 每次读取实际版本，避免把版本冲突误当成摄入上限生效。
        var capture = MeridianMessages.CaptureQi.newBuilder()
                .setOrganId(ORGAN)
                .setRecipeId(RECIPE)
                .setRecipeVersion(1)
                .setSampleVolumeBlocks(sample)
                .build();
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(store.find(id).orElseThrow().revision())
                .setCaptureQi(capture)
                .build();
        var request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        return MeridianProtocol.decodeResponse(handler.handleCapturedQi("owner", request.toByteArray()));
    }

    private static BodyMessages.QiInventory qi(String attribute, double amount) {
        // 配方和环境保持相同属性与密度，测试只关注额度和守恒。
        var entry = BodyMessages.Qi.newBuilder()
                .setDensity(DENSITY)
                .setAttribute(attribute)
                .setAmount(amount)
                .build();
        return BodyMessages.QiInventory.newBuilder().addEntries(entry).build();
    }

    private static void advance(BodyCommandHandler<String> handler, InMemoryBodyStore store, BodyId id) {
        // 接触命令要求已结算到可信时刻，先走正式时间命令推进身体。
        var command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(id.value())
                .setExpectedRevision(store.find(id).orElseThrow().revision())
                .setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance())
                .build();
        var request = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId("advance")
                .setBodyCommand(command)
                .build();
        var response = MeridianProtocol.decodeResponse(handler.handle("owner", request.toByteArray()));
        assertTrue(response.toString(), response.hasCommandCommitted());
    }
}
