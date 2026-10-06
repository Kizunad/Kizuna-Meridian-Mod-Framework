package dev.kizuna.meridian.core;

import java.util.Objects;

/** 宿主模拟时间游标；elapsedMillis 仅在同一时钟域和世代内可比较。 */
public record TimeCursor(DefinitionId domain, String epoch, long elapsedMillis) {
    public TimeCursor {
        // 时间必须附带域与世代，宿主重建时间基准后不能直接与旧位置相减。
        Objects.requireNonNull(domain, "时钟域不能为空");
        DataChecks.text(epoch, "时钟世代");
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("模拟时间不能为负数");
        }
    }
}
