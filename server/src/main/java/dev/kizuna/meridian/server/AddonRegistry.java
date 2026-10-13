package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.AddonMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static dev.kizuna.meridian.protocol.pb.AddonMessages.AddonIssueCode.*;
import static dev.kizuna.meridian.protocol.pb.AddonMessages.ContentAvailabilityStatus.*;
import static dev.kizuna.meridian.protocol.pb.AddonMessages.ContentDefinitionKind.*;
import static dev.kizuna.meridian.protocol.pb.AddonMessages.ContentSymbolKind.*;

/** 整批校验后创建的不可变内容目录；定义与权威身体账本分别持有。 */
public final class AddonRegistry {
    public static final String BODY_TEMPLATES = "meridian:body_templates_v1";
    public static final String ORGAN_RECIPES = "meridian:organ_recipes_v1";
    public static final String ORGAN_INTAKE = "meridian:organ_intake_v1";

    private final Map<String, AddonMessages.AddonBundle> bundles;
    private final Map<String, AddonMessages.ContentSymbol> symbols;
    private final Map<String, AddonMessages.ContentDefinitionKind> definitionKinds;
    private final Map<DefinitionKey, BodyMessages.BodyTemplate> templates;
    private final OrganRecipeCatalog recipes;

    private AddonRegistry(Map<String, AddonMessages.AddonBundle> bundles,
                          Map<String, AddonMessages.ContentSymbol> symbols,
                          Map<String, AddonMessages.ContentDefinitionKind> definitionKinds,
                          Map<DefinitionKey, BodyMessages.BodyTemplate> templates,
                          OrganRecipeCatalog recipes) {
        // 只有全部校验完成才复制候选，调用方无法拿到或改写半成品目录。
        this.bundles = Map.copyOf(bundles);
        this.symbols = Map.copyOf(symbols);
        this.definitionKinds = Map.copyOf(definitionKinds);
        this.templates = Map.copyOf(templates);
        this.recipes = recipes;
    }

    public static AddonRegistry load(AddonMessages.AddonRegistration registration) {
        // 读取并校验整批格式；同一命名空间不能由加载顺序决定最终归属。
        MeridianErrors.required(registration, MeridianErrors.Addon.REGISTRATION_REQUIRED);
        AddonProtobuf.validateRegistration(registration);
        var bundles = new TreeMap<String, AddonMessages.AddonBundle>();
        for (var bundle : registration.getBundlesList()) {
            String namespace = bundle.getManifest().getNamespace();
            if (bundles.putIfAbsent(namespace, bundle) != null) {
                throw MeridianErrors.rejectAddon(DUPLICATE_ADDON, namespace, namespace);
            }
        }
        AddonDependencies.validate(bundles, registration.getHost());

        // 先收集所有定义身份，再验证引用，使正确结果与传入包的先后顺序无关。
        var symbols = builtinAttributes();
        var definitionKinds = new HashMap<String, AddonMessages.ContentDefinitionKind>();
        var templates = new HashMap<DefinitionKey, BodyMessages.BodyTemplate>();
        var recipes = new HashMap<DefinitionKey, BodyMessages.OrganRecipe>();
        for (var bundle : bundles.values()) {
            String namespace = bundle.getManifest().getNamespace();
            for (var symbol : bundle.getSymbolsList()) {
                requireOwnership(namespace, symbol.getId());
                if (definitionKinds.containsKey(symbol.getId()) || symbols.putIfAbsent(symbol.getId(), symbol) != null) {
                    throw MeridianErrors.rejectAddon(DUPLICATE_CONTENT, namespace, symbol.getId());
                }
            }
            for (var template : bundle.getTemplatesList()) {
                var reference = template.getReference();
                claimDefinition(namespace, reference.getId(), BODY_TEMPLATE, symbols, definitionKinds);
                var key = new DefinitionKey(reference.getId(), reference.getVersion());
                if (templates.putIfAbsent(key, template) != null) {
                    throw MeridianErrors.rejectAddon(DUPLICATE_CONTENT, namespace, reference.getId());
                }
            }
            for (var recipe : bundle.getOrganRecipesList()) {
                claimDefinition(namespace, recipe.getId(), ORGAN_RECIPE, symbols, definitionKinds);
                var key = new DefinitionKey(recipe.getId(), recipe.getVersion());
                if (recipes.putIfAbsent(key, recipe) != null) {
                    throw MeridianErrors.rejectAddon(DUPLICATE_CONTENT, namespace, recipe.getId());
                }
            }
        }

        // 组织、器官和属性均按登记种类解析，声明了依赖仍不能引用不存在的内容。
        for (var bundle : bundles.values()) {
            var manifest = bundle.getManifest();
            for (var template : bundle.getTemplatesList()) {
                for (var tissue : template.getStructure().getTissuesList()) {
                    requireSymbol(manifest, tissue.getKind(), TISSUE_KIND, symbols);
                }
                for (var organ : template.getStructure().getOrgansList()) {
                    requireSymbol(manifest, organ.getKind(), ORGAN_KIND, symbols);
                }
            }
            for (var recipe : bundle.getOrganRecipesList()) {
                requireSymbol(manifest, recipe.getOrganKind(), ORGAN_KIND, symbols);
                for (var qi : recipe.getInput().getEntriesList()) {
                    requireSymbol(manifest, qi.getAttribute(), QI_ATTRIBUTE, symbols);
                }
                for (var qi : recipe.getOutput().getEntriesList()) {
                    requireSymbol(manifest, qi.getAttribute(), QI_ATTRIBUTE, symbols);
                }
            }
        }

        // 配方投影沿用已有执行入口；此处成功也不触碰宿主存档或正在使用的旧注册表。
        var recipeCatalog = new OrganRecipeCatalog(List.copyOf(recipes.values()));
        return new AddonRegistry(bundles, symbols, definitionKinds, templates, recipeCatalog);
    }

