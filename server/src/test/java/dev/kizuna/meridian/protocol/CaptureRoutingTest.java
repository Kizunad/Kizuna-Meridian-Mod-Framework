package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganIntakeCapability;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganRecipe;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OutputRoute;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TimeCursor;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpace;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpaceKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CaptureQi;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvanceMode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.TimeAdvancePlan;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.BodyTimeSource;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import dev.kizuna.meridian.server.OrganRecipeCatalog;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 即时摄入配方的产物去处：配方给某种属性写了去处（经脉标签），这一样就不进器官缓存，
 * 直接记进带这个标签、已经开拓的经脉内腔；没有这样的经脉或者装不下的部分，照同样的量算成无属性留在器官缓存里。
 * 用非人体构型：一个滤元器、一条通路，吸「雾」出无属性和雾两样。
 */
public class CaptureRoutingTest {
    private static final String MIST = "addon:mist";
    private static final String NONE = "meridian:none";
    private static final String EMBER_TAG = "sample:ember";
    private static final String SOURCE = "sample:contact";
    private static final double SOURCE_VOLUME = 100;
    private static final double SOURCE_QI = 1000;
    private static final double INPUT = 0.05;
    private static final double PLAIN_OUTPUT = 0.0075;
    private static final double NATIVE_OUTPUT = 0.0025;
    private static final double FREE_DENSITY = 0.01;
    private static final double TOLERANCE = 1e-10;
    /** 一次接触吸进来的原料：来源浓度 10 元/格 × 采样 1 格；炼出两成，其中四分之一是雾。 */
    private static final double RAW = SOURCE_QI / SOURCE_VOLUME;
    private static final double PLAIN = RAW * PLAIN_OUTPUT / INPUT;
    private static final double NATIVE = RAW * NATIVE_OUTPUT / INPUT;

    @Test
    public void routedAttributeGoesStraightIntoTheTaggedOpenedMeridian() {
        // 通路带着标签、开着、空着：雾直接进通路内腔，器官缓存里只有无属性那一份。
        BodySnapshot after = capture(routed(), body -> tagged(body, EMBER_TAG));
        assertEquals(PLAIN, after.organStates().get(0).buffer().totalQi(), TOLERANCE);
        assertEquals(PLAIN, attribute(after.organStates().get(0).buffer(), NONE), TOLERANCE);
        assertEquals(NATIVE, attribute(after.meridianStates().get(0).lumenQi(), MIST), TOLERANCE);
    }

    @Test
    public void withoutAnOpenedTaggedMeridianTheShareStaysPlainInTheOrgan() {
        // 通路没有这个标签，或者带着标签但没开：这一份照同样的量算成无属性留在器官里，总量不变，通路不动。
        for (UnaryOperator<BodyMessages.BodySnapshot> shape : List.<UnaryOperator<BodyMessages.BodySnapshot>>of(
                body -> body, body -> unopened(tagged(body, EMBER_TAG)))) {
            BodySnapshot after = capture(routed(), shape);
            assertEquals(PLAIN + NATIVE, attribute(after.organStates().get(0).buffer(), NONE), TOLERANCE);
            assertEquals(0, after.meridianStates().get(0).lumenQi().totalQi(), 0);
        }
    }

    @Test
    public void whatDoesNotFitTheMeridianStaysPlainInTheOrgan() {
        // 通路内腔只装得下一半的雾：装得下的进通路，装不下的那一半算成无属性留在器官里。
        double room = NATIVE / 2 / FREE_DENSITY;
        BodySnapshot after = capture(routed(), body -> lumenVolume(tagged(body, EMBER_TAG), room));
        assertEquals(NATIVE / 2, attribute(after.meridianStates().get(0).lumenQi(), MIST), TOLERANCE);
        assertEquals(PLAIN + NATIVE / 2, attribute(after.organStates().get(0).buffer(), NONE), TOLERANCE);
    }

    @Test
    public void recipesWithoutRoutesKeepEverythingInTheOrganAsBefore() {
        // 没写去处的配方和原来一样：两样产物都进器官缓存。
        BodySnapshot after = capture(recipe().build(), body -> tagged(body, EMBER_TAG));
        assertEquals(NATIVE, attribute(after.organStates().get(0).buffer(), MIST), TOLERANCE);
        assertEquals(0, after.meridianStates().get(0).lumenQi().totalQi(), 0);
    }

    @Test
    public void routesMustNameAnOutputAttributeOnceAndATag() {
        // 去处只能写给产物里真有的、不是无属性的那一样，一样只写一次，标签不能空。
        for (OutputRoute bad : List.of(route("addon:smoke", EMBER_TAG), route(NONE, EMBER_TAG), route(MIST, ""))) {
            OrganRecipe recipe = recipe().addOutputRoutes(bad).build();
            assertThrows(bad.toString(), IllegalArgumentException.class, () -> CapabilityProtobuf.fromRecipe(recipe));
        }
        OrganRecipe twice = recipe().addOutputRoutes(route(MIST, EMBER_TAG))
                .addOutputRoutes(route(MIST, "sample:other")).build();
        assertThrows(IllegalArgumentException.class, () -> CapabilityProtobuf.fromRecipe(twice));
        CapabilityProtobuf.fromRecipe(routed());
    }

    private static OrganRecipe routed() {
        return recipe().addOutputRoutes(route(MIST, EMBER_TAG)).build();
    }

