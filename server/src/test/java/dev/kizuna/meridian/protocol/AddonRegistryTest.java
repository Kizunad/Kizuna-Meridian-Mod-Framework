package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.AddonMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;
import org.junit.Test;

import java.util.List;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static dev.kizuna.meridian.protocol.pb.AddonMessages.AddonIssueCode.*;
import static dev.kizuna.meridian.protocol.pb.AddonMessages.ContentAvailabilityStatus.*;
import static dev.kizuna.meridian.protocol.pb.AddonMessages.ContentDefinitionKind.*;
import static dev.kizuna.meridian.protocol.pb.AddonMessages.ContentSymbolKind.*;
import static org.junit.Assert.*;

/** 保护多包注册、缺包保存和实际身体执行之间的公开合同。 */
public class AddonRegistryTest {
    @Test
    public void protobufInstallationResolvesCrossPackageContentRegardlessOfOrder() {
        // 生物包和配方包分开交付，使用同一公开格式且保留精确内容修订。
        var species = species();
        var techniques = techniques().toBuilder().addOrganRecipes(recipe().toBuilder().setVersion(2)).build();
        assertEquals(species, AddonProtobuf.decodeBundle(AddonProtobuf.encodeBundle(species)));
        // 固定独立字节样例保护公开字段编号，避免只有同版本自编自解的覆盖。
        assertArrayEquals(HexFormat.of().parseHex("0801120908011203613a621807"),
                AddonProtobuf.encodeReferences(references(reference(BODY_TEMPLATE, "a:b", 7))));
        var request = registration(techniques, species);
        var installed = AddonRegistry.load(AddonProtobuf.decodeRegistration(AddonProtobuf.encodeRegistration(request)));
        var reversed = AddonRegistry.load(registration(species, techniques));
        assertEquals(species.getTemplates(0), installed.findTemplate("sample:tendril", 7).orElseThrow());
        assertEquals(installed.findTemplate("sample:tendril", 7), reversed.findTemplate("sample:tendril", 7));
        assertEquals(recipe(), installed.recipes().find("arts:refine", 1).orElseThrow());
        assertEquals(installed.recipes().find("arts:refine", 2), reversed.recipes().find("arts:refine", 2));
        assertTrue(installed.findTemplate("sample:tendril", 1).isEmpty());
        assertEquals(ORGAN_KIND, installed.findSymbol("sample:filter").orElseThrow().getKind());
        assertEquals(QI_ATTRIBUTE, installed.findSymbol("meridian:none").orElseThrow().getKind());
        assertEquals(QI_ATTRIBUTE, installed.findSymbol("arts:mist").orElseThrow().getKind());

        // 空目录支持基础框架独立加载，不创建默认人体或默认配方。
        var empty = AddonRegistry.load(registration());
        assertTrue(empty.findTemplate("sample:tendril", 7).isEmpty());
        assertTrue(empty.recipes().find("arts:refine", 1).isEmpty());
        var references = references();
        assertEquals(references, AddonProtobuf.decodeReferences(AddonProtobuf.encodeReferences(references)));
        var report = empty.inspectReferences(references);
        assertEquals(report, AddonProtobuf.decodeAvailability(AddonProtobuf.encodeAvailability(report)));
    }

    @Test
    public void actionContentRequiresCapabilityAndReportsMissingVersions() {
        // 功法包可独立登记动作；启动时再验证具体身体模块，注册不依赖某个身体实例。
        var action = BodyMessages.ActionDefinition.newBuilder().setId("arts:hold").setVersion(1)
                .setDisplayName("维持触须").addTissueIds("tendril").build();
        var content = techniques().toBuilder().addActions(action).build();
        rejected(registration(species(), content), CAPABILITY_MISSING, "arts", AddonRegistry.ACTIONS);
        var request = registration(species(), content).toBuilder().setHost(host().addCapabilities(AddonRegistry.ACTIONS)).build();
        var registry = AddonRegistry.load(AddonProtobuf.decodeRegistration(AddonProtobuf.encodeRegistration(request)));
        assertEquals(action, registry.actions().find("arts:hold", 1).orElseThrow());
        var references = references(reference(ACTION, "arts:hold", 1), reference(ACTION, "arts:hold", 2));
        assertEquals(List.of(AVAILABLE, DEFINITION_VERSION_MISSING), registry.inspectReferences(references)
                .getEntriesList().stream().map(AddonMessages.ContentAvailability::getStatus).toList());
        assertEquals(ADDON_MISSING, AddonRegistry.load(registration(species())).inspectReferences(references)
                .getEntries(0).getStatus());
        rejected(request.toBuilder().setBundles(0, content.toBuilder().addActions(action)).setBundles(1, species()).build(),
                DUPLICATE_CONTENT, "arts", "arts:hold");
    }

