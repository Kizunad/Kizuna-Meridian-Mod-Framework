package dev.kizuna.meridian.core;

/** 六境的目标容量比与基础主动任务位；不承担升境判定和占位结算。 */
public enum Realm {
    AWAKENING(0.01, 1),
    QI_GUIDANCE(0.1, 2),
    MERIDIAN_CONDENSATION(1, 3),
    ESSENCE_SOLIDIFICATION(10, 4),
    SPIRITUAL_ATTUNEMENT(100, 5),
    VOID_TRANSFORMATION(1000, 6);

    private final double capacityRatio;
    private final int controlTasks;

    Realm(double capacityRatio, int controlTasks) {
        // 保存各境基础目标，不根据当前真元填充量自动升境。
        this.capacityRatio = capacityRatio;
        this.controlTasks = controlTasks;
    }

    public double capacityRatio() {
        // 返回相对于理论液态参照的目标容量比，不是真元余额。
        return capacityRatio;
    }

    public int controlTasks() {
        // 这里只给出基础任务位数，任务占用和本能养护由运行层处理。
        return controlTasks;
    }
}
