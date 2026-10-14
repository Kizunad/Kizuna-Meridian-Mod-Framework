package dev.kizuna.meridian.server;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.AddonMessages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

import static dev.kizuna.meridian.protocol.pb.AddonMessages.AddonIssueCode.*;

/** 包之间的依赖和宿主兼容检查；不接触身体状态或发布目录。 */
final class AddonDependencies {
    private AddonDependencies() {
        // 启动期校验无需保存可变的运行状态。
    }

    static void validate(Map<String, AddonMessages.AddonBundle> bundles, AddonMessages.AddonHostProfile host) {
        // 先确认每个直接依赖及版本都存在，随后才判断完整依赖图是否可加载。
        Set<String> capabilities = Set.copyOf(host.getCapabilitiesList());
        for (String namespace : new TreeSet<>(bundles.keySet())) {
            var bundle = bundles.get(namespace);
            var manifest = bundle.getManifest();
            if (!includes(manifest.getApiVersions(), host.getApiVersion())) {
                throw MeridianErrors.rejectAddon(API_INCOMPATIBLE, namespace, Integer.toString(host.getApiVersion()));
            }
            if (!manifest.getSupportedHostsList().contains(host.getHostId())) {
                throw MeridianErrors.rejectAddon(HOST_UNSUPPORTED, namespace, host.getHostId());
            }
            for (String required : manifest.getRequiredCapabilitiesList()) {
                requireCapability(capabilities, namespace, required);
            }

            // 已携带的内容也有最低能力要求，不能通过漏写 required_capabilities 绕过。
            if (bundle.getTemplatesCount() > 0) {
                requireCapability(capabilities, namespace, AddonRegistry.BODY_TEMPLATES);
            }
            if (bundle.getOrganRecipesCount() > 0) {
                requireCapability(capabilities, namespace, AddonRegistry.ORGAN_RECIPES);
            }
            boolean declaresIntake = bundle.getTemplatesList().stream()
                    .flatMap(template -> template.getStructure().getOrgansList().stream())
                    .anyMatch(organ -> organ.hasIntake());
            if (declaresIntake) {
                requireCapability(capabilities, namespace, AddonRegistry.ORGAN_INTAKE);
            }
            boolean declaresRecovery = bundle.getTemplatesList().stream()
                    .flatMap(template -> template.getStructure().getTissuesList().stream())
                    .anyMatch(tissue -> tissue.getRecoveryHalfLifeSeconds() > 0);
            if (declaresRecovery) {
                // 其他语言核心也须显式支持恢复规则，不能只读取字段却忽略其运行含义。
                requireCapability(capabilities, namespace, AddonRegistry.TISSUE_RECOVERY);
            }
            if (bundle.getActionsCount() > 0) {
                requireCapability(capabilities, namespace, AddonRegistry.ACTIONS);
            }

            for (var dependency : manifest.getDependenciesList()) {
                var installed = bundles.get(dependency.getNamespace());
                if (installed == null) {
                    throw MeridianErrors.rejectAddon(DEPENDENCY_MISSING, namespace, dependency.getNamespace());
                }
                if (!includes(dependency.getVersions(), installed.getManifest().getContentVersion())) {
                    throw MeridianErrors.rejectAddon(DEPENDENCY_VERSION_MISMATCH, namespace, dependency.getNamespace());
                }
            }
        }

        // 迭代剥离没有前置依赖的包；合法身体拓扑允许循环，包依赖图则必须能完整初始化。
        var remainingDependencies = new HashMap<String, Integer>();
        var dependents = new HashMap<String, ArrayList<String>>();
        var ready = new PriorityQueue<String>();
        for (var bundle : bundles.values()) {
            var manifest = bundle.getManifest();
            String namespace = manifest.getNamespace();
            remainingDependencies.put(namespace, manifest.getDependenciesCount());
            if (manifest.getDependenciesCount() == 0) {
                ready.add(namespace);
            }
            for (var dependency : manifest.getDependenciesList()) {
                dependents.computeIfAbsent(dependency.getNamespace(), key -> new ArrayList<>()).add(namespace);
            }
        }

        int resolved = 0;
        while (!ready.isEmpty()) {
            String dependency = ready.remove();
            resolved++;
            for (String dependent : dependents.getOrDefault(dependency, new ArrayList<>())) {
                int remaining = remainingDependencies.get(dependent) - 1;
                remainingDependencies.put(dependent, remaining);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (resolved != bundles.size()) {
            // 剩余集合中可能包含依赖环的下游；沿未解决依赖前进可定位到真正重复的节点。
            String current = remainingDependencies.keySet().stream()
                    .filter(namespace -> remainingDependencies.get(namespace) > 0).sorted().findFirst().orElseThrow();
            var visited = new TreeSet<String>();
            while (visited.add(current)) {
                current = bundles.get(current).getManifest().getDependenciesList().stream()
                        .map(AddonMessages.AddonDependency::getNamespace)
                        .filter(namespace -> remainingDependencies.get(namespace) > 0).sorted().findFirst().orElseThrow();
            }
            throw MeridianErrors.rejectAddon(DEPENDENCY_CYCLE, current, current);
        }
    }

    private static boolean includes(AddonMessages.ContentVersionRange range, int version) {
        // 包修订与内容 API 都采用显式的闭区间，不推断语义版本兼容性。
        return version >= range.getMinimum() && version <= range.getMaximum();
    }

    private static void requireCapability(Set<String> capabilities, String namespace, String required) {
        // 可信宿主的实际能力是启用前提，缺少时不能退回另一种隐含执行规则。
        if (!capabilities.contains(required)) {
            throw MeridianErrors.rejectAddon(CAPABILITY_MISSING, namespace, required);
        }
    }
}