    @Test
    public void openingRulesResolveActionsAndDiagnoseMissingContent() {
        // 开拓包引用生物包的动作，整批注册与精确引用诊断不依赖包输入顺序。
        var species = species().toBuilder().addActions(openingAction()).build();
        var arts = techniques().toBuilder().addOpeningRules(openingRule()).build();
        var request = registration(arts, species).toBuilder().setHost(host()
                .addCapabilities(AddonRegistry.ACTIONS).addCapabilities(AddonRegistry.OPENING_DEFINITIONS)).build();
        var registry = AddonRegistry.load(AddonProtobuf.decodeRegistration(AddonProtobuf.encodeRegistration(request)));
        assertEquals(openingRule(), registry.openings().find("arts:open_ring", 1).orElseThrow());
        assertEquals(registry.openings().find("arts:open_ring", 1), AddonRegistry.load(request.toBuilder()
                .clearBundles().addBundles(species).addBundles(arts).build()).openings().find("arts:open_ring", 1));
        assertTrue(registry.openings().find("arts:open_ring", 2).isEmpty());
        var references = references(reference(MERIDIAN_OPENING_RULE, "arts:open_ring", 1),
                reference(MERIDIAN_OPENING_RULE, "arts:open_ring", 2), reference(MERIDIAN_OPENING_RULE, "arts:absent", 1));
        assertEquals(List.of(AVAILABLE, DEFINITION_VERSION_MISSING, DEFINITION_MISSING), registry.inspectReferences(references)
                .getEntriesList().stream().map(AddonMessages.ContentAvailability::getStatus).toList());
        assertEquals(ADDON_MISSING, AddonRegistry.load(registration()).inspectReferences(references).getEntries(0).getStatus());
    }

    @Test
    public void openingRegistrationChecksCapabilitiesOwnershipAndExactDirectDependencies() {
        // 定义支持独立于进度执行；能力缺失和重复修订都必须在发布目录前拒绝。
        var species = species().toBuilder().addActions(openingAction()).build();
        var arts = techniques().toBuilder().addOpeningRules(openingRule()).build();
        var host = host().addCapabilities(AddonRegistry.ACTIONS);
        rejected(registration(species, arts).toBuilder().setHost(host).build(),
                CAPABILITY_MISSING, "arts", AddonRegistry.OPENING_DEFINITIONS);
        var request = registration(species, arts).toBuilder()
                .setHost(host.addCapabilities(AddonRegistry.OPENING_DEFINITIONS)).build();
        rejected(request.toBuilder().setBundles(1, arts.toBuilder().addOpeningRules(openingRule())).build(),
                DUPLICATE_CONTENT, "arts", "arts:open_ring");
        rejected(request.toBuilder().setBundles(1, arts.toBuilder().setOpeningRules(0,
                        openingRule().toBuilder().setId("sample:open_ring"))).build(),
                NAMESPACE_MISMATCH, "arts", "sample:open_ring");

        // 动作身份、类型和修订分别诊断，自定义门槛属性也须登记正确类型。
        rejected(request.toBuilder().setBundles(1, arts.toBuilder().setOpeningRules(0,
                        openingRule().toBuilder().setActionId("sample:absent"))).build(),
                DEFINITION_REFERENCE_MISSING, "arts", "sample:absent");
        rejected(request.toBuilder().setBundles(1, arts.toBuilder().setOpeningRules(0,
                        openingRule().toBuilder().setActionId("sample:tendril"))).build(),
                DEFINITION_REFERENCE_KIND_MISMATCH, "arts", "sample:tendril");
        rejected(request.toBuilder().setBundles(1, arts.toBuilder().setOpeningRules(0,
                        openingRule().toBuilder().setActionId("sample:muscle"))).build(),
                DEFINITION_REFERENCE_KIND_MISMATCH, "arts", "sample:muscle");
        rejected(request.toBuilder().setBundles(1, arts.toBuilder().setOpeningRules(0,
                        openingRule().toBuilder().setActionVersion(2))).build(),
                DEFINITION_REFERENCE_VERSION_MISSING, "arts", "sample:focus");
        // 即使依赖通过中间包带入，也不能隐式引用动作；去掉配方隔离这一检查。
        var middle = bundle("middle").toBuilder().setManifest(manifest("middle").addDependencies(dependency("sample"))).build();
        var indirect = bundle("arts").toBuilder().setManifest(manifest("arts").addDependencies(dependency("middle")))
                .addOpeningRules(openingRule()).build();
        rejected(request.toBuilder().clearBundles().addBundles(species).addBundles(middle).addBundles(indirect).build(),
                UNDECLARED_DEPENDENCY, "arts", "sample:focus");
    }

