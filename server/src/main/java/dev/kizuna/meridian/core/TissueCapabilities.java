package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.CapabilityRejectionReason;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;

import static dev.kizuna.meridian.core.CapabilityCurves.Multipliers;

/** 组织自身的能力与做功规则；不决定具体武器伤害、移速或世界动作。 */
public final class TissueCapabilities {
    private TissueCapabilities() {
        // 查询派生能力不写账；做功必须走权威命令与事务。
    }

    public record Exertion(Anatomy.TissueId tissueId, double forceNewtons, double powerWatts, double durationSeconds) {
        public Exertion {
            // 做功需要正功率与时长；神经组织等非机械负载可以没有外部作用力。
            MeridianErrors.required(tissueId, Data.TISSUE_ID_REQUIRED);
            DataChecks.nonNegative(forceNewtons, "做功力量");
            DataChecks.positive(powerWatts, "做功功率");
            DataChecks.positive(durationSeconds, "做功时长");
        }
    }

    public static Anatomy.TissueCapacity available(BodySnapshot body, Anatomy.TissueId tissueId) {
        // 查询只取身体当前时刻的倍率，未来曲线不提前生效。
        CapabilityCurves.Context curves = CapabilityCurves.context(body.modifiers());
        Multipliers multipliers = curves.current(tissueId, body.settledAt().elapsedMillis());
        return available(body, tissueId, multipliers);
    }

    static Anatomy.TissueCapacity availableDuring(BodySnapshot body, Exertion request) {
        // 做功预算对整个时段保守取值，不能在峰值瞬间预支之后已衰减的能力。
        CapabilityCurves.Context curves = CapabilityCurves.context(body.modifiers());
        Multipliers multipliers = curves.minimum(request.tissueId(), body.settledAt().elapsedMillis(),
                request.durationSeconds());
        return available(body, request.tissueId(), multipliers);
    }

    static Anatomy.TissueCapacity available(BodyAdvancement body, Anatomy.TissueId tissueId) {
        // 开脉预算与恢复复用整次推进的索引及同一时刻的曲线评估。
        Multipliers multipliers = body.curves().current(tissueId, body.time.elapsedMillis());
        return available(body, tissueId, multipliers);
    }

    static Anatomy.TissueCapacity availableDuring(BodyAdvancement body, Exertion request) {
        // 连续负载仍检查整个区间最低值，端点评估缓存仅消除重复计算。
        Multipliers multipliers = body.curves().minimum(request.tissueId(), body.time.elapsedMillis(),
                request.durationSeconds());
        return available(body, request.tissueId(), multipliers);
    }

    private static Anatomy.TissueCapacity available(BodyAdvancement body, Anatomy.TissueId tissueId,
                                                    Multipliers multipliers) {
        // 静态定义和状态位置在一次推进内固定，避免每个预算步骤重新扫描全身。
        Anatomy.TissueDefinition definition = body.tissueDefinitions().get(tissueId);
        if (definition == null) {
            throw MeridianErrors.rejectCapability(CapabilityRejectionReason.MODULE_NOT_FOUND);
        }
        AnatomyState.Tissue state = body.tissues.get(body.tissuePosition(tissueId));
        double tissueHealthRatio = state.hp() / definition.maxHp();
        double partHealthRatio = body.partState(definition.partId()).hp()
                / body.partDefinition(definition.partId()).maxHp();
        double healthRatio = StrictMath.min(1, StrictMath.min(tissueHealthRatio, partHealthRatio));
        return availableCapacity(body.vitality, definition, state, healthRatio, multipliers);
    }

