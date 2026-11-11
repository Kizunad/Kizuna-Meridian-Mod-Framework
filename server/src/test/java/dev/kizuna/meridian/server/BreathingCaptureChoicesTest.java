package dev.kizuna.meridian.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganRecipe;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingOrgan;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingProfile;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.GatheringChoice;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/** 吐纳命中时的配方顺序：同一器官同一属性先用境界要求最高的配方，别的器官、属性和非游离输入不参与。 */
public class BreathingCaptureChoicesTest {
    private static final String ORGAN = "lungs";
    private static final String OTHER_ORGAN = "gills";
    private static final String FIRE = "meridian:fire";
    private static final String WATER = "meridian:water";
    private static final double FREE = 0.01;
    private static final double GAS = 0.1;

    @Test
    public void higherRealmRecipeComesFirstAndLowerStaysAsFallback() {
        // 目录顺序把低境界配方放在前面，排序后高境界在前；同境界保持目录原顺序。
        var base = recipe("sample:breath_fire", FIRE, FREE, Realm.WANDERING_QI);
        var gas = recipe("sample:breath_fire_gas", FIRE, FREE, Realm.TRUE_QI);
        var water = recipe("sample:breath_water", WATER, FREE, Realm.TRUE_QI);
        var dense = recipe("sample:condense_fire", FIRE, GAS, Realm.TRUE_QI);
        var unlisted = recipe("sample:unlisted_fire", FIRE, FREE, Realm.ESSENCE_TRANSFORMATION);
        List<GatheringChoice> choices = List.of(choice(ORGAN, base), choice(ORGAN, water), choice(ORGAN, dense),
                choice(OTHER_ORGAN, gas), choice(ORGAN, gas), choice(ORGAN, unlisted));
        var profile = BreathingProfile.newBuilder()
                .addOrgans(organ(ORGAN))
                .addOrgans(organ(OTHER_ORGAN))
                .addAllRecipeIds(List.of(base.getId(), gas.getId(), water.getId(), dense.getId()))
                .build();

        List<String> ordered = BreathingDriver.captureChoices(profile, choices, ORGAN, FIRE).stream()
                .map(choice -> choice.getRecipe().getId())
                .toList();

        assertEquals(List.of(gas.getId(), base.getId()), ordered);
    }

    private static BreathingOrgan organ(String organId) {
        // 吐纳器官只需 ID 参与排序。
        return BreathingOrgan.newBuilder()
                .setOrganId(organId)
                .build();
    }

    private static GatheringChoice choice(String organId, OrganRecipe recipe) {
        // 每个组合只填排序用到的器官与配方。
        return GatheringChoice.newBuilder()
                .setOrganId(organId)
                .setRecipe(recipe)
                .build();
    }

    private static OrganRecipe recipe(String id, String attribute, double inputDensity, Realm minimumRealm) {
        // 单种输入配方；产物与时长不影响排序。
        Qi input = Qi.newBuilder()
                .setAttribute(attribute)
                .setDensity(inputDensity)
                .setAmount(1)
                .build();
        QiInventory inputs = QiInventory.newBuilder()
                .addEntries(input)
                .build();
        return OrganRecipe.newBuilder()
                .setId(id)
                .setVersion(1)
                .setInput(inputs)
                .setMinimumRealm(minimumRealm)
                .build();
    }
}
