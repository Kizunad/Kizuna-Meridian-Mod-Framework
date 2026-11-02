package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodyAction;
import dev.kizuna.meridian.core.BodyActions;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.pb.ActivityMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyActivityExecution;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 组织训练回归：可信做功、空闲巩固、能力成长和存档必须沿同一状态工作。 */
public final class TissueTrainingTest {
    private static final Anatomy.TissueId TISSUE = new Anatomy.TissueId("tendril");
    private static final long HALF_LIFE_MILLIS = 600_000;
    private static final long TWO_HALF_LIVES_MILLIS = HALF_LIFE_MILLIS * 2;
    private static final double MILLIS_PER_SECOND = 1_000;
    private static final double TEST_LIFESPAN_SECONDS = 1_000_000;
    private static final double MAXIMUM_GROWTH = 0.5;
    private static final double WORK_JOULES_PER_GROWTH = 1_000;

    @Test
    public void trustedTrainingEffortAddsFatigueAndPendingLoad() {
        // 训练标记只能从可信活动进入，并与普通做功共用力量、功率和有效耐力校验。
        BodySnapshot initial = trainingBody(0, 0);
        var effort = ActivityMessages.ActivityEffort.newBuilder()
                .setTissueId(TISSUE.value())
                .setWatts(10)
                .setSeconds(1)
                .setTraining(true)
                .build();
        var plan = ActivityMessages.BodyActivity.newBuilder().addEfforts(effort).build();

        BodySnapshot trained = BodyActivityExecution.apply(initial, plan, "sample:host");

        assertEquals(10, trained.tissueStates().get(0).pendingTrainingLoad(), 0);
        assertTrue(trained.tissueStates().get(0).fatigue() > initial.tissueStates().get(0).fatigue());
    }

    @Test
    public void pendingLoadConsolidatesOnlyWhenTissueIsFree() {
        // 动作占用期间不巩固；释放占用后同一半衰期才结算一半负荷。
        BodySnapshot initial = trainingBody(0, 100);
        var definition = new BodyAction.Definition(new DefinitionId("sample:hold"), 1, "占用",
                List.of(TISSUE), List.of());
        BodySnapshot occupied = BodyActions.start(initial, "hold", definition);
        BodySnapshot blocked = advance(occupied, HALF_LIFE_MILLIS);
        assertEquals(100, blocked.tissueStates().get(0).pendingTrainingLoad(), 0);
        assertEquals(0, blocked.tissueStates().get(0).trainingGrowth(), 0);

        BodySnapshot released = BodyActions.stop(blocked, "hold");
        BodySnapshot consolidated = advance(released, TWO_HALF_LIVES_MILLIS);
        assertEquals(50, consolidated.tissueStates().get(0).pendingTrainingLoad(), 1e-10);
        assertEquals(0.05, consolidated.tissueStates().get(0).trainingGrowth(), 1e-10);
    }

    @Test
    public void growthRaisesEnduranceOnlyAndStopsAtConfiguredMaximum() {
        // 成长只放大有效耐力；力量、爆发功率和封顶规则保持定义中的独立值。
        BodySnapshot grown = trainingBody(0.2, 0);
        var capacity = TissueCapabilities.available(grown, TISSUE);
        assertEquals(72, capacity.strengthNewtons(), 1e-10);
        assertEquals(144, capacity.burstWatts(), 1e-10);
        assertEquals(864, capacity.enduranceJoules(), 1e-10);

        BodySnapshot nearLimit = trainingBody(0.49, 20_000);
        BodySnapshot capped = advance(nearLimit, HALF_LIFE_MILLIS);
        assertEquals(MAXIMUM_GROWTH, capped.tissueStates().get(0).trainingGrowth(), 1e-10);
        assertEquals(0, capped.tissueStates().get(0).pendingTrainingLoad(), 0);
    }

    @Test
    public void trainingStateSurvivesProtobufRoundTripAndRequiresDefinition() {
        // 训练定义、成长与待巩固负荷都是存档事实；
        // 没有训练定义的组织不能伪造训练状态。
        BodySnapshot source = trainingBody(0.2, 123.5);
        assertEquals(source, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(source)));

        BodyMessages.BodySnapshot untrained = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        BodyMessages.TissueState malformedState = untrained.getTissueStates(0).toBuilder()
                .setTrainingGrowth(0.1)
                .build();
        BodyMessages.BodySnapshot malformed = untrained.toBuilder()
                .setTissueStates(0, malformedState)
                .build();
        assertThrows(IllegalArgumentException.class, () -> BodyProtobuf.fromMessage(malformed));
    }

    private static BodySnapshot trainingBody(double growth, double pendingLoad) {
        // 使用非人体样例保持框架测试不依赖 Human 内容，同时给组织补上训练参数。
        BodySnapshot source = ProtocolFixtures.snapshot(Realm.WANDERING_QI);
        BodyMessages.BodySnapshot message = BodyProtobuf.toMessage(source);
        BodyMessages.TissueTraining training = BodyMessages.TissueTraining.newBuilder()
                .setMaximumGrowth(MAXIMUM_GROWTH)
                .setConsolidationHalfLifeSeconds(HALF_LIFE_MILLIS / MILLIS_PER_SECOND)
                .setWorkJoulesPerGrowth(WORK_JOULES_PER_GROWTH)
                .build();
        BodyMessages.TissueDefinition definition = message.getStructure().getTissues(0).toBuilder()
                .setTraining(training)
                .build();
        BodyMessages.BodyStructure structure = message.getStructure().toBuilder()
                .setTissues(0, definition)
                .build();
        BodyMessages.TissueState state = message.getTissueStates(0).toBuilder()
                .setTrainingGrowth(growth)
                .setPendingTrainingLoad(pendingLoad)
                .build();
        BodyMessages.TimeCursor time = BodyMessages.TimeCursor.newBuilder()
                .setDomain("sample:world")
                .setEpoch("epoch-1")
                .setElapsedMillis(0)
                .build();
        BodyMessages.VitalityState vitality = message.getVitality().toBuilder()
                .setAgeSeconds(0)
                .setLifespanLimitSeconds(TEST_LIFESPAN_SECONDS)
                .setSpentLifespanSeconds(0)
                .setDead(false)
                .build();
        BodyMessages.BodySnapshot body = message.toBuilder()
                .setStructure(structure)
                .setTissueStates(0, state)
                .setSettledAt(time)
                .setVitality(vitality)
                .setRevision(0)
                .build();
        return BodyProtobuf.fromMessage(body);
    }

    private static BodySnapshot advance(BodySnapshot body, long targetMillis) {
        // 所有时间推进都使用同一时钟域；模拟秒倍率只影响寿命，不改变训练半衰期。
        var target = new TimeCursor(new DefinitionId("sample:world"), "epoch-1", targetMillis);
        var plan = new BodyTimeAdvancement.Plan(target, 1, BodyTimeAdvancement.Mode.ACTIVE);
        return BodyTimeAdvancement.apply(body, plan).body();
    }
}
