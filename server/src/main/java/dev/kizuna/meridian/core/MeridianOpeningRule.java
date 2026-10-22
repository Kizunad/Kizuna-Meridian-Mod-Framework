package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Opening;

import com.google.protobuf.Any;
import java.util.List;

/** 首次建立单脉意念响应的内容规则；资源仍由目标经脉内腔持有。 */
public record MeridianOpeningRule(DefinitionId id, int version, String displayName,
                                  Anatomy.MeridianId targetMeridianId,
                                  DefinitionId actionId, int actionVersion,
                                  List<Effort> efforts, boolean continuousHold, double lumenVolumeGain,
                                  double guidanceQiPerSecond, double guidanceBatchQi,
                                  double holdQiPerSecond,
                                  DefinitionId policyId, Any policyConfig) {
    public MeridianOpeningRule {
        // 定义身份与动作都采用精确修订，防止加载顺序改变规则含义。
        MeridianErrors.required(id, Opening.RULE_ID_REQUIRED);
        MeridianErrors.required(targetMeridianId, MeridianErrors.Data.MERIDIAN_ID_REQUIRED);
        MeridianErrors.required(actionId, Opening.ACTION_ID_REQUIRED);
        MeridianErrors.required(policyId, Opening.POLICY_INVALID);
        MeridianErrors.required(policyConfig, Opening.POLICY_INVALID);
        DataChecks.text(policyConfig.getTypeUrl(), "开拓策略配置类型");
        DataChecks.text(displayName, "开拓规则名称");
        if (version <= 0 || actionVersion <= 0) {
            throw MeridianErrors.invalid(Opening.VERSION);
        }

        // 框架只校验运输与扣费参数；生物条件留在附属的 Protobuf 配置和策略中。
        efforts = List.copyOf(efforts);
        DataChecks.index(efforts, Effort::moduleId, "开拓费用模块");
        DataChecks.nonNegative(lumenVolumeGain, "开拓容量增量");
        DataChecks.nonNegative(guidanceQiPerSecond, "开拓引导吞吐");
        DataChecks.nonNegative(guidanceBatchQi, "开拓引导批次");
        DataChecks.nonNegative(holdQiPerSecond, "开拓维持真元消耗速率");
        if ((guidanceQiPerSecond == 0) != (guidanceBatchQi == 0)) {
            throw MeridianErrors.invalid(Opening.GUIDANCE);
        }
        // 最短结算单位为一毫秒；拒绝成本溢出或小到无法表示的比例费用。
        if (holdQiPerSecond > 0) {
            DataChecks.positive(holdQiPerSecond / 1000, "开拓每毫秒消耗");
        }
    }

    public record GuidanceTarget(Anatomy.MeridianId meridianId, double volume) {
        public GuidanceTarget {
            // 引导目标只表达期望的占容，不授予额外空间。
            MeridianErrors.required(meridianId, MeridianErrors.Data.MERIDIAN_ID_REQUIRED);
            DataChecks.positive(volume, "开拓引导体积");
        }
    }

    public record Effort(Object moduleId, double watts) {
        public Effort {
            // ID 保留模块类型；同名器官和组织的独立耐力不能串账。
            if (!(moduleId instanceof Anatomy.TissueId) && !(moduleId instanceof Anatomy.OrganId)) {
                throw MeridianErrors.invalid(Opening.EFFORTS);
            }
            DataChecks.positive(watts, "开拓持续功率");
        }
    }

}
