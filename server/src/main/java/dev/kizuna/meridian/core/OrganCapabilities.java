package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

import java.util.Comparator;
import java.util.List;

/** 从实际器官定义和权威曲线派生摄入与吞吐，不修改基础构型。 */
public final class OrganCapabilities {
    private OrganCapabilities() {
        // 宿主和核心结算共用纯计算，不缓存另一份有效能力状态。
    }

    public static double intakeMultiplier(BodySnapshot body, Anatomy.OrganId organId) {
        // 宿主采样体积与核心摄入上限采用同一时刻的倍率。
        return multiplier(body, organId, CapabilityModifier.Attribute.INTAKE);
    }

    public static double intakePerSecond(BodySnapshot body, Anatomy.OrganId organId) {
        // 没有摄入能力的器官不能因修正获得新能力。
        Anatomy.OrganDefinition definition = definition(body, organId);
        if (definition.intake() == null) {
            return 0;
        }
        return finite(definition.intake().maxQiPerSecond() * intakeMultiplier(body, organId));
    }

    public static double throughputPerSecond(BodySnapshot body, Anatomy.OrganId organId) {
        // 公共查询与运输端口使用相同的派生吞吐。
        return throughput(body, definition(body, organId));
    }

    static double throughput(BodyView body, Anatomy.OrganDefinition definition) {
        // 运输候选按自己的游标求值，既有预约不因倍率到期被重新收费。
        double multiplier = multiplier(body, definition.id(), CapabilityModifier.Attribute.THROUGHPUT);
        return finite(definition.throughputPerSecond() * multiplier);
    }

    static double intakeLimit(BodySnapshot body, Anatomy.OrganDefinition definition, long endMillis) {
        // 长区间按曲线精确分段积分，不能用终点倍率放大整个过去区间。
        List<CapabilityModifier> modifiers = modifiers(body, definition.id());
        double seconds = CapabilityCurves.effectiveSeconds(modifiers, CapabilityModifier.Attribute.INTAKE,
                body.settledAt().elapsedMillis(), endMillis);
        return finite(definition.intake().maxQiPerSecond() * seconds);
    }

    private static double multiplier(BodyView body, Anatomy.OrganId organId,
                                     CapabilityModifier.Attribute attribute) {
        // 按稳定身份排序后合并，保持存档顺序变化时的数值一致。
        return CapabilityCurves.current(modifiers(body, organId), attribute, body.settledAt().elapsedMillis());
    }

    private static List<CapabilityModifier> modifiers(BodyView body, Anatomy.OrganId organId) {
        // 组织修正不能影响同名器官，目标类型由协议与核心共同校验。
        return body.modifiers().stream()
                .filter(modifier -> organId.equals(modifier.target().organId()))
                .sorted(Comparator.comparing(modifier -> modifier.id().value()))
                .toList();
    }

    private static Anatomy.OrganDefinition definition(BodySnapshot body, Anatomy.OrganId organId) {
        // 不存在的器官明确拒绝，避免调用者把缺失模块误认为单位倍率。
        return body.structure().organs().stream()
                .filter(organ -> organ.id().equals(organId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.rejectIntake(FailureCode.MODULE_NOT_FOUND));
    }

    private static double finite(double value) {
        // 基础值与倍率分别合法仍可能乘法溢出，统一报告资源数值上限。
        if (!Double.isFinite(value)) {
            throw MeridianErrors.rejectIntake(FailureCode.NUMERIC_LIMIT);
        }
        return value;
    }
}
