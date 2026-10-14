package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.LifespanRejectionReason;

/** 非致死寿元支出的纯计算；授权、去重和提交由服务入口完成。 */
public final class LifespanSpending {
    private LifespanSpending() {
        // 算子不持有第二份身体，不执行时间推进或外部副作用。
    }

    public static BodySnapshot apply(BodySnapshot body, double seconds) {
        // 即使由进程内调用，也不能绕过正数和有限数约束。
        MeridianErrors.required(body, Data.BODY_REQUIRED);
        DataChecks.positive(seconds, "寿元支出");

        // 先确认身体仍可变更，再判断本次剂量是否会耗尽寿命。
        var vitality = body.vitality();
        if (vitality.dead()) {
            throw MeridianErrors.rejectLifespan(LifespanRejectionReason.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectLifespan(LifespanRejectionReason.REVISION_EXHAUSTED);
        }
        // 死亡需要和环境释放一起结算；首片不得先扣尽寿元再留下半次死亡。
        if (seconds >= vitality.remainingSeconds()) {
            throw MeridianErrors.rejectLifespan(LifespanRejectionReason.INSUFFICIENT_LIFESPAN);
        }

        // 计算只产生候选状态，尚未向宿主提交任何寿元变化。
        double spent = vitality.spentLifespanSeconds() + seconds;
        if (!Double.isFinite(spent) || spent <= vitality.spentLifespanSeconds()) {
            throw MeridianErrors.rejectLifespan(LifespanRejectionReason.NUMERIC_LIMIT);
        }
        var updated = new VitalityState(vitality.ageSeconds(), vitality.lifespanLimitSeconds(), spent, false);
        // 极端量级下，舍入可能吞掉扣款或耗尽最后余额，不能把它报告成正常支出。
        if (updated.remainingSeconds() <= 0 || updated.remainingSeconds() >= vitality.remainingSeconds()) {
            throw MeridianErrors.rejectLifespan(LifespanRejectionReason.NUMERIC_LIMIT);
        }

        // 所有规则通过后返回新快照，原快照始终保持不变。
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), body.organStates(), body.meridianStates(), body.transits(),
                updated, body.settledAt(), body.revision() + 1, body.actions());
    }
}
