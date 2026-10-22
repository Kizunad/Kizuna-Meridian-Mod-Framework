package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 加工中账户持有 recipe.input；输出只是完成时才能兑现的转换规则。 */
public record OrganProcess(OrganRecipe recipe, QiInventory input, long completionMillis, boolean blocked) {
    public OrganProcess {
        // 保存完整配方事实，卸载附属包也不会丢失输入或改变已启动任务的结果。
        MeridianErrors.required(recipe, Data.RECIPE_REQUIRED);
        MeridianErrors.required(input, Data.QI_REQUIRED);
        QiAccounting.select(input, recipe.input());
        DataChecks.atMost(input.totalQi(), recipe.input().totalQi(), "加工实际原料");
        if (completionMillis <= 0) {
            throw MeridianErrors.invalid(Data.INVALID_PROCESS_TIME);
        }
    }
}
