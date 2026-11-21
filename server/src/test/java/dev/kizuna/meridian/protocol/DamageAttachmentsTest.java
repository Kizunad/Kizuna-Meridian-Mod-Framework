package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageResolution;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DeathProtection;
import dev.kizuna.meridian.protocol.pb.HealthMessages.Healing;
import dev.kizuna.meridian.protocol.pb.HealthMessages.HpLoss;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.ActionCatalog;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyDamageHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.server.MeridianOpeningCatalog;
import dev.kizuna.meridian.server.OrganRecipeCatalog;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 伤害命令带的两样附带：器官份额原样交给伤害策略；能力修正和扣血同一笔提交，不合法就整笔不提交。 */
public class DamageAttachmentsTest {
    private static final String SOURCE = "sample:attack";
    private static final String OTHER_SOURCE = "sample:technique";
    private static final String NUMB = "sample:numb";
    private static final String TISSUE = "tendril";
    private static final long NUMB_MILLIS = 6_000;
    private static final long LONGER_MILLIS = 9_000;
    private static final double NUMB_PEAK = 0.3;
    private static final double ORGAN_SHARE = 0.6;
    private static final double PROTECTED_DAMAGE_SHARE = 0.6;
    private static final double HIT_HP = 5;
    private static final double LETHAL_HP = 1_000;
    private static final double TOLERANCE = 1e-9;
    private static final long CLOCK_MILLIS = 60_000;
    private static final double PROTECTED_HP = 10;