    private static Anatomy.TissueCapacity available(BodySnapshot body, Anatomy.TissueId tissueId,
                                                   Multipliers modifiers) {
        // 1. 找到组织定义及当前状态：定义提供基础能力和上限，状态提供实际伤势与疲劳。
        Anatomy.TissueDefinition tissueDefinition = requireTissueDefinition(body, tissueId);
        // 快照构造时已保证组织与部位数据齐全；查找失败属于内部状态错误，不能用默认值代替。
        AnatomyState.Tissue tissueState = body.tissueStates().stream()
                .filter(tissue -> tissue.id().equals(tissueId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.inconsistent(Data.TISSUE_CONTEXT_INCOMPLETE));

        // 2. 伤势取组织和部位较低完好率，疲劳耗尽时余量为零。
        double healthRatio = healthRatio(body, tissueDefinition, tissueState);
        return availableCapacity(body.vitality(), tissueDefinition, tissueState, healthRatio, modifiers);
    }

    private static Anatomy.TissueCapacity availableCapacity(VitalityState vitality,
            Anatomy.TissueDefinition tissueDefinition, AnatomyState.Tissue tissueState,
            double healthRatio, Multipliers modifiers) {
        // 伤势与疲劳约束基础能力，三项倍率均在完整乘积后截断。
        double fatigueRatio = tissueState.fatigue() / tissueDefinition.fatigueCapacity();
        double remainingFatigueRatio = Math.max(0.0, 1.0 - fatigueRatio);

        // 3. 活体同时受伤势和疲劳限制；已经死亡或耗尽寿元时，所有可用能力归零。
        double capacityMultiplier;
        if (vitality.dead() || vitality.remainingSeconds() == 0) {
            capacityMultiplier = 0.0;
        } else {
            capacityMultiplier = healthRatio * remainingFatigueRatio;
        }

        // 4. 每项修正先完成全部乘积再截断，减益不会因添加顺序而丢失。
        Anatomy.TissueCapacity baseCapacity = tissueDefinition.capacity();
        double availableStrengthNewtons = scaledCapacity(baseCapacity.strengthNewtons(), capacityMultiplier,
                modifiers.strength());
        double availableBurstWatts = scaledCapacity(baseCapacity.burstWatts(), capacityMultiplier, modifiers.burst());
        double availableEnduranceJoules = scaledCapacity(baseCapacity.enduranceJoules(), capacityMultiplier,
                modifiers.endurance());

        return new Anatomy.TissueCapacity(
                availableStrengthNewtons,
                availableBurstWatts,
                availableEnduranceJoules);
    }

    private static double healthRatio(BodySnapshot body, Anatomy.TissueDefinition definition,
                                       AnatomyState.Tissue tissue) {
        // 所属部位受伤也会限制发力；快照校验容许的 HP 舍入误差不能放大基础能力。
        Anatomy.PartId partId = definition.partId();
        Anatomy.PartDefinition partDefinition = body.structure().parts().stream()
                .filter(part -> part.id().equals(partId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.inconsistent(Data.TISSUE_CONTEXT_INCOMPLETE));
        AnatomyState.Part partState = body.partStates().stream()
                .filter(part -> part.id().equals(partId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.inconsistent(Data.TISSUE_CONTEXT_INCOMPLETE));
        double tissueHealthRatio = tissue.hp() / definition.maxHp();
        double partHealthRatio = partState.hp() / partDefinition.maxHp();
        return Math.min(1, Math.min(tissueHealthRatio, partHealthRatio));
    }

    private static double scaledCapacity(double base, double healthAndFatigue, double modifiers) {
        // 健康疲劳比例不超过 1；常规范围直接计算，避免查询时分配十进制大数。
        double healthyCapacity = base * healthAndFatigue;
        if (healthyCapacity >= Double.MIN_NORMAL || base == 0 || healthAndFatigue == 0) {
            return healthyCapacity * modifiers;
        }
        // 中间下溢时保留原始三个因子，后续较大倍率仍可能将最终结果恢复到可表示范围。
        return BigDecimal.valueOf(base)
                .multiply(BigDecimal.valueOf(healthAndFatigue))
                .multiply(BigDecimal.valueOf(modifiers))
                .doubleValue();
    }

    public static BodySnapshot exert(BodySnapshot body, Exertion request) {
        // 1. 检查身体能否接受新动作；做功只记一次疲劳，不推进时钟或执行世界动作。
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.REVISION_EXHAUSTED);
        }

        // 2. 做功量 = 功率 × 时间，单位为焦耳；无法表示的工作量不能作为免费动作接受。
        Anatomy.TissueId tissueId = request.tissueId();
        Anatomy.TissueDefinition tissueDefinition = requireTissueDefinition(body, tissueId);
        Anatomy.TissueCapacity availableCapacity = availableDuring(body, request);
        double requestedWorkJoules = validateLoad(request, availableCapacity);

        // 4. 只增加目标组织的疲劳，完整候选仍由宿主事务原子提交。
        List<AnatomyState.Tissue> updatedTissues = exertedTissues(body, tissueDefinition, availableCapacity,
                requestedWorkJoules);
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), updatedTissues, body.organStates(), body.meridianStates(), body.transits(),
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask(),
                body.addonProgress(), body.modifiers());
    }

    private static double validateLoad(Exertion request, Anatomy.TissueCapacity availableCapacity) {
        // 无法表示的工作量和超出任一维度的负载都拒绝，内部连续预算沿用同一校验。
        double requestedWorkJoules = request.powerWatts() * request.durationSeconds();
        if (!Double.isFinite(requestedWorkJoules) || requestedWorkJoules == 0) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
        }
        if (availableCapacity.enduranceJoules() == 0) {
            throw MeridianErrors.rejectCapability(CapabilityRejectionReason.MODULE_UNAVAILABLE);
        }

        // 3. 力量、功率和总做功量各有自己的上限，任何一项超出都整次拒绝。
        boolean forceExceedsCapacity = request.forceNewtons() > availableCapacity.strengthNewtons();
        boolean powerExceedsCapacity = request.powerWatts() > availableCapacity.burstWatts();
        boolean workExceedsEndurance = requestedWorkJoules > availableCapacity.enduranceJoules();
        if (forceExceedsCapacity || powerExceedsCapacity || workExceedsEndurance) {
            throw MeridianErrors.rejectCapability(CapabilityRejectionReason.TISSUE_LOAD_EXCEEDED);
        }

        return requestedWorkJoules;
    }

    private static List<AnatomyState.Tissue> exertedTissues(BodySnapshot body, Anatomy.TissueDefinition definition,
                                                           Anatomy.TissueCapacity available, double workJoules) {
        // 用掉有效耐力的几成就消耗剩余疲劳容量的几成，修正不会扩大疲劳账户上限。
        var updatedTissues = new ArrayList<AnatomyState.Tissue>();
        Anatomy.TissueId tissueId = definition.id();
        for (AnatomyState.Tissue tissueState : body.tissueStates()) {
            if (!tissueState.id().equals(tissueId)) {
                updatedTissues.add(tissueState);
                continue;
            }

            updatedTissues.add(exertedTissue(tissueState, definition, available, workJoules));
        }

        return updatedTissues;
    }

    static AnatomyState.Tissue exert(BodyAdvancement body, Exertion request) {
        // 开脉只取得这一组织的候选疲劳，不构造或递增完整身体快照。
        Anatomy.TissueCapacity available = availableDuring(body, request);
        double workJoules = validateLoad(request, available);
        Anatomy.TissueDefinition definition = body.tissueDefinitions().get(request.tissueId());
        AnatomyState.Tissue state = body.tissues.get(body.tissuePosition(request.tissueId()));
        return exertedTissue(state, definition, available, workJoules);
    }

    private static AnatomyState.Tissue exertedTissue(AnatomyState.Tissue state, Anatomy.TissueDefinition definition,
                                                    Anatomy.TissueCapacity available, double workJoules) {
        // 用掉有效耐力的比例转换为剩余疲劳容量的比例，临时倍率不扩大疲劳账户。
        double fatigueCapacity = definition.fatigueCapacity();
        double consumedEnduranceRatio = workJoules / available.enduranceJoules();
        double remainingFatigueCapacity = fatigueCapacity - state.fatigue();
        double fatigueIncrease = consumedEnduranceRatio * remainingFatigueCapacity;
        double rawFatigue = state.fatigue() + fatigueIncrease;
        double updatedFatigue = StrictMath.min(fatigueCapacity, rawFatigue);
        if (!Double.isFinite(updatedFatigue) || updatedFatigue <= state.fatigue()) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
        }
        return new AnatomyState.Tissue(state.id(), state.hp(), updatedFatigue);
    }

    public static BodySnapshot exertAll(BodySnapshot body, List<Exertion> loads) {
        // 一次动作的全部组织先在内存中验算；任何失败都不发布部分疲劳。
        if (loads.isEmpty() || loads.size() > 32) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_TISSUE_LOADS);
        }
        var identities = new HashSet<Anatomy.TissueId>();
        var updated = new ArrayList<>(body.tissueStates());
        for (Exertion load : loads) {
            if (!identities.add(load.tissueId())) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_TISSUE_LOADS);
            }
            BodySnapshot candidate = exert(body, load);
            for (int index = 0; index < updated.size(); index++) {
                if (updated.get(index).id().equals(load.tissueId())) {
                    updated.set(index, candidate.tissueStates().get(index));
                }
            }
        }
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), updated, body.organStates(), body.meridianStates(), body.transits(),
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask(),
                body.addonProgress(), body.modifiers());
    }

    private static Anatomy.TissueDefinition requireTissueDefinition(BodySnapshot body, Anatomy.TissueId tissueId) {
        // 先校验 ID 对应的实际模块，不能因为类型类似就跨组织借用能力。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        MeridianErrors.required(tissueId, Data.TISSUE_ID_REQUIRED);
        return body.structure().tissues().stream()
                .filter(tissue -> tissue.id().equals(tissueId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.rejectCapability(CapabilityRejectionReason.MODULE_NOT_FOUND));
    }
}