    @Test
    public void dependenciesVersionsAndHostCapabilitiesMustAllBeSatisfied() {
        // 缺直接依赖和版本不符整批拒绝，诊断提供包和关联目标。
        rejected(registration(techniques()), DEPENDENCY_MISSING, "arts", "sample");
        var wrongVersion = species().toBuilder().setManifest(species().getManifest().toBuilder().setContentVersion(2)).build();
        rejected(registration(wrongVersion, techniques()), DEPENDENCY_VERSION_MISMATCH, "arts", "sample");
        var noDeclaration = techniques().toBuilder().setManifest(techniques().getManifest().toBuilder().clearDependencies()).build();
        rejected(registration(species(), noDeclaration), UNDECLARED_DEPENDENCY, "arts", "sample:filter");

        // 即使另一个依赖间接安装了目标包，当前包也必须声明自己使用的内容来源。
        var middle = bundle("middle").toBuilder().setManifest(manifest("middle").addDependencies(dependency("sample"))).build();
        var indirect = techniques().toBuilder().setManifest(manifest("arts").addDependencies(dependency("middle"))).build();
        rejected(registration(species(), middle, indirect), UNDECLARED_DEPENDENCY, "arts", "sample:filter");

        var unsupportedApi = species().toBuilder().setManifest(manifest("sample").setApiVersions(range(2, 3))).build();
        rejected(registration(unsupportedApi), API_INCOMPATIBLE, "sample", "1");
        var unsupportedHost = registration(species()).toBuilder().setHost(host().setHostId("test:other")).build();
        rejected(unsupportedHost, HOST_UNSUPPORTED, "sample", "test:other");
        var required = bundle("extension").toBuilder().setManifest(manifest("extension")
                .addRequiredCapabilities("test:custom_rule")).build();
        rejected(registration(required), CAPABILITY_MISSING, "extension", "test:custom_rule");

        // 内容本身的能力需求也必须满足，不能通过清单漏写来绕过。
        var noRecipes = registration(species(), techniques()).toBuilder().setHost(host().clearCapabilities()
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)).build();
        rejected(noRecipes, CAPABILITY_MISSING, "arts", AddonRegistry.ORGAN_RECIPES);
        var template = species().getTemplates(0);
        var withIntake = species().toBuilder().setTemplates(0, template.toBuilder().setStructure(template.getStructure().toBuilder()
                .setOrgans(0, template.getStructure().getOrgans(0).toBuilder().setIntake(
                        BodyMessages.OrganIntakeCapability.newBuilder().setMaxQiPerSecond(1))))).build();
        var withoutIntakeCapability = registration(withIntake).toBuilder().setHost(host().clearCapabilities()
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)).build();
        rejected(withoutIntakeCapability, CAPABILITY_MISSING, "sample", AddonRegistry.ORGAN_INTAKE);
        var withRecovery = species().toBuilder().setTemplates(0, template.toBuilder().setStructure(template.getStructure().toBuilder()
                .setTissues(0, template.getStructure().getTissues(0).toBuilder().setRecoveryHalfLifeSeconds(2)))).build();
        rejected(registration(withRecovery), CAPABILITY_MISSING, "sample", AddonRegistry.TISSUE_RECOVERY);
        var supportedRecovery = registration(withRecovery).toBuilder()
                .setHost(host().addCapabilities(AddonRegistry.TISSUE_RECOVERY)).build();
        assertNotNull(AddonRegistry.load(supportedRecovery));

        // 模板实际声明自然流动时，宿主必须支持它；清单漏报不能静默忽略生理规则。
        var firstMeridian = template.getStructure().getMeridians(0);
        var secondMeridian = firstMeridian.toBuilder().setId("second").clearPorts()
                .addPorts(BodyMessages.Port.newBuilder().setId("second.in")
                        .setDirection(BodyMessages.PortDirection.INPUT));
        var passiveConnection = BodyMessages.Connection.newBuilder().setId("passive.link")
                .setFrom("channel.out").setTo("second.in").setResistance(0).setDistance(.1)
                .setPassiveQiFlow(BodyMessages.PassiveQiFlow.newBuilder().setThroughputPerSecond(.5)
                        .setMaximumBatchQi(.25).setMinimumBatchQi(.001));
        var passiveContent = species().toBuilder().setTemplates(0, template.toBuilder().setStructure(
                template.getStructure().toBuilder().addMeridians(secondMeridian).addConnections(passiveConnection))).build();
        rejected(registration(passiveContent), CAPABILITY_MISSING, "sample", AddonRegistry.PASSIVE_QI_FLOW);
        assertNotNull(AddonRegistry.load(registration(passiveContent).toBuilder()
                .setHost(host().addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)).build()));
    }

    @Test
    public void cyclesAndIdentityConflictsCannotPublishPartialContent() {
        // 已发布目录独立于失败候选；循环诊断定位到环内的包，而不是其下游。
        var installed = AddonRegistry.load(registration(species(), techniques()));
        var a = bundle("a").toBuilder().setManifest(manifest("a").addDependencies(dependency("b"))).build();
        var b = bundle("b").toBuilder().setManifest(manifest("b").addDependencies(dependency("c"))).build();
        var c = bundle("c").toBuilder().setManifest(manifest("c").addDependencies(dependency("b"))).build();
        rejected(registration(a, b, c), DEPENDENCY_CYCLE, "b", "b");
        var self = bundle("a").toBuilder().setManifest(manifest("a").addDependencies(dependency("a"))).build();
        rejected(registration(self), DEPENDENCY_CYCLE, "a", "a");
        rejected(registration(species(), species()), DUPLICATE_ADDON, "sample", "sample");
        var duplicateTemplate = species().toBuilder().addTemplates(species().getTemplates(0)).build();
        rejected(registration(duplicateTemplate), DUPLICATE_CONTENT, "sample", "sample:tendril");
        var duplicateRecipe = techniques().toBuilder().addOrganRecipes(recipe()).build();
        rejected(registration(species(), duplicateRecipe), DUPLICATE_CONTENT, "arts", "arts:refine");
        var conflictingKind = species().toBuilder().addSymbols(symbol("sample:tendril", TISSUE_KIND)).build();
        rejected(registration(conflictingKind), DUPLICATE_CONTENT, "sample", "sample:tendril");
        var conflictingSymbol = species().toBuilder().addSymbols(symbol("sample:filter", ORGAN_KIND)).build();
        rejected(registration(conflictingSymbol), DUPLICATE_CONTENT, "sample", "sample:filter");
        var stolen = bundle("other").toBuilder().addSymbols(symbol("sample:filter", ORGAN_KIND)).build();
        rejected(registration(stolen), NAMESPACE_MISMATCH, "other", "sample:filter");
        assertEquals(recipe(), installed.recipes().find("arts:refine", 1).orElseThrow());
        assertEquals(species().getTemplates(0), installed.findTemplate("sample:tendril", 7).orElseThrow());
    }

    @Test
    public void referencedKindsAndQiAttributesMustBeRegisteredWithTheCorrectType() {
        // 格式合法的 ID 仍可能悬空；组织、器官和属性的身份不能相互冒充。
        var missing = species().toBuilder().removeSymbols(1).build();
        rejected(registration(missing), SYMBOL_MISSING, "sample", "sample:filter");
        var wrongKind = species().toBuilder().setSymbols(1, symbol("sample:filter", TISSUE_KIND)).build();
        rejected(registration(wrongKind), SYMBOL_KIND_MISMATCH, "sample", "sample:filter");
        var customRecipe = recipe().toBuilder().setOutput(qi("arts:mist", 3)).build();
        var custom = techniques().toBuilder().setOrganRecipes(0, customRecipe).build();
        assertEquals(customRecipe, AddonRegistry.load(registration(species(), custom)).recipes()
                .find("arts:refine", 1).orElseThrow());
        rejected(registration(species(), custom.toBuilder().clearSymbols().build()), SYMBOL_MISSING, "arts", "arts:mist");
        var inventedBuiltin = techniques().toBuilder().setOrganRecipes(0,
                recipe().toBuilder().setInput(qi("meridian:invented", 4))).build();
        rejected(registration(species(), inventedBuiltin), SYMBOL_MISSING, "arts", "meridian:invented");
    }

    @Test
    public void missingReferencesRetainExactIdentityAndRecoverWhenTheSameContentReturns() {
        // 可用性是独立报告，不删宿主保存的引用，也不自动升级到包里较新的定义。
        var saved = references(reference(BODY_TEMPLATE, "sample:tendril", 7),
                reference(ORGAN_RECIPE, "arts:refine", 1), reference(ORGAN_RECIPE, "arts:refine", 2),
                reference(ORGAN_RECIPE, "arts:absent", 1), reference(BODY_TEMPLATE, "gone:body", 1));
        byte[] original = AddonProtobuf.encodeReferences(saved);
        var report = AddonRegistry.load(registration(species(), techniques())).inspectReferences(saved);
        assertEquals(List.of(AVAILABLE, AVAILABLE, DEFINITION_VERSION_MISSING, DEFINITION_MISSING, ADDON_MISSING),
                report.getEntriesList().stream().map(AddonMessages.ContentAvailability::getStatus).toList());
        assertEquals(saved.getReferencesList(), report.getEntriesList().stream()
                .map(AddonMessages.ContentAvailability::getReference).toList());
        assertEquals(report, AddonProtobuf.decodeAvailability(AddonProtobuf.encodeAvailability(report)));
        var removed = AddonRegistry.load(registration(species())).inspectReferences(saved);
        assertEquals(ADDON_MISSING, removed.getEntries(1).getStatus());
        var upgraded = techniques().toBuilder().setManifest(techniques().getManifest().toBuilder().setContentVersion(2))
                .setOrganRecipes(0, recipe().toBuilder().setVersion(2)).build();
        assertEquals(DEFINITION_VERSION_MISSING, AddonRegistry.load(registration(species(), upgraded))
                .inspectReferences(saved).getEntries(1).getStatus());
        assertEquals(report, AddonRegistry.load(registration(species(), techniques())).inspectReferences(saved));
        assertArrayEquals(original, AddonProtobuf.encodeReferences(saved));
    }

    @Test
    public void installedRecipesUseAuthorizedCommandsAndSavedProcessingSurvivesRemoval() {
        // 从完整包目录启动真实命令，再卸载全部包并恢复：出生模板和运行中的固定配方不重套。
        var registry = AddonRegistry.load(registration(species(), techniques()));
        var initial = initialBody();
        var store = new InMemoryBodyStore(List.of(initial), List.of());
        var now = new AtomicLong(1000);
        byte[] start = command("start", 0, MeridianMessages.BodyCommand.newBuilder().setStartOrganRecipe(
                MeridianMessages.StartOrganRecipe.newBuilder().setOrganId("filter").setRecipeId("arts:refine").setRecipeVersion(1)));
        var denied = handler(store, registry, now, false);
        assertEquals(MeridianMessages.FailureCode.ACCESS_DENIED,
                MeridianProtocol.decodeResponse(denied.handle("host", start)).getFailure().getCode());
        assertEquals(initial, store.find(initial.id()).orElseThrow());
        byte[] response = handler(store, registry, now, true).handle("host", start);
        assertTrue(MeridianProtocol.decodeResponse(response).hasCommandCommitted());
        var running = store.find(initial.id()).orElseThrow();
        var receipt = store.transact(initial.id(), transaction -> transaction.receipt("host", "start").orElseThrow());
        var restoredBody = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(running));
        var restored = new InMemoryBodyStore(List.of(restoredBody), List.of(receipt));
        var empty = AddonRegistry.load(registration());
        assertEquals(ADDON_MISSING, empty.inspectReferences(references(reference(BODY_TEMPLATE, "sample:tendril", 7)))
                .getEntries(0).getStatus());
        var withoutContent = handler(restored, empty, now, true);
        assertArrayEquals(response, withoutContent.handle("host", start));

        // 运行事实内嵌完整配方，完成及回环境守恒；新调用则必须找到已安装的精确修订。
        now.set(3000);
        byte[] finish = command("finish", 1, MeridianMessages.BodyCommand.newBuilder()
                .setAdvanceTime(MeridianMessages.AdvanceTime.getDefaultInstance()));
        assertTrue(MeridianProtocol.decodeResponse(withoutContent.handle("host", finish)).hasCommandCommitted());
        var after = restored.find(initial.id()).orElseThrow();
        assertNull(after.organStates().get(0).process());
        assertEquals(initial.structure(), after.structure());
        assertEquals(initial.template(), after.template());
        assertEquals(initial.totalQi(), after.totalQi() + restored.environmentQi().totalQi(), 0);
        byte[] restart = command("restart", 2, MeridianMessages.BodyCommand.newBuilder().setStartOrganRecipe(
                MeridianMessages.StartOrganRecipe.newBuilder().setOrganId("filter").setRecipeId("arts:refine").setRecipeVersion(1)));
        assertEquals(MeridianMessages.FailureCode.RECIPE_NOT_FOUND,
                MeridianProtocol.decodeResponse(withoutContent.handle("host", restart)).getFailure().getCode());
        assertEquals(after, restored.find(initial.id()).orElseThrow());
    }

    @Test
    public void protocolBoundaryRejectsIncompleteUnknownAndInvalidDeclarations() {
        // 生成消息本身不代表合法清单；字节入口和直接注册都执行完整格式校验。
        for (var invalid : List.of(species().toBuilder().setSchemaVersion(2).build(),
                species().toBuilder().clearManifest().build(),
                species().toBuilder().setManifest(manifest("meridian")).build(),
                species().toBuilder().setManifest(manifest("sample").setContentVersion(0)).build(),
                species().toBuilder().setManifest(manifest("sample").setApiVersions(range(2, 1))).build(),
                species().toBuilder().setManifest(manifest("sample").clearSupportedHosts()).build(),
                species().toBuilder().setManifest(manifest("sample").addSupportedHosts("test:server")).build(),
                species().toBuilder().setManifest(manifest("sample").addDependencies(dependency("base"))
                        .addDependencies(dependency("base"))).build(),
                species().toBuilder().setSymbols(0, symbol("sample:muscle", TISSUE_KIND).toBuilder().setKindValue(99)).build())) {
            assertThrows(IllegalArgumentException.class, () -> AddonProtobuf.decodeBundle(invalid.toByteArray()));
            assertThrows(IllegalArgumentException.class, () -> AddonRegistry.load(registration(invalid)));
        }
        var invalidInput = techniques().toBuilder().setOrganRecipes(0, recipe().toBuilder().clearInput()).build();
        assertThrows(IllegalArgumentException.class, () -> AddonProtobuf.encodeBundle(invalidInput));
        var unknown = species().toBuilder().setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder()
                .addField(100, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build()).build();
        assertThrows(IllegalArgumentException.class, () -> AddonProtobuf.decodeBundle(unknown.toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> AddonProtobuf.decodeRegistration(
                registration(species()).toBuilder().setProtocolVersion(2).build().toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> AddonProtobuf.encodeReferences(
                references(reference(ORGAN_RECIPE, "arts:refine", 0))));
    }

    private static AddonMessages.AddonBundle species() {
        // 非人构型只存在于测试内容包，公开框架不内置此种族。
        var body = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI));
        return bundle("sample").toBuilder().addSymbols(symbol("sample:muscle", TISSUE_KIND))
                .addSymbols(symbol("sample:filter", ORGAN_KIND)).addTemplates(BodyMessages.BodyTemplate.newBuilder()
                        .setReference(body.getTemplate()).setStructure(body.getStructure())).build();
    }

    private static AddonMessages.AddonBundle techniques() {
        // 配方包依赖生物包提供的器官类型，不要求把该器官定义复制进来。
        return bundle("arts").toBuilder().setManifest(manifest("arts").addDependencies(dependency("sample")))
                .addSymbols(symbol("arts:mist", QI_ATTRIBUTE)).addOrganRecipes(recipe()).build();
    }

    private static BodyMessages.OrganRecipe recipe() {
        // 数值只用于验证守恒和跨包调用，不是官方人体的炼化配置。
        return BodyMessages.OrganRecipe.newBuilder().setId("arts:refine").setVersion(1).setOrganKind("sample:filter")
                .setInput(qi("meridian:wood", 4)).setOutput(qi("meridian:none", 3)).setDurationMillis(2000)
                .setMinimumRealm(BodyMessages.Realm.WANDERING_QI).build();
    }

    private static BodyMessages.ActionDefinition openingAction() {
        // 生物包为开拓提供占用内容；动作身份可由另一个附属规则精确引用。
        return BodyMessages.ActionDefinition.newBuilder().setId("sample:focus").setVersion(1)
                .setDisplayName("环脉引导").addTissueIds("tendril").addOrganIds("filter").build();
    }

    private static BodyMessages.MeridianOpeningRule openingRule() {
        // 首脉前置为空，门槛只读取目标内腔，不另外复制一份待开拓真元。
        return BodyMessages.MeridianOpeningRule.newBuilder().setId("arts:open_ring").setVersion(1)
                .setDisplayName("环脉适应").setTargetMeridianId("channel")
                .setPolicyId("sample:opening").setPolicyConfig(SampleOpeningPolicy.configuration(5000, false, false))
                .setActionId("sample:focus").setActionVersion(1).build();
    }

    private static BodyMessages.QiInventory qi(String attribute, double amount) {
        // 输入与输出都采用现有账本消息，避免建立附属包自己的余额格式。
        return BodyMessages.QiInventory.newBuilder().addEntries(BodyMessages.Qi.newBuilder()
                .setDensity(.01).setAttribute(attribute).setAmount(amount)).build();
    }

    private static AddonMessages.AddonBundle bundle(String namespace) {
        // 纯交互包可没有身体定义，但仍须显式提供清单和支持宿主。
        return AddonMessages.AddonBundle.newBuilder().setSchemaVersion(1).setManifest(manifest(namespace)).build();
    }

    private static AddonMessages.AddonManifest.Builder manifest(String namespace) {
        // 每个测试包独占命名空间，API 范围和包修订各自记录。
        return AddonMessages.AddonManifest.newBuilder().setNamespace(namespace).setContentVersion(1)
                .setApiVersions(range(1, 1)).addSupportedHosts("test:server");
    }

    private static AddonMessages.ContentVersionRange range(int minimum, int maximum) {
        // 闭区间使边界兼容测试明确，不借助字符串版本比较。
        return AddonMessages.ContentVersionRange.newBuilder().setMinimum(minimum).setMaximum(maximum).build();
    }

    private static AddonMessages.AddonDependency dependency(String namespace) {
        // 默认仅接受依赖包第一修订，其他修订必须由内容作者明确允许。
        return AddonMessages.AddonDependency.newBuilder().setNamespace(namespace).setVersions(range(1, 1)).build();
    }

    private static AddonMessages.ContentSymbol symbol(String id, AddonMessages.ContentSymbolKind kind) {
        // 类型登记只声明身份，不夹带某个角色的实际器官或组织状态。
        return AddonMessages.ContentSymbol.newBuilder().setId(id).setKind(kind).build();
    }

    private static AddonMessages.AddonHostProfile.Builder host() {
        // 使用测试宿主 ID，避免声称尚未接入的 Fabric 或 Bong 运行时已得到验证。
        return AddonMessages.AddonHostProfile.newBuilder().setHostId("test:server").setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES).addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE);
    }

    private static AddonMessages.AddonRegistration registration(AddonMessages.AddonBundle... bundles) {
        // 同一批包必须原子发布，数组顺序只用于验证不会影响加载结果。
        return AddonMessages.AddonRegistration.newBuilder().setProtocolVersion(1).setHost(host())
                .addAllBundles(List.of(bundles)).build();
    }

    private static AddonMessages.ContentReference reference(AddonMessages.ContentDefinitionKind kind, String id, int version) {
        // 宿主存档保存精确引用，与当前安装情况无关。
        return AddonMessages.ContentReference.newBuilder().setKind(kind).setId(id).setVersion(version).build();
    }

    private static AddonMessages.ContentReferences references(AddonMessages.ContentReference... references) {
        // 空列表也携带协议版本，仍可稳定编码为非空消息。
        return AddonMessages.ContentReferences.newBuilder().setProtocolVersion(1).addAllReferences(List.of(references)).build();
    }

    private static void rejected(AddonMessages.AddonRegistration registration, AddonMessages.AddonIssueCode code,
                                 String namespace, String subject) {
        // 对外错误同时核验类别与定位信息，并证明可以使用 Protobuf 交付给宿主。
        var issue = assertThrows(MeridianErrors.AddonRejected.class, () -> AddonRegistry.load(registration)).issue();
        assertEquals(code, issue.getCode());
        assertEquals(namespace, issue.getAddonNamespace());
        assertEquals(subject, issue.getSubject());
        assertEquals(issue, AddonProtobuf.decodeIssue(AddonProtobuf.encodeIssue(issue)));
    }

    private static BodySnapshot initialBody() {
        // 复用非人构型，只重设本测试需要的时间和原料，保留快照中的其他实际事实。
        var source = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI));
        return BodyProtobuf.fromMessage(source.toBuilder().setRevision(0).clearTransits()
                .setSettledAt(source.getSettledAt().toBuilder().setElapsedMillis(1000))
                .setOrganStates(0, source.getOrganStates(0).toBuilder().setBuffer(qi("meridian:wood", 4))).build());
    }

    private static BodyCommandHandler<String> handler(InMemoryBodyStore store, AddonRegistry registry,
                                                       AtomicLong now, boolean allowed) {
        // 安装定义不授予执行权限，现有授权/私有策略/可信时间仍参与每笔身体事务。
        return new BodyCommandHandler<>((context, command) -> Optional.of(context), (context, command, body) -> allowed,
                (context, body) -> MeridianMessages.TimeAdvancePlan.newBuilder()
                        .setTarget(BodyProtobuf.toTime(body.settledAt()).toBuilder().setElapsedMillis(now.get()))
                        .setBiologicalSecondsPerSimulationSecond(1).setMode(MeridianMessages.TimeAdvanceMode.ACTIVE).build(),
                store, (context, receipt, before, after) -> { }, registry.recipes());
    }

    private static byte[] command(String requestId, long revision, MeridianMessages.BodyCommand.Builder operation) {
        // 模拟客户端只提交配方身份或时间意图，不能上传附属清单覆盖宿主目录。
        return MeridianProtocol.encodeRequest(MeridianMessages.RequestEnvelope.newBuilder().setProtocolVersion(1)
                .setRequestId(requestId).setBodyCommand(operation.setBodyId("creature-1").setExpectedRevision(revision)).build());
    }
}
