package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.error.MeridianErrors.Addon;
import dev.kizuna.meridian.protocol.pb.AddonMessages;

import java.util.HashSet;
import java.util.List;

/** 附属内容使用独立的可信安装契约，与玩家身体变更请求分开。 */
public final class AddonProtobuf {
    public static final int SCHEMA_VERSION = 1;
    public static final int API_VERSION = 1;

    private AddonProtobuf() {
        // 编解码负责格式与定义合法性，跨包依赖由注册表统一校验。
    }

    public static byte[] encodeBundle(AddonMessages.AddonBundle bundle) {
        // 单包可作为 JAR 资源或其他语言的二进制内容文件交付。
        validateBundle(bundle);
        return ProtoWire.write(bundle);
    }

    public static AddonMessages.AddonBundle decodeBundle(byte[] payload) {
        // 未知字段和超限内容不能绕过通用 Protobuf 输入限制。
        var bundle = ProtoWire.read(payload, AddonMessages.AddonBundle.parser());
        validateBundle(bundle);
        return bundle;
    }

    public static byte[] encodeRegistration(AddonMessages.AddonRegistration registration) {
        // 宿主把本次完整候选一起提交；不逐包改变正在使用的目录。
        validateRegistration(registration);
        return ProtoWire.write(registration);
    }

    public static AddonMessages.AddonRegistration decodeRegistration(byte[] payload) {
        // 完整批次也受字节、字段与列表总量限制，不能用许多小包绕过。
        var registration = ProtoWire.read(payload, AddonMessages.AddonRegistration.parser());
        validateRegistration(registration);
        return registration;
    }

    public static void validateRegistration(AddonMessages.AddonRegistration registration) {
        // 能力画像只能来自宿主，版本与身份检查在任何注册行为之前完成。
        ProtoWire.validate(registration);
        ProtoWire.require(registration.getProtocolVersion() == MeridianProtocol.VERSION, Addon.PROTOCOL_VERSION);
        ProtoWire.require(registration.getHost().getApiVersion() == API_VERSION, Addon.API_VERSION);
        new DefinitionId(registration.getHost().getHostId());
        validateIds(registration.getHost().getCapabilitiesList());
        for (var bundle : registration.getBundlesList()) {
            validateBundle(bundle);
        }
    }

    public static void validateBundle(AddonMessages.AddonBundle bundle) {
        // 先检查清单，再复用身体和配方的现有业务校验，避免形成宽松的第二套规则。
        ProtoWire.validate(bundle);
        ProtoWire.require(bundle.getSchemaVersion() == SCHEMA_VERSION, Addon.SCHEMA_VERSION);
        var manifest = bundle.getManifest();
        validateNamespace(manifest.getNamespace());
        ProtoWire.require(manifest.getContentVersion() > 0, Addon.VERSION);
        validateRange(manifest.getApiVersions());
        ProtoWire.require(!manifest.getSupportedHostsList().isEmpty(), Addon.HOSTS_EMPTY);
        validateIds(manifest.getSupportedHostsList());
        validateIds(manifest.getRequiredCapabilitiesList());

        var dependencies = new HashSet<String>();
        for (var dependency : manifest.getDependenciesList()) {
            validateNamespace(dependency.getNamespace());
            validateRange(dependency.getVersions());
            ProtoWire.require(dependencies.add(dependency.getNamespace()), Addon.DUPLICATE_DECLARATION);
        }
        for (var symbol : bundle.getSymbolsList()) {
            new DefinitionId(symbol.getId());
        }
        for (var template : bundle.getTemplatesList()) {
            BodyProtobuf.fromMessage(template);
        }
        for (var recipe : bundle.getOrganRecipesList()) {
            CapabilityProtobuf.fromRecipe(recipe);
        }
        for (var action : bundle.getActionsList()) {
            ActionProtobuf.fromDefinition(action);
        }
    }

