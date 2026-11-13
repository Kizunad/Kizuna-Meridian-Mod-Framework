package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiDebugging;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiShare;
import org.junit.Test;

import static org.junit.Assert.*;

/** 调试补元与覆盖按属性比例、指定密度拆分明细，比例配错整笔拒绝。 */
public class DebugQiSharesTest {
    private static final String FIRE = "meridian:fire";
    private static final String METAL = "meridian:metal";
    private static final String WITHERING = "qi_variants:withering";
    private static final double GAS = 0.1;
    private static final double LIQUID = 1;
    private static final double LUMEN_VOLUME = 3.7;

    @Test
    public void fillSplitsSharesAtRequestedDensityAndRestIsNone() {
        // 气态补满：火 0.3、金 0.2，余下 0.5 记无属性；三份都是气态且体积不越过内腔。
        BodySnapshot filled = QiDebugging.apply(emptyBody(), DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .setDensity(GAS)
                .addShares(share(FIRE, 0.3))
                .addShares(share(METAL, 0.2))
                .build());
        QiInventory lumen = filled.meridianStates().get(0).lumenQi();
        double total = lumen.totalQi();

        assertEquals(LUMEN_VOLUME * GAS, total, 1e-12);
        assertTrue(lumen.volume() <= LUMEN_VOLUME);
        assertEquals(0.3 * total, amount(lumen, FIRE, Qi.QiPhase.GAS), 1e-12);
        assertEquals(0.2 * total, amount(lumen, METAL, Qi.QiPhase.GAS), 1e-12);
        assertEquals(0.5 * total, amount(lumen, "meridian:none", Qi.QiPhase.GAS), 1e-12);
        assertEquals(3, lumen.entries().size());
    }

    @Test
    public void setWithFullSharesAtCapacityStaysWithinVolume() {
        // 液态覆盖到满容量且比例合计 1：拆成三份后体积求和的舍入不越过上限，也不留无属性。
        BodySnapshot body = emptyBody();
        String meridianId = body.structure().meridians().get(0).id().value();
        BodySnapshot set = QiDebugging.apply(body, DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setMeridianId(meridianId)
                .setAmount(LUMEN_VOLUME * LIQUID)
                .setDensity(LIQUID)
                .addShares(share(FIRE, 0.1))
                .addShares(share(METAL, 0.2))
                .addShares(share(WITHERING, 0.7))
                .build());
        QiInventory lumen = set.meridianStates().get(0).lumenQi();

        assertTrue(lumen.volume() <= LUMEN_VOLUME);
        assertEquals(0.7 * LUMEN_VOLUME, amount(lumen, WITHERING, Qi.QiPhase.LIQUID), 1e-9);
        assertEquals(0, amount(lumen, "meridian:none", Qi.QiPhase.LIQUID), 0);
        assertEquals(3, lumen.entries().size());
    }

    @Test
    public void rejectsBadSharesAndDensities() {
        // 合计超 1、重复、零份、越过 1、非法 ID、扩散带比例或密度、补元密度低于游离态都整笔拒绝。
        DebugQi fill = DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).build();
        DebugQi inject = DebugQi.newBuilder().setMode(DebugQiMode.INJECT_FOR_DIFFUSION)
                .setMeridianId("channel").setAmount(0.01).build();
        BodySnapshot body = emptyBody();

        assertRejected(body, fill.toBuilder().addShares(share(FIRE, 0.6)).addShares(share(METAL, 0.5)).build());
        assertRejected(body, fill.toBuilder().addShares(share(FIRE, 0.2)).addShares(share(FIRE, 0.2)).build());
        assertRejected(body, fill.toBuilder().addShares(share(FIRE, 0)).build());
        assertRejected(body, fill.toBuilder().addShares(share(FIRE, 1.5)).build());
        assertRejected(body, fill.toBuilder().addShares(share("Fire", 0.5)).build());
        assertRejected(body, inject.toBuilder().addShares(share(FIRE, 0.5)).build());
        assertRejected(body, inject.toBuilder().setDensity(GAS).build());
        assertRejected(body, fill.toBuilder().setDensity(0.005).build());
        assertNotNull(QiDebugging.apply(body, inject));
    }

    @Test
    public void sharesSurviveWireValidation() {
        // 比例作为重复子消息通过正式线协议；空列表是合法默认值。
        DebugQi request = DebugQi.newBuilder().setMode(DebugQiMode.FILL_LUMENS).setDensity(GAS)
                .addShares(share(FIRE, 0.25)).build();
        var envelope = MeridianMessages.RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("debug-shares")
                .setBodyCommand(MeridianMessages.BodyCommand.newBuilder().setBodyId("body-1")
                        .setExpectedRevision(0).setDebugQi(request)).build();

        var decoded = MeridianProtocol.decodeRequest(MeridianProtocol.encodeRequest(envelope));

        assertEquals(request, decoded.getBodyCommand().getDebugQi());
    }

    private static void assertRejected(BodySnapshot body, DebugQi request) {
        // 拒绝发生在改账之前，属于请求无效而不是容量或身体状态拒绝。
        assertThrows(request.toString(), IllegalArgumentException.class, () -> QiDebugging.apply(body, request));
    }

    private static double amount(QiInventory inventory, String attribute, Qi.QiPhase phase) {
        // 同属性同相态在库存中唯一，缺省视为 0。
        return inventory.entries().stream()
                .filter(qi -> qi.attribute().id().value().equals(attribute) && qi.phase() == phase)
                .mapToDouble(Qi::amount)
                .sum();
    }

    private static DebugQiShare share(String attribute, double fraction) {
        // 测试只构造线消息，比例校验交给被测算子。
        return DebugQiShare.newBuilder().setAttribute(attribute).setFraction(fraction).build();
    }

    private static BodySnapshot emptyBody() {
        // 夹具经脉改为完好、内腔清空、在途清空、版本归零，补元结果只取决于请求本身。
        var message = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI));
        var meridian = message.getStructure().getMeridians(0).toBuilder()
                .setLumenVolume(LUMEN_VOLUME)
                .build();
        var state = message.getMeridianStates(0).toBuilder()
                .setCondition(BodyMessages.MeridianCondition.INTACT)
                .setLumenQi(BodyMessages.QiInventory.getDefaultInstance())
                .build();
        var empty = message.toBuilder()
                .setStructure(message.getStructure().toBuilder().setMeridians(0, meridian))
                .clearTransits()
                .setMeridianStates(0, state)
                .setRevision(0)
                .build();
        return BodyProtobuf.fromMessage(empty);
    }
}
