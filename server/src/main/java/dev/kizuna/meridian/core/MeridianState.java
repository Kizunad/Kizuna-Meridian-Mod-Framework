package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 经脉的两类独立账户；有效结构参数在实际构型中，变更共用身体 revision。 */
public record MeridianState(Anatomy.MeridianId id, QiInventory lumenQi, QiInventory wallQi,
                            double fatigue, Condition condition) {
    public MeridianState {
        // 内腔与外壁独立持有明细，禁止用空引用表示尚未初始化的账户。
        MeridianErrors.required(id, Data.MERIDIAN_ID_REQUIRED);
        MeridianErrors.required(lumenQi, Data.LUMEN_QI_REQUIRED);
        MeridianErrors.required(wallQi, Data.WALL_QI_REQUIRED);
        DataChecks.nonNegative(fatigue, "经脉疲劳");
        MeridianErrors.required(condition, Data.MERIDIAN_CONDITION_REQUIRED);
        // 两个有限账户相加也可能溢出，不能只信任局部校验。
        DataChecks.nonNegative(lumenQi.totalQi() + wallQi.totalQi(), "经脉总量");
    }

    public double totalQi() {
        // 这里只统计该经脉持有的真元，在途记录归身体快照单独统计。
        return lumenQi.totalQi() + wallQi.totalQi();
    }

    public enum Condition {
        INTACT,
        LEAKING,
        SEVERED
    }
}
