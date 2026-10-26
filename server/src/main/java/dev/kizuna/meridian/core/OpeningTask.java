package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;

import java.util.List;

/** 身体唯一账本中的开脉任务；额度、进度和摄入证据均不是第二份真元库存。 */
public record OpeningTask(MeridianOpeningRule rule, String actionInstanceId,
                          List<MeridianOpeningRule.GuidanceTarget> participants,
                          long requiredMillis, double targetStableVolume, long startedMillis,
                          long heldMillis, Status status, List<IntakeSample> intakeSamples,
                          List<Anatomy.OrganId> observedOrganIds, long receiptWindowMillis) {
    public OpeningTask {
        // 保存完整规则与派生目标，恢复时不重新抽取内容版本或重新计算序号。
        MeridianErrors.required(rule, MeridianErrors.Opening.TASK_STATE);
        DataChecks.localId(actionInstanceId);
        participants = List.copyOf(participants);
        intakeSamples = List.copyOf(intakeSamples);
        observedOrganIds = List.copyOf(observedOrganIds);
        DataChecks.index(observedOrganIds, id -> id, "开拓观察器官");
        var limits = DataChecks.index(participants, MeridianOpeningRule.GuidanceTarget::meridianId, "开拓参与经脉");
        DataChecks.positive(targetStableVolume, "开拓后稳定容量");
        MeridianErrors.required(status, MeridianErrors.Opening.TASK_STATE);
        if (!limits.containsKey(rule.targetMeridianId()) || requiredMillis <= 0 || startedMillis < 0
                || heldMillis < 0 || heldMillis > requiredMillis || receiptWindowMillis < 0
                || (!observedOrganIds.isEmpty() && receiptWindowMillis == 0)
                || ((status == Status.COMPLETED) != (heldMillis == requiredMillis))) {
            throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
        }
    }

    public enum Status { PREPARING, HOLDING, PAUSED, EXHAUSTED, DAMAGED, COMPLETED }

    public record IntakeSample(Anatomy.OrganId organId, long atMillis, double qi) {
        public IntakeSample {
            // 只记录已入器官的环境摄入，不将加工输出、在途或内腔搬运算成新供给。
            MeridianErrors.required(organId, MeridianErrors.Data.ORGAN_ID_REQUIRED);
            DataChecks.positive(qi, "开拓摄入证据");
            if (atMillis < 0) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
            }
        }
    }

    public boolean active() {
        // 终止状态继续供界面查看，但不能继续占用动作。
        return status == Status.PREPARING || status == Status.HOLDING;
    }

    public OpeningTask progress(long held, Status nextStatus, List<IntakeSample> samples) {
        // 保留启动事实；进度更新不能改变容量增量或本轮要求。
        if (held == heldMillis && nextStatus == status && samples.equals(intakeSamples)) {
            return this;
        }
        return new OpeningTask(rule, actionInstanceId, participants, requiredMillis, targetStableVolume,
                startedMillis, held, nextStatus, samples, observedOrganIds, receiptWindowMillis);
    }
}
