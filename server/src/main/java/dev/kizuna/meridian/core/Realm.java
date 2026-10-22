package dev.kizuna.meridian.core;

/** 六境的相态基准密度与基础主动任务位；既有容量比数值沿用为基准密度。 */
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
        // 原容量比现解释为液态参照下的相态基准密度。
        this.capacityRatio = capacityRatio;
        this.controlTasks = controlTasks;
    }

    public double capacityRatio() {
        // 保留公开名称与数值；返回该境相态的基准密度，不表示结构容量。
        return capacityRatio;
    }

    public static Qi.QiPhase phaseAtDensity(double density) {
        // 与客户端采用同一边界，避免界面相态与服务端判定不一致。
        var phase = dev.kizuna.meridian.protocol.QiDensity.phase(density);
        return Qi.QiPhase.valueOf(phase.name());
    }

    public int controlTasks() {
        // 这里只给出基础任务位数，任务占用和本能养护由运行层处理。
        return controlTasks;
    }
}
