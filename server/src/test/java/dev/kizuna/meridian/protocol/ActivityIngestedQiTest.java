package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.QiAccountChange;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyActivityExecution;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * 活动里的外来真元：服下的丹药这类从身体外面进来的真元，活动要写明进了多少，守恒式把它算进去。
 * 账户里多出来的量必须正好等于写明的量；没写明就多出来、写多了、写少了、装不下，整笔拒绝。
 */
public class ActivityIngestedQiTest {
    private static final String CALLER = "sample:pill";
    private static final String CHANNEL = "channel";
    private static final String EMBER = "addon:ember";
    private static final double FREE_DENSITY = 0.01;
    private static final double DOSE = 0.6;
    private static final double MISMATCH_FACTOR = 2;
    private static final double TOLERANCE = 1e-9;
    /** 通路内腔一共 10000 体积，这么多游离态真元（体积 = 量 ÷ 密度）远远装不下。 */
    private static final double TOO_MUCH = 1_000;

    @Test
    public void declaredIngestedQiIsCreditedToTheAccount() {
        // 往通路内腔记 0.6 余烬，活动写明外来 0.6：通过，身体总量正好多 0.6，原有的不动。
        BodySnapshot body = body();
        BodySnapshot after = BodyActivityExecution.apply(body, plan(body, DOSE, DOSE), CALLER);
        assertEquals(body.totalQi() + DOSE, after.totalQi(), TOLERANCE);
        var lumen = BodyProtobuf.toMessage(after).getMeridianStates(0).getLumenQi();
        double ember = lumen.getEntriesList().stream().filter(entry -> entry.getAttribute().equals(EMBER))
                .mapToDouble(BodyMessages.Qi::getAmount).sum();
        assertEquals(DOSE, ember, TOLERANCE);
    }

    @Test
    public void accountGrowthMustMatchTheDeclaredAmountExactly() {
        // 没写明就多出来（原来的规矩）、写少了、写多了，都不守恒，整笔拒绝。
        BodySnapshot body = body();
        for (double declared : new double[] {0, DOSE / MISMATCH_FACTOR, DOSE * MISMATCH_FACTOR}) {
            assertThrows("写明 " + declared, IllegalArgumentException.class,
                    () -> BodyActivityExecution.apply(body, plan(body, DOSE, declared), CALLER));
        }
    }

    @Test
    public void ingestedQiCannotOverfillTheLumen() {
        // 写明了也不能装过内腔的体积：外来真元不是绕开容量的口子。
        BodySnapshot body = body();
        assertThrows(IllegalArgumentException.class,
                () -> BodyActivityExecution.apply(body, plan(body, TOO_MUCH, TOO_MUCH), CALLER));
    }

    private static BodySnapshot body() {
        // 样例身体的时钟和版本号都顶在上限，改回零才提交得了活动；在途清掉，只看账户。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        var time = original.getSettledAt().toBuilder()
                .setElapsedMillis(0)
                .build();
        var reset = original.toBuilder()
                .setSettledAt(time)
                .setRevision(0)
                .clearTransits()
                .build();
        return BodyProtobuf.fromMessage(reset);
    }

    private static BodyActivity plan(BodySnapshot body, double credited, double declared) {
        // 通路内腔原有的照旧，另加一行游离态余烬；外来真元写 declared（0 就不写这一项）。
        var lumen = BodyProtobuf.toMessage(body).getMeridianStates(0).getLumenQi();
        var replacement = lumen.toBuilder()
                .addEntries(ember(credited))
                .build();
        var account = QiAccountChange.newBuilder()
                .setLumenId(CHANNEL)
                .setExpected(lumen)
                .setReplacement(replacement)
                .build();
        var plan = BodyActivity.newBuilder()
                .addAccounts(account);
        if (declared > 0) {
            var ingested = BodyMessages.QiInventory.newBuilder()
                    .addEntries(ember(declared))
                    .build();
            plan.setIngestedQi(ingested);
        }
        return plan.build();
    }

    private static BodyMessages.Qi ember(double amount) {
        // 样例使用固定属性和游离态密度，只改变声明量。
        return BodyMessages.Qi.newBuilder()
                .setAttribute(EMBER)
                .setAmount(amount)
                .setDensity(FREE_DENSITY)
                .build();
    }
}
