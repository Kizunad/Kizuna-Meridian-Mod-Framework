package dev.kizuna.meridian.core;

/** 六境的目标容量比与基础主动任务位。 */
public enum Realm {
    /** 游炁：炁在脉中零散游走，和外界的游离灵气是同一种状态。 */
    WANDERING_QI(0.01, 1),
    /** 真炁：炼成真炁，能在经脉里运转周天。 */
    TRUE_QI(0.1, 2),
    /** 化元：炁化为元，真元有了重量，能在脉中流动。 */
    ESSENCE_TRANSFORMATION(1, 3),
    /** 凝元：元凝成实体。 */
    ESSENCE_CONDENSATION(10, 4),
    /** 天纹：真元排列成纹，和天地的纹理同构。 */
    HEAVENLY_PATTERN(100, 5),
    /** 化虚：天纹在高密度下耦合干涉，相长时极实，相消时化实为虚。 */
    VOID_TRANSFORMATION(1000, 6);

    private final double capacityRatio;
    private final int controlTasks;

    Realm(double capacityRatio, int controlTasks) {
        // 保存各境的目标容量比和基础主动任务位数。
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
