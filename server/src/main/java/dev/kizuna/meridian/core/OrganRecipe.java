package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 一次器官加工的固定剂量定义；扩展包声明内容，执行不依赖扩展包代码。 */
public record OrganRecipe(DefinitionId id, int version, DefinitionId organKind, QiInventory input,
                          QiInventory output, long durationMillis, Realm minimumRealm) {
    public OrganRecipe {
        // 配方只允许守恒转换，差额在完成时作为无属性游离态返回环境。
        MeridianErrors.required(id, Data.RECIPE_REQUIRED);
        MeridianErrors.required(organKind, Data.ORGAN_KIND_REQUIRED);
        MeridianErrors.required(input, Data.QI_REQUIRED);
        MeridianErrors.required(output, Data.QI_REQUIRED);
        MeridianErrors.required(minimumRealm, Data.REALM_REQUIRED);
        // 内容版本和时长有效之后，分别检查输入非空与转换守恒。
        boolean invalidVersionOrDuration = version <= 0 || durationMillis <= 0;
        double inputQi = input.totalQi();
        double outputQi = output.totalQi();
        boolean invalidQiConversion = inputQi <= 0 || outputQi > inputQi;
        if (invalidVersionOrDuration || invalidQiConversion) {
            throw MeridianErrors.invalid(Data.INVALID_RECIPE);
        }
    }
}
