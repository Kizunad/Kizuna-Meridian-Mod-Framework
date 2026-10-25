package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.OpeningRuntime;
import dev.kizuna.meridian.core.Realm;
import dev.kizuna.meridian.protocol.pb.AddonMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.AddonRegistry;
import org.junit.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 标签作为公开定义在注册、持久化与结构更新之间保持同一语义。 */
public class ModuleTagsTest {
    private static final int TAG_LIMIT = 16;
    private static final int TISSUE_TAG_FIELD = 11;
    private static final int ORGAN_TAG_FIELD = 12;
    private static final int MERIDIAN_TAG_FIELD = 14;
    private static final String TEMPLATE_ID = "sample:tendril";
    private static final int TEMPLATE_VERSION = 7;

    @Test
    public void registrationAcceptsOpaqueNamespacedTagsUpToTheLimit() {
        // 标签不需要注册为内容类型，也不隐含对标签命名空间的依赖。
        List<String> tags = IntStream.range(0, TAG_LIMIT)
                .mapToObj(index -> "selector:tag_" + index)
                .toList();
        for (ModuleType type : ModuleType.values()) {
            BodyMessages.BodyStructure structure = taggedStructure(type, tags);
            AddonMessages.AddonRegistration request = registration(structure);
            AddonRegistry registry = AddonRegistry.load(request);
            BodyMessages.BodyTemplate template = registry.findTemplate(TEMPLATE_ID, TEMPLATE_VERSION).orElseThrow();
            assertEquals(structure, template.getStructure());
            byte[] payload = AddonProtobuf.encodeRegistration(request);
            assertEquals(request, AddonProtobuf.decodeRegistration(payload));
        }
    }