    @Test
    public void organShareReachesTheDamagePolicyUnchanged() {
        // 框架不解释透劲，只把份额带进伤害上下文；没写的时候策略看到的是「没有」。
        BodySnapshot initial = body();
        var seen = new AtomicReference<DamageBody>();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> {
            seen.set(HealthProtobuf.decodeContext(payload).getDamage());
            return resolution(false /* lethal */);
        });
        committed(handler.handleHealthEffect("host", request("plain", 0, damage())));
        assertFalse(seen.get().hasOrganShare());
        committed(handler.handleHealthEffect("host", request("through", 1, damage().setOrganShare(ORGAN_SHARE))));
        assertEquals(ORGAN_SHARE, seen.get().getOrganShare(), 0);
        assertThrows(IllegalArgumentException.class, () -> request("over", 2, damage().setOrganShare(1.5)));
        assertThrows(IllegalArgumentException.class, () -> request("nan", 2, damage().setOrganShare(Double.NaN)));
    }

    @Test
    public void protectedDamageShareIsOptionalAndReachesTheDamagePolicyUnchanged() {
        // 缺省不改变旧调用；显式份额由宿主计算，框架只校验并原样传递。
        BodySnapshot initial = body();
        var seen = new AtomicReference<DamageBody>();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> {
            seen.set(HealthProtobuf.decodeContext(payload).getDamage());
            return resolution(false /* lethal */);
        });
        committed(handler.handleHealthEffect("host", request("protected-default", 0, damage())));
        assertFalse(seen.get().hasProtectedDamageShare());
        committed(handler.handleHealthEffect("host", request("protected-zero", 1,
                damage().setProtectedDamageShare(0))));
        assertTrue(seen.get().hasProtectedDamageShare());
        assertEquals(0, seen.get().getProtectedDamageShare(), 0);
        committed(handler.handleHealthEffect("host", request("protected-share", 2,
                damage().setProtectedDamageShare(PROTECTED_DAMAGE_SHARE))));
        assertEquals(PROTECTED_DAMAGE_SHARE, seen.get().getProtectedDamageShare(), 0);
        assertThrows(IllegalArgumentException.class, () -> request("protected-over", 3,
                damage().setProtectedDamageShare(1.5)));
        assertThrows(IllegalArgumentException.class, () -> request("protected-negative", 3,
                damage().setProtectedDamageShare(-0.1)));
        assertThrows(IllegalArgumentException.class, () -> request("protected-nan", 3,
                damage().setProtectedDamageShare(Double.NaN)));
    }

    @Test
    public void modifiersLandInTheSameRevisionAsTheHpLoss() {
        // 一笔命令、一个版本号：扣的血和加的修正同时出现；开始时刻是这笔事务的身体时钟。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> resolution(false /* lethal */));
        committed(handler.handleHealthEffect("host", request("hit", 0, damage().addAddModifiers(numb(NUMB_MILLIS)))));
        BodySnapshot hurt = store.find(initial.id()).orElseThrow();
        assertEquals(initial.revision() + 1, hurt.revision());
        assertEquals(initial.tissueStates().get(0).hp() - HIT_HP, hurt.tissueStates().get(0).hp(), TOLERANCE);
        assertEquals(1, hurt.modifiers().size());
        var modifier = hurt.modifiers().get(0);
        assertEquals(NUMB, modifier.id().value());
        assertEquals(initial.settledAt().elapsedMillis(), modifier.curve().startMillis());
        assertEquals(NUMB_PEAK, modifier.peaks().tissue().strength(), 0);
    }

    @Test
    public void sameSourceSameIdIsReplacedNotStacked() {
        // 同一种麻再挨一下：换成新的那条（续期），身体里还是一条，不会两条相乘。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> resolution(false /* lethal */));
        committed(handler.handleHealthEffect("host", request("first", 0, damage().addAddModifiers(numb(NUMB_MILLIS)))));
        committed(handler.handleHealthEffect("host",
                request("second", 1, damage().addAddModifiers(numb(LONGER_MILLIS)))));
        var modifiers = store.find(initial.id()).orElseThrow().modifiers();
        assertEquals(1, modifiers.size());
        assertEquals(LONGER_MILLIS, modifiers.get(0).curve().points().get(1).offsetMillis());
    }

    @Test
    public void anyInvalidModifierRejectsTheWholeDamage() {
        // 附带不合法时连血也不扣：组织不存在、和别的来源的修正撞 ID，都是整笔拒绝，身体原样。
        BodySnapshot initial = withForeignModifier(body());
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> resolution(false /* lethal */));
        var missingTissue = numb(NUMB_MILLIS).toBuilder().setTissueId("missing");
        assertFalse(MeridianProtocol.decodeResponse(handler.handleHealthEffect("host",
                request("missing", 0, damage().addAddModifiers(missingTissue)))).hasCommandCommitted());
        var collides = numb(NUMB_MILLIS).toBuilder().setId("sample:boost");
        assertEquals(FailureCode.ACCESS_DENIED, MeridianProtocol.decodeResponse(handler.handleHealthEffect("host",
                request("collide", 0, damage().addAddModifiers(collides)))).getFailure().getCode());
        assertEquals(initial, store.find(initial.id()).orElseThrow());

        // 格式上就不对的在入口拒绝：来源不是这笔伤害、自己写了开始时刻、同一笔里 ID 重复。
        assertThrows(IllegalArgumentException.class, () -> request("source", 0,
                damage().addAddModifiers(numb(NUMB_MILLIS).toBuilder().setSource(OTHER_SOURCE))));
        assertThrows(IllegalArgumentException.class, () -> request("start", 0,
                damage().addAddModifiers(numb(NUMB_MILLIS).toBuilder().setStartMillis(1))));
        assertThrows(IllegalArgumentException.class, () -> request("twice", 0,
                damage().addAddModifiers(numb(NUMB_MILLIS)).addAddModifiers(numb(LONGER_MILLIS))));
    }

    @Test
    public void lethalDamageLeavesNoModifiers() {
        // 这一下打死了，身体上不留修正（死体不允许带修正）。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> resolution(true /* lethal */));
        committed(handler.handleHealthEffect("host", request("fatal", 0,
                damage().setDamageHp(LETHAL_HP).addAddModifiers(numb(NUMB_MILLIS)))));
        BodySnapshot dead = store.find(initial.id()).orElseThrow();
        assertTrue(dead.vitality().dead());
        assertTrue(dead.modifiers().isEmpty());
    }

    @Test
    public void damageSurvivedThroughDeathProtectionKeepsItsModifiers() {
        // 这一下本来致死、被保命方案救回来：人还活着，附带的修正照常留在身上。
        BodySnapshot initial = body();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var handler = handler(store, payload -> resolution(true /* lethal */));
        var protection = DeathProtection.newBuilder().setSourceId("sample:totem")
                .addMinimumHp(Healing.newBuilder().setTarget(HealthTarget.newBuilder().setTissueId(TISSUE))
                        .setHp(PROTECTED_HP));
        committed(handler.handleHealthEffect("host", request("saved", 0,
                damage().setDamageHp(LETHAL_HP).setDeathProtection(protection).addAddModifiers(numb(NUMB_MILLIS)))));
        BodySnapshot saved = store.find(initial.id()).orElseThrow();
        assertFalse(saved.vitality().dead());
        assertEquals(1, saved.modifiers().size());
        assertEquals(NUMB, saved.modifiers().get(0).id().value());
    }

    private static BodySnapshot body() {
        // 非人构型：一个部位、一块组织，够看扣血和修正落在哪。
        // 样例身体的时钟顶在长整型上限，修正的到期时刻会溢出，换成一个平常的时刻。
        var message = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI)).toBuilder().setRevision(0);
        message.setSettledAt(message.getSettledAt().toBuilder().setElapsedMillis(CLOCK_MILLIS));
        return BodyProtobuf.fromMessage(message.build());
    }

    private static BodySnapshot withForeignModifier(BodySnapshot body) {
        // 身体上先有一条别的来源加的修正，用来验证伤害附带顶不掉它。
        var foreign = numb(NUMB_MILLIS).toBuilder().setId("sample:boost").setSource(OTHER_SOURCE)
                .setStartMillis(body.settledAt().elapsedMillis());
        return BodyProtobuf.fromMessage(BodyProtobuf.toMessage(body).toBuilder().addModifiers(foreign).build());
    }

    private static BodyMessages.CapabilityModifier numb(long durationMillis) {
        // 麻：这块组织的力量和功率在持续时间里乘 0.3，常值曲线；开始时刻留给框架写。
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId(NUMB)
                .setSource(SOURCE)
                .setTissueId(TISSUE)
                .addCurve(BodyMessages.CurvePoint.newBuilder().setOffsetMillis(0).setValue(1))
                .addCurve(BodyMessages.CurvePoint.newBuilder().setOffsetMillis(durationMillis).setValue(1))
                .setStrengthPeak(NUMB_PEAK)
                .setBurstPeak(NUMB_PEAK)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .build();
    }

    private static DamageBody.Builder damage() {
        // 一下打在部位上的钝击；策略把损失记在组织上。
        return DamageBody.newBuilder().setSourceId(SOURCE).setDamageType("sample:impact").setKind(DamageKind.BLUNT)
                .setHit(HealthTarget.newBuilder().setPartId("body")).setDamageHp(HIT_HP);
    }

    private static byte[] request(String id, long revision, DamageBody.Builder damage) {
        // 走正式的请求编码，格式错误在这里就抛。
        return MeridianProtocol.encodeRequest(RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId(id)
                .setBodyCommand(BodyCommand.newBuilder().setDamageBody(damage).setBodyId("creature-1")
                        .setExpectedRevision(revision)).build());
    }

    private static byte[] resolution(boolean lethal) {
        // 策略固定把这一下记在那块组织上。
        var loss = HpLoss.newBuilder().setTarget(HealthTarget.newBuilder().setTissueId(TISSUE)).setHp(HIT_HP);
        return HealthProtobuf.encodeResolution(DamageResolution.newBuilder().setLethal(lethal).addLosses(loss).build());
    }

    private static void committed(byte[] response) {
        // 失败时把失败码带出来，好看是哪一步被拒。
        var decoded = MeridianProtocol.decodeResponse(response);
        assertTrue("没有提交：" + decoded.getFailure(), decoded.hasCommandCommitted());
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, BodyDamageHandler damage) {
        // 和 BodyHealthTest 同一套装配：可信健康入口、时间停在已结清的时刻、固定回执。
        return new BodyCommandHandler<>((caller, command) -> Optional.of(caller),
                (caller, command, current) -> true, (caller, current) -> plan(current), store,
                (caller, receipt, before, after) -> { /* 测试直接读已提交的身体。 */ },
                new OrganRecipeCatalog(List.of()), (caller, current, time) -> Optional.empty(),
                new ActionCatalog(List.of()), new MeridianOpeningCatalog(List.of()), caller -> false, damage);
    }

    private static TimeAdvancePlan plan(BodySnapshot body) {
        // 效果发生在当前已结清的时刻。
        return TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(body.settledAt()))
                .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(1).build();
    }
}
