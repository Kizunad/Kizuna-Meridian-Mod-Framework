package dev.kizuna.meridian.core;

import java.util.Objects;

/** 已从源账户扣除、尚未进入目标账户的真元；到达时间沿用所属快照时钟。 */
public record QiTransit(String id, Anatomy.ConnectionId connectionId, QiInventory cargo,
                        long arrivalMillis) {
    public QiTransit {
        // 在途必须有实际货量与线路；线路存在性和到达时序由完整快照验证。
        DataChecks.text(id, "在途记录标识");
        Objects.requireNonNull(connectionId, "在途连接不能为空");
        Objects.requireNonNull(cargo, "在途真元不能为空");
        DataChecks.positive(cargo.totalQi(), "在途真元数量");
        if (arrivalMillis < 0) {
            throw new IllegalArgumentException("到达时间不能为负数");
        }
    }
}
