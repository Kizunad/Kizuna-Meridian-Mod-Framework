package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 已从源账户扣除、尚未进入目标账户的真元；到达时间沿用所属快照时钟。 */
public record QiTransit(String id, Anatomy.ConnectionId connectionId, QiInventory cargo,
                        long arrivalMillis, double throughputPerSecond, Status status,
                        dev.kizuna.meridian.protocol.pb.BodyMessages.ControlledCircuit circuit) {
    public QiTransit(String id, Anatomy.ConnectionId connectionId, QiInventory cargo,
                     long arrivalMillis, double throughputPerSecond, Status status) {
        // 普通单段运输不携带闭环控制事实。
        this(id, connectionId, cargo, arrivalMillis, throughputPerSecond, status, null);
    }

    public QiTransit {
        // 在途必须有实际货量与线路；线路存在性和到达时序由完整快照验证。
        DataChecks.text(id, "在途记录标识");
        MeridianErrors.required(connectionId, Data.TRANSIT_CONNECTION_REQUIRED);
        MeridianErrors.required(cargo, Data.TRANSIT_QI_REQUIRED);
        DataChecks.positive(cargo.totalQi(), "在途真元数量");
        DataChecks.positive(throughputPerSecond, "预约吞吐");
        MeridianErrors.required(status, Data.TRANSIT_STATUS_REQUIRED);
        if (arrivalMillis < 0) {
            throw MeridianErrors.invalid(Data.NEGATIVE_ARRIVAL);
        }
    }

    public enum Status { MOVING, BLOCKED_ROUTE, BLOCKED_CAPACITY }
}
