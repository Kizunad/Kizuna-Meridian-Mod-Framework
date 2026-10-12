package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 单条经脉首次建立意念响应的阶段与进度，独立于储量、损伤和后续结构成长。 */
public record MeridianOpening(Stage stage, double progress) {
    public MeridianOpening {
        // 进度使用无量纲比例；严格限制到 1，避免舍入容差把未完成误记为完成。
        MeridianErrors.required(stage, Data.OPENING_STAGE_REQUIRED);
        if (!Double.isFinite(progress) || progress < 0 || progress > 1) {
            throw MeridianErrors.invalid(Data.OPENING_PROGRESS_OUT_OF_RANGE);
        }

        // 未开始与刚开始均可为零；中途阶段可在暂停后保留，完成阶段必须显式记为 1。
        boolean progressMatchesStage = switch (stage) {
            case UNOPENED -> progress == 0;
            case IN_PROGRESS -> progress < 1;
            case OPENED -> progress == 1;
        };
        if (!progressMatchesStage) {
            throw MeridianErrors.invalid(Data.OPENING_STAGE_PROGRESS_MISMATCH);
        }
    }

    public enum Stage {
        UNOPENED,
        IN_PROGRESS,
        OPENED
    }
}
