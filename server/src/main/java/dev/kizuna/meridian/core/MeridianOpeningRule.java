package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Opening;

import com.google.protobuf.Any;
import java.util.List;

/** 首次建立单脉意念响应的内容规则；资源仍由目标经脉内腔持有。 */
public record MeridianOpeningRule(DefinitionId id, int version, String displayName,
                                  Anatomy.MeridianId targetMeridianId,
                                  QiInventory minimumLumenQi, DefinitionId actionId, int actionVersion,
                                  List<Effort> efforts, boolean continuousHold, double lumenCapacityGain,
                                  double guidanceQiPerSecond, double guidanceBatchQi,
                                  double holdQiPerSecond, double guidanceBufferQi,
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
        MeridianErrors.required(minimumLumenQi, Opening.QI_REQUIRED);
        if (minimumLumenQi.entries().isEmpty()) {
            throw MeridianErrors.invalid(Opening.QI_EMPTY);
        }
        efforts = List.copyOf(efforts);
        DataChecks.index(efforts, Effort::moduleId, "开拓费用模块");
        DataChecks.nonNegative(lumenCapacityGain, "开拓容量增量");
        DataChecks.nonNegative(guidanceQiPerSecond, "开拓引导吞吐");
        DataChecks.nonNegative(guidanceBatchQi, "开拓引导批次");
        DataChecks.nonNegative(holdQiPerSecond, "开拓维持真元消耗速率");
        DataChecks.nonNegative(guidanceBufferQi, "开拓引导缓冲");
        if ((guidanceQiPerSecond == 0) != (guidanceBatchQi == 0)) {
            throw MeridianErrors.invalid(Opening.GUIDANCE);
        }
        if (guidanceBufferQi > 0 && guidanceQiPerSecond == 0) {
            throw MeridianErrors.invalid(Opening.GUIDANCE_BUFFER);
        }
        // 临时容量还包含中转余量，独立字段有限并不足以保证相加后仍可结算。
        DataChecks.positive(minimumLumenQi.totalQi() + guidanceBufferQi + guidanceBatchQi, "开拓临时容量");
        // 最短结算单位为一毫秒；拒绝成本溢出或小到无法表示的比例费用。
        if (holdQiPerSecond > 0) {
            for (Qi minimum : minimumLumenQi.entries()) {
                DataChecks.positive(holdQiPerSecond * (minimum.amount() / minimumLumenQi.totalQi()) / 1000,
                        "开拓每毫秒明细消耗");
            }
        }
    }

    public record PressureLimit(Anatomy.MeridianId meridianId, double qi) {
        public PressureLimit {
            // 额度只授予实际参与的经脉，不生成任何库存。
            MeridianErrors.required(meridianId, MeridianErrors.Data.MERIDIAN_ID_REQUIRED);
            DataChecks.positive(qi, "开拓增压额度");
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
