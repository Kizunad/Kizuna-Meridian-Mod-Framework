package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 宿主模拟时间游标；elapsedMillis 仅在同一时钟域和世代内可比较。 */
public record TimeCursor(DefinitionId domain, String epoch, long elapsedMillis) {
    public TimeCursor {
        // 时间必须附带域与世代，宿主重建时间基准后不能直接与旧位置相减。
        MeridianErrors.required(domain, Data.TIME_DOMAIN_REQUIRED);
        DataChecks.text(epoch, "时钟世代");
        if (elapsedMillis < 0) {
            throw MeridianErrors.invalid(Data.NEGATIVE_TIME);
        }
    }
}
