package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.CapabilityRejectionReason;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;

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
        // 1. 找到组织定义及当前状态：定义提供基础能力和上限，状态提供实际伤势与疲劳。
        Anatomy.TissueDefinition tissueDefinition = requireTissueDefinition(body, tissueId);
        // 快照构造时已保证组织与部位数据齐全；查找失败属于内部状态错误，不能用默认值代替。
        AnatomyState.Tissue tissueState = body.tissueStates().stream()
                .filter(tissue -> tissue.id().equals(tissueId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.inconsistent(Data.TISSUE_CONTEXT_INCOMPLETE));

        // 2. 找到组织所属部位。组织自身完好，也可能因为整个部位受伤而无法正常发力。
        Anatomy.PartId partId = tissueDefinition.partId();
        Anatomy.PartDefinition partDefinition = body.structure().parts().stream()
                .filter(part -> part.id().equals(partId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.inconsistent(Data.TISSUE_CONTEXT_INCOMPLETE));

        AnatomyState.Part partState = body.partStates().stream()
                .filter(part -> part.id().equals(partId))
                .findFirst()
                .orElseThrow(() -> MeridianErrors.inconsistent(Data.TISSUE_CONTEXT_INCOMPLETE));

        // 3. 计算伤势限制：组织和部位取较低完好比例，不把两份伤势重复相乘。
        double tissueHealthRatio = tissueState.hp() / tissueDefinition.maxHp();
        double partHealthRatio = partState.hp() / partDefinition.maxHp();
        double healthRatio = Math.min(tissueHealthRatio, partHealthRatio);
        // HP 校验允许极小的浮点舍入误差，但这种误差不能产生超过满健康的能力。
        healthRatio = Math.min(1.0, healthRatio);

        // 4. 计算疲劳余量：疲劳达到容量时余量为零，不能让舍入误差产生负能力。
        double fatigueRatio = tissueState.fatigue() / tissueDefinition.fatigueCapacity();
        double remainingFatigueRatio = Math.max(0.0, 1.0 - fatigueRatio);

        // 5. 活体同时受伤势和疲劳限制；已经死亡或耗尽寿元时，所有可用能力归零。
        double capacityMultiplier;
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            capacityMultiplier = 0.0;
        } else {
            capacityMultiplier = healthRatio * remainingFatigueRatio;
        }

        // 6. 分别计算力量、爆发功率和耐力。基础值来自组织本身，不从经脉容量推导。
        Anatomy.TissueCapacity baseCapacity = tissueDefinition.capacity();
        double availableStrengthNewtons = baseCapacity.strengthNewtons() * capacityMultiplier;
        double availableBurstWatts = baseCapacity.burstWatts() * capacityMultiplier;
        double availableEnduranceJoules = baseCapacity.enduranceJoules() * capacityMultiplier;

        return new Anatomy.TissueCapacity(
                availableStrengthNewtons,
                availableBurstWatts,
                availableEnduranceJoules);
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
        Anatomy.TissueDefinition tissueDefinition = requireTissueDefinition(body, request.tissueId());
        Anatomy.TissueCapacity availableCapacity = available(body, request.tissueId());
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

        // 4. 只增加目标组织的疲劳；其他组织保持原值，不能跨组织分摊这次消耗。
        var updatedTissues = new ArrayList<AnatomyState.Tissue>();
        for (AnatomyState.Tissue tissueState : body.tissueStates()) {
            if (!tissueState.id().equals(request.tissueId())) {
                updatedTissues.add(tissueState);
                continue;
            }

            // 用掉当前耐力预算的几成，就消耗剩余疲劳容量的几成；受伤后会更快疲劳。
            double consumedEnduranceRatio = requestedWorkJoules / availableCapacity.enduranceJoules();
            double remainingFatigueCapacity = tissueDefinition.fatigueCapacity() - tissueState.fatigue();
            double fatigueIncrease = consumedEnduranceRatio * remainingFatigueCapacity;
            double updatedFatigue = tissueState.fatigue() + fatigueIncrease;
            updatedFatigue = Math.min(tissueDefinition.fatigueCapacity(), updatedFatigue);

            // 浮点精度若吞掉疲劳增长，必须拒绝，防止执行动作却没有承担代价。
            if (!Double.isFinite(updatedFatigue) || updatedFatigue <= tissueState.fatigue()) {
                throw MeridianErrors.rejectTransport(TransportRejectionReason.NUMERIC_LIMIT);
            }
            updatedTissues.add(new AnatomyState.Tissue(tissueState.id(), tissueState.hp(), updatedFatigue));
        }

        // 5. 返回完整候选身体，只替换组织状态并递增版本；实际提交仍由宿主事务完成。
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), updatedTissues, body.organStates(), body.meridianStates(), body.transits(),
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask());
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
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask());
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