    public static byte[] encodeReferences(AddonMessages.ContentReferences references) {
        // 原始引用可随宿主存档保存；缺包不改写 ID 或版本。
        validateReferences(references);
        return ProtoWire.write(references);
    }

    public static AddonMessages.ContentReferences decodeReferences(byte[] payload) {
        // 引用格式有效不等于已安装，实际可用性需查询当前注册表。
        var references = ProtoWire.read(payload, AddonMessages.ContentReferences.parser());
        validateReferences(references);
        return references;
    }

    public static void validateReferences(AddonMessages.ContentReferences references) {
        // 保留列表顺序与重复引用，允许宿主把结果对应到不同知识或布局条目。
        ProtoWire.validate(references);
        ProtoWire.require(references.getProtocolVersion() == MeridianProtocol.VERSION, Addon.PROTOCOL_VERSION);
        for (var reference : references.getReferencesList()) {
            validateReference(reference);
        }
    }

    public static void validateReference(AddonMessages.ContentReference reference) {
        // 查找必须精确到定义版本，不能把缺失修订解释为最新版本。
        ProtoWire.validate(reference);
        new DefinitionId(reference.getId());
        ProtoWire.require(reference.getVersion() > 0, Addon.VERSION);
    }

    public static byte[] encodeAvailability(AddonMessages.ContentAvailabilityReport report) {
        // 状态和原始引用一起传递，使用方无需根据错误文案猜测缺失类别。
        validateAvailability(report);
        return ProtoWire.write(report);
    }

    public static AddonMessages.ContentAvailabilityReport decodeAvailability(byte[] payload) {
        // 可用性是目录查询结果，不授予学习权限或修改身体状态的能力。
        var report = ProtoWire.read(payload, AddonMessages.ContentAvailabilityReport.parser());
        validateAvailability(report);
        return report;
    }

    private static void validateAvailability(AddonMessages.ContentAvailabilityReport report) {
        // 各项都须携带合法的精确引用；列表可为空。
        ProtoWire.validate(report);
        ProtoWire.require(report.getProtocolVersion() == MeridianProtocol.VERSION, Addon.PROTOCOL_VERSION);
        for (var entry : report.getEntriesList()) {
            validateReference(entry.getReference());
        }
    }

    public static byte[] encodeIssue(AddonMessages.AddonIssue issue) {
        // 安装异常的结构化部分可跨进程返回，不暴露 Java 堆栈。
        ProtoWire.validate(issue);
        validateNamespace(issue.getAddonNamespace());
        return ProtoWire.write(issue);
    }

    public static AddonMessages.AddonIssue decodeIssue(byte[] payload) {
        // 稳定枚举和定位信息足以提示恢复依赖或修正内容定义。
        var issue = ProtoWire.read(payload, AddonMessages.AddonIssue.parser());
        validateNamespace(issue.getAddonNamespace());
        return issue;
    }

    public static String namespaceOf(String id) {
        // 内容身份经统一格式检查后提取归属，避免接受伪造或空命名空间。
        new DefinitionId(id);
        return id.substring(0, id.indexOf(':'));
    }

    private static void validateNamespace(String namespace) {
        // 框架内置属性使用 meridian；任何官方或第三方包都不能占用它。
        ProtoWire.require(namespace.matches("[a-z0-9_.-]+") && !namespace.equals("meridian"), Addon.NAMESPACE);
    }

    private static void validateRange(AddonMessages.ContentVersionRange range) {
        // 上下限均包含端点；显式封顶避免未经验证就声称兼容未来版本。
        ProtoWire.require(range.getMinimum() > 0 && range.getMaximum() >= range.getMinimum(), Addon.VERSION);
    }

    private static void validateIds(List<String> ids) {
        // 能力和宿主身份使用同一命名空间语法，重复声明直接提示内容作者修正。
        var seen = new HashSet<String>();
        for (String id : ids) {
            new DefinitionId(id);
            ProtoWire.require(seen.add(id), Addon.DUPLICATE_DECLARATION);
        }
    }
}
