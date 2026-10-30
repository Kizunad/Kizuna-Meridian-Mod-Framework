package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiDebugging;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.DebugQiMode;
import org.junit.Test;

import static org.junit.Assert.*;

/** 调试补元对高密度存量与游离残余混合内腔的容量边界。 */
public class DebugQiCapacityTest {
    private static final String NONE = "meridian:none";
    private static final double RESIDUAL_DENSITY = 0.05;
    private static final double RESIDUAL_SHARE = 0.1;
    private static final double LUMEN_VOLUME = 3.7;
    private static final double SET_SLACK_VOLUME = 5e-9;
    private static final double[] MAIN_DENSITIES = {0.1, 0.34, 0.37, 0.4, 0.43, 0.61, 0.97};
    private static final double[] FILL_RATIOS = {0.4, 0.8, 0.9};

    @Test
    public void fillNearHighDensityStockStaysWithinLumenVolume() {
        // 真炁主元占大头时，补元按剩余体积直接换算，合并残余后仍不超过内腔上限，再补一次视为已满。
        for (double mainDensity : MAIN_DENSITIES) {
            for (double fillRatio : FILL_RATIOS) {
                BodySnapshot body = mixedBody(mainDensity, fillRatio);
                BodySnapshot filled = QiDebugging.apply(body, fill());
                double occupied = filled.meridianStates().get(0).lumenQi().volume();
                String label = "主元密度 " + mainDensity + "，占用 " + fillRatio;
                assertTrue(label, occupied <= LUMEN_VOLUME);
                assertTrue(label, filled.lumenQiTotal() > body.lumenQiTotal());
                BodySnapshot refilled = QiDebugging.apply(filled, fill());
                assertEquals(label, filled.lumenQiTotal(), refilled.lumenQiTotal(), 0);
            }
        }
    }

    @Test
    public void setInventoryWithinSlackStaysWithinLumenVolume() {
        // 总量检查容许的体积余量不能落进单条内腔，覆盖后仍不超过上限。
        BodySnapshot body = mixedBody(MAIN_DENSITIES[0], FILL_RATIOS[0]);
        String meridianId = body.structure().meridians().get(0).id().value();
        double amount = (LUMEN_VOLUME + SET_SLACK_VOLUME) * RESIDUAL_DENSITY;
        DebugQi request = DebugQi.newBuilder()
                .setMode(DebugQiMode.SET_INVENTORY)
                .setMeridianId(meridianId)
                .setAmount(amount)
                .setDensity(RESIDUAL_DENSITY)
                .build();
        BodySnapshot set = QiDebugging.apply(body, request);
        assertTrue(set.meridianStates().get(0).lumenQi().volume() <= LUMEN_VOLUME);
    }

    private static DebugQi fill() {
        // 省略经脉与数量即填满全部经脉内腔。
        return DebugQi.newBuilder()
                .setMode(DebugQiMode.FILL_LUMENS)
                .build();
    }

    private static BodySnapshot mixedBody(double mainDensity, double fillRatio) {
        // 体积先定、元数由密度反推，与类人生成器同法；经脉改为完好、清空在途、版本归零以允许补元。
        var message = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI));
        var meridian = message.getStructure().getMeridians(0).toBuilder()
                .setLumenVolume(LUMEN_VOLUME)
                .build();
        var structure = message.getStructure().toBuilder()
                .setMeridians(0, meridian)
                .build();
        double occupiedVolume = LUMEN_VOLUME * fillRatio;
        double residualVolume = occupiedVolume * RESIDUAL_SHARE;
        double mainVolume = occupiedVolume - residualVolume;
        var main = qi(mainVolume * mainDensity, mainDensity);
        var residual = qi(residualVolume * RESIDUAL_DENSITY, RESIDUAL_DENSITY);
        var lumen = BodyMessages.QiInventory.newBuilder()
                .addEntries(main)
                .addEntries(residual)
                .build();
        var state = message.getMeridianStates(0).toBuilder()
                .setCondition(BodyMessages.MeridianCondition.INTACT)
                .setLumenQi(lumen)
                .build();
        var mixed = message.toBuilder()
                .setStructure(structure)
                .clearTransits()
                .setMeridianStates(0, state)
                .setRevision(0)
                .build();
        return BodyProtobuf.fromMessage(mixed);
    }

    private static BodyMessages.Qi qi(double amount, double density) {
        // 无属性真元，补元的新明细与同相态残余合并。
        return BodyMessages.Qi.newBuilder()
                .setAttribute(NONE)
                .setAmount(amount)
                .setDensity(density)
                .build();
    }
}