    public Optional<BodyMessages.BodyTemplate> findTemplate(String id, int version) {
        // 只有精确版本存在才能用于新建身体；已有快照无需重新读取出生模板。
        var reference = AddonMessages.ContentReference.newBuilder()
                .setKind(BODY_TEMPLATE).setId(id).setVersion(version).build();
        AddonProtobuf.validateReference(reference);
        return Optional.ofNullable(templates.get(new DefinitionKey(id, version)));
    }

    public Optional<AddonMessages.ContentSymbol> findSymbol(String id) {
        // 登记类型只是公开内容身份，实际能力以实例构型的参数为准。
        new DefinitionId(id);
        return Optional.ofNullable(symbols.get(id));
    }

    public OrganRecipeCatalog recipes() {
        // 返回不可变目录，直接传入 BodyCommandHandler 的配方参数。
        return recipes;
    }

    public AddonMessages.ContentAvailabilityReport inspectReferences(AddonMessages.ContentReferences references) {
        // 保留每个原始引用的顺序；缺失时只报告原因，不迁移、删除或替换存档。
        AddonProtobuf.validateReferences(references);
        var report = AddonMessages.ContentAvailabilityReport.newBuilder().setProtocolVersion(MeridianProtocol.VERSION);
        for (var reference : references.getReferencesList()) {
            AddonMessages.ContentAvailabilityStatus status = availability(reference);
            report.addEntries(AddonMessages.ContentAvailability.newBuilder().setReference(reference).setStatus(status));
        }
        var result = report.build();
        AddonProtobuf.encodeAvailability(result);
        return result;
    }

    private AddonMessages.ContentAvailabilityStatus availability(AddonMessages.ContentReference reference) {
        // 包不存在、定义种类不存在和精确版本缺失分开报告，便于决定恢复哪个依赖。
        String namespace = AddonProtobuf.namespaceOf(reference.getId());
        if (!bundles.containsKey(namespace)) {
            return ADDON_MISSING;
        }
        if (definitionKinds.get(reference.getId()) != reference.getKind()) {
            return DEFINITION_MISSING;
        }
        boolean installed = switch (reference.getKind()) {
            case BODY_TEMPLATE -> templates.containsKey(new DefinitionKey(reference.getId(), reference.getVersion()));
            case ORGAN_RECIPE -> recipes.find(reference.getId(), reference.getVersion()).isPresent();
            default -> false; // 公开入口已拒绝未知枚举，保留穷尽分支满足生成枚举约束。
        };
        if (!installed) {
            return DEFINITION_VERSION_MISSING;
        }
        return AVAILABLE;
    }

    private static void requireOwnership(String namespace, String id) {
        // 人体、官方功法和第三方包都只能发布自己命名空间中的内容。
        if (!namespace.equals(AddonProtobuf.namespaceOf(id))) {
            throw MeridianErrors.rejectAddon(NAMESPACE_MISMATCH, namespace, id);
        }
    }

    private static void claimDefinition(String namespace, String id, AddonMessages.ContentDefinitionKind kind,
                                        Map<String, AddonMessages.ContentSymbol> symbols,
                                        Map<String, AddonMessages.ContentDefinitionKind> definitionKinds) {
        // 相同定义可以保留多个修订，同一 ID 则不能同时表示模板、配方或类型符号。
        requireOwnership(namespace, id);
        AddonMessages.ContentDefinitionKind previous = definitionKinds.putIfAbsent(id, kind);
        if (symbols.containsKey(id) || (previous != null && previous != kind)) {
            throw MeridianErrors.rejectAddon(DUPLICATE_CONTENT, namespace, id);
        }
    }

    private static void requireSymbol(AddonMessages.AddonManifest manifest, String id,
                                       AddonMessages.ContentSymbolKind expected,
                                       Map<String, AddonMessages.ContentSymbol> symbols) {
        // 外部类型必须是显式直接依赖；框架提供的无属性和五行身份不要求安装附属包。
        String owner = AddonProtobuf.namespaceOf(id);
        String namespace = manifest.getNamespace();
        boolean declared = owner.equals(namespace) || owner.equals("meridian")
                || manifest.getDependenciesList().stream().anyMatch(dependency -> dependency.getNamespace().equals(owner));
        if (!declared) {
            throw MeridianErrors.rejectAddon(UNDECLARED_DEPENDENCY, namespace, id);
        }
        var symbol = symbols.get(id);
        if (symbol == null) {
            throw MeridianErrors.rejectAddon(SYMBOL_MISSING, namespace, id);
        }
        if (symbol.getKind() != expected) {
            throw MeridianErrors.rejectAddon(SYMBOL_KIND_MISMATCH, namespace, id);
        }
    }

    private static Map<String, AddonMessages.ContentSymbol> builtinAttributes() {
        // 这些身份已是核心账本与环境规则的一部分，不包含任何具体生物模板或器官。
        var symbols = new HashMap<String, AddonMessages.ContentSymbol>();
        for (var attribute : List.of(Qi.QiAttribute.NONE, Qi.QiAttribute.METAL, Qi.QiAttribute.WOOD,
                Qi.QiAttribute.WATER, Qi.QiAttribute.FIRE, Qi.QiAttribute.EARTH)) {
            String id = attribute.id().value();
            symbols.put(id, AddonMessages.ContentSymbol.newBuilder().setId(id).setKind(QI_ATTRIBUTE).build());
        }
        return symbols;
    }

    private record DefinitionKey(String id, int version) { }
}