    private static OrganRecipe.Builder recipe() {
        // 吸 0.05 雾，出 0.0075 无属性 + 0.0025 雾，都是游离态。
        var output = BodyMessages.QiInventory.newBuilder().addEntries(qi(NONE, PLAIN_OUTPUT))
                .addEntries(qi(MIST, NATIVE_OUTPUT));
        return OrganRecipe.newBuilder().setId("sample:contact_refine").setVersion(1).setOrganKind("sample:filter")
                .setInput(BodyMessages.QiInventory.newBuilder().addEntries(qi(MIST, INPUT))).setOutput(output)
                .setDurationMillis(100).setMinimumRealm(BodyMessages.Realm.WANDERING_QI);
    }

    private static OutputRoute route(String attribute, String tag) {
        return OutputRoute.newBuilder().setAttribute(attribute).setMeridianTag(tag).build();
    }

    private static BodyMessages.Qi qi(String attribute, double amount) {
        return BodyMessages.Qi.newBuilder().setAttribute(attribute).setAmount(amount).setDensity(FREE_DENSITY).build();
    }

    private static double attribute(QiInventory inventory, String attribute) {
        return inventory.ofAttribute(Qi.QiAttribute.of(attribute)).totalQi();
    }

    private static BodyMessages.BodySnapshot tagged(BodyMessages.BodySnapshot body, String tag) {
        var meridian = body.getStructure().getMeridians(0).toBuilder().addTags(tag);
        return body.toBuilder().setStructure(body.getStructure().toBuilder().setMeridians(0, meridian)).build();
    }

    private static BodyMessages.BodySnapshot unopened(BodyMessages.BodySnapshot body) {
        var opening = MeridianOpening.newBuilder().setStage(MeridianOpeningStage.UNOPENED).setProgress(0);
        return body.toBuilder().setMeridianStates(0, body.getMeridianStates(0).toBuilder().setOpening(opening)).build();
    }

    private static BodyMessages.BodySnapshot lumenVolume(BodyMessages.BodySnapshot body, double volume) {
        var meridian = body.getStructure().getMeridians(0).toBuilder().setLumenVolume(volume);
        return body.toBuilder().setStructure(body.getStructure().toBuilder().setMeridians(0, meridian)).build();
    }

    private static BodySnapshot capture(OrganRecipe recipe, UnaryOperator<BodyMessages.BodySnapshot> shape) {
        // 经真实命令处理器走一次可信接触摄入，返回提交后的身体；存档按回执复算，去处不一致会被拒绝。
        var original = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.WANDERING_QI));
        TimeCursor now = original.getSettledAt().toBuilder().setElapsedMillis(0).build();
        var organ = original.getStructure().getOrgans(0).toBuilder()
                .setIntake(OrganIntakeCapability.newBuilder().setMaxQiPerSecond(1_000)).build();
        var empty = original.getMeridianStates(0).toBuilder().clearLumenQi().clearWallQi()
                .setLumenQi(BodyMessages.QiInventory.getDefaultInstance())
                .setWallQi(BodyMessages.QiInventory.getDefaultInstance());
        var prepared = original.toBuilder().setStructure(original.getStructure().toBuilder().setOrgans(0, organ))
                .setMeridianStates(0, empty).setSettledAt(now).setRevision(0).clearTransits().build();
        BodySnapshot initial = BodyProtobuf.fromMessage(shape.apply(prepared));
        var source = EnvironmentAbsorption.initialize(EnvironmentSpace.newBuilder().setProtocolVersion(1)
                        .setSourceId(SOURCE).setKind(EnvironmentSpaceKind.REGION_BACKGROUND)
                        .setVolumeBlocks(SOURCE_VOLUME).build(),
                BodyProtobuf.fromInventory(
                        BodyMessages.QiInventory.newBuilder().addEntries(qi(MIST, SOURCE_QI)).build()),
                now, now.toBuilder().setElapsedMillis(10000).build(),
                new EnvironmentQiRules(EnvironmentConfigLoader.defaults()));
        var store = new InMemoryBodyStore(List.of(initial), List.of(), QiInventory.empty(), List.of(source));
        BodyTimeSource<String> clock = (caller, body) -> TimeAdvancePlan.newBuilder().setTarget(now)
                .setMode(TimeAdvanceMode.ACTIVE).setBiologicalSecondsPerSimulationSecond(1).build();
        var handler = new BodyCommandHandler<String>((caller, command) -> Optional.of(caller),
                (caller, command, body) -> true, clock, store, (caller, receipt, before, after) -> { },
                new OrganRecipeCatalog(List.of(recipe)), (caller, body, plan) -> Optional.of(SOURCE));
        var request = RequestEnvelope.newBuilder().setProtocolVersion(1).setRequestId("routed-0")
                .setBodyCommand(BodyCommand.newBuilder().setBodyId(initial.id().value()).setExpectedRevision(0)
                        .setCaptureQi(CaptureQi.newBuilder().setOrganId("filter").setRecipeId(recipe.getId())
                                .setRecipeVersion(1).setSampleVolumeBlocks(1)));
        var response = MeridianProtocol.decodeResponse(
                handler.handleCapturedQi("owner", request.build().toByteArray()));
        assertEquals(response.toString(), FailureCode.FAILURE_CODE_UNSPECIFIED, response.getFailure().getCode());
        assertTrue(response.toString(), response.hasCommandCommitted());
        BodySnapshot after = store.find(initial.id()).orElseThrow();
        assertEquals(initial.totalQi() + PLAIN + NATIVE, after.totalQi(), TOLERANCE);
        return after;
    }
}
