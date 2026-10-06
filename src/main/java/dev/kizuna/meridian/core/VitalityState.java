package dev.kizuna.meridian.core;

/** 年龄、寿命和死亡状态的最小核心定义。 */
public record VitalityState(double ageSeconds, double lifespanLimitSeconds,
                            double spentLifespanSeconds, boolean dead) {
    public VitalityState {
        // 实际年龄与额外寿元支出分别记录，避免燃烧寿元被误当成自然老化。
        DataChecks.nonNegative(ageSeconds, "年龄");
        DataChecks.nonNegative(lifespanLimitSeconds, "寿命上限");
        DataChecks.nonNegative(spentLifespanSeconds, "已耗寿元");
    }

    public double remainingSeconds() {
        // 展示剩余寿命时下限为零，死亡处理由运行层显式执行。
        return Math.max(0, lifespanLimitSeconds - ageSeconds - spentLifespanSeconds);
    }
}