    @Test
    public void registrationAndTemplateDecodingRejectInvalidDuplicateAndExcessTags() {
        // 三类模块都必须在直接加载与二进制入口拒绝相同的非法定义。
        List<String> excessive = IntStream.rangeClosed(0, TAG_LIMIT)
                .mapToObj(index -> "selector:tag_" + index)
                .toList();
        List<List<String>> invalidTags = List.of(List.of(""), List.of("unqualified"), List.of("Bad:tag"),
                List.of("sample:tag", "sample:tag"), excessive);
        for (ModuleType type : ModuleType.values()) {
            for (List<String> tags : invalidTags) {
                AddonMessages.AddonRegistration request = registration(taggedStructure(type, tags));
                BodyMessages.BodyTemplate template = request.getBundles(0).getTemplates(0);
                assertThrows(IllegalArgumentException.class, () -> AddonRegistry.load(request));
                assertThrows(IllegalArgumentException.class,
                        () -> AddonProtobuf.decodeRegistration(request.toByteArray()));
                assertThrows(IllegalArgumentException.class,
                        () -> BodyProtobuf.decodeTemplate(template.toByteArray()));
                BodyMessages.BodySnapshot snapshot = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI))
                        .toBuilder()
                        .setStructure(template.getStructure())
                        .build();
                assertThrows(IllegalArgumentException.class,
                        () -> BodyProtobuf.decodeSnapshot(snapshot.toByteArray()));
            }
        }
    }

    @Test
    public void snapshotAndTemplateRoundTripsRetainDefinitionTagsWithoutChangingState() {
        // 空标签兼容已有存档；标签仅随实际构型保存，不复制到组织、器官或经脉状态。
        BodySnapshot original = ProtocolFixtures.snapshot(Realm.TRUE_QI);
        BodyMessages.BodySnapshot message = BodyProtobuf.toMessage(original);
        assertEquals(original, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(original)));
        for (ModuleType type : ModuleType.values()) {
            BodyMessages.BodyStructure structure = taggedStructure(type, List.of("selector:actuator"));
            BodyMessages.BodySnapshot tagged = message.toBuilder().setStructure(structure).build();
            BodySnapshot restored = BodyProtobuf.decodeSnapshot(tagged.toByteArray());
            assertEquals(tagged, BodyProtobuf.toMessage(restored));
            assertEquals(original.tissueStates(), restored.tissueStates());
            assertEquals(original.organStates(), restored.organStates());
            assertEquals(original.meridianStates(), restored.meridianStates());
            BodyMessages.BodyTemplate template = template(structure);
            assertEquals(template, BodyProtobuf.toMessage(BodyProtobuf.decodeTemplate(template.toByteArray())));
        }

        // 标签列表不可被调用方就地修改，防止绕过注册时的数量与唯一性校验。
        assertThrows(UnsupportedOperationException.class,
                () -> original.structure().tissues().get(0).tags().add(new DefinitionId("sample:late")));
    }

    @Test
    public void completingAMeridianPreservesTagsAndUsesNewFieldNumbers() {
        // 稳定体积更新仍保留标签，新增字段不占用已有 reserved 编号。
        assertEquals(TISSUE_TAG_FIELD,
                BodyMessages.TissueDefinition.getDescriptor().findFieldByName("tags").getNumber());
        assertEquals(ORGAN_TAG_FIELD, BodyMessages.OrganDefinition.getDescriptor().findFieldByName("tags").getNumber());
        assertEquals(MERIDIAN_TAG_FIELD,
                BodyMessages.MeridianDefinition.getDescriptor().findFieldByName("tags").getNumber());
        BodyMessages.BodySnapshot source = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI));
        BodyMessages.MeridianOpening unopened = BodyMessages.MeridianOpening.newBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.UNOPENED)
                .setProgress(0)
                .build();
        BodyMessages.MeridianState state = source.getMeridianStates(0).toBuilder().setOpening(unopened).build();
        BodyMessages.BodySnapshot tagged = source.toBuilder()
                .setStructure(taggedStructure(ModuleType.MERIDIAN, List.of("selector:supply")))
                .setMeridianStates(0, state)
                .build();
        BodySnapshot body = BodyProtobuf.fromMessage(tagged);
        Anatomy.MeridianDefinition definition = body.structure().meridians().get(0);
        BodySnapshot completed = OpeningRuntime.complete(body, definition.id(), definition.lumenVolume() + 1);
        assertEquals(definition.tags(), completed.structure().meridians().get(0).tags());
        assertEquals(completed, BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(completed)));
    }

    private static BodyMessages.BodyStructure taggedStructure(ModuleType type, List<String> tags) {
        // 从完整的非人构型出发，每次只改变一个模块的定义标签。
        BodyMessages.BodyStructure source = BodyProtobuf.toMessage(ProtocolFixtures.snapshot(Realm.TRUE_QI))
                .getStructure();
        var builder = source.toBuilder();
        switch (type) {
            case TISSUE -> {
                BodyMessages.TissueDefinition tissue = source.getTissues(0).toBuilder().addAllTags(tags).build();
                builder.setTissues(0, tissue);
            }
            case ORGAN -> {
                BodyMessages.OrganDefinition organ = source.getOrgans(0).toBuilder().addAllTags(tags).build();
                builder.setOrgans(0, organ);
            }
            case MERIDIAN -> {
                BodyMessages.MeridianDefinition meridian = source.getMeridians(0).toBuilder().addAllTags(tags).build();
                builder.setMeridians(0, meridian);
            }
        }
        return builder.build();
    }

    private static BodyMessages.BodyTemplate template(BodyMessages.BodyStructure structure) {
        // 模板使用原构型的精确身份，不包含身体实例的账本。
        BodyMessages.TemplateReference reference = BodyMessages.TemplateReference.newBuilder()
                .setId(TEMPLATE_ID)
                .setVersion(TEMPLATE_VERSION)
                .build();
        return BodyMessages.BodyTemplate.newBuilder()
                .setReference(reference)
                .setStructure(structure)
                .build();
    }

    private static AddonMessages.AddonRegistration registration(BodyMessages.BodyStructure structure) {
        // 注册只声明本构型所需能力，标签自身不要求额外宿主执行器。
        AddonMessages.ContentVersionRange versions = AddonMessages.ContentVersionRange.newBuilder()
                .setMinimum(1)
                .setMaximum(1)
                .build();
        AddonMessages.AddonManifest manifest = AddonMessages.AddonManifest.newBuilder()
                .setNamespace("sample")
                .setContentVersion(1)
                .setApiVersions(versions)
                .addSupportedHosts("test:server")
                .build();
        AddonMessages.ContentSymbol tissueKind = symbol("sample:muscle", AddonMessages.ContentSymbolKind.TISSUE_KIND);
        AddonMessages.ContentSymbol organKind = symbol("sample:filter", AddonMessages.ContentSymbolKind.ORGAN_KIND);
        AddonMessages.AddonBundle bundle = AddonMessages.AddonBundle.newBuilder()
                .setSchemaVersion(1)
                .setManifest(manifest)
                .addSymbols(tissueKind)
                .addSymbols(organKind)
                .addTemplates(template(structure))
                .build();
        AddonMessages.AddonHostProfile host = AddonMessages.AddonHostProfile.newBuilder()
                .setHostId("test:server")
                .setApiVersion(1)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .build();
        return AddonMessages.AddonRegistration.newBuilder()
                .setProtocolVersion(1)
                .setHost(host)
                .addBundles(bundle)
                .build();
    }

    private static AddonMessages.ContentSymbol symbol(String id, AddonMessages.ContentSymbolKind kind) {
        // 类型声明与标签独立，防止把任意标签解释成已登记内容。
        return AddonMessages.ContentSymbol.newBuilder()
                .setId(id)
                .setKind(kind)
                .build();
    }

    private enum ModuleType { TISSUE, ORGAN, MERIDIAN }
}
