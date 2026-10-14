package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;
import dev.kizuna.meridian.error.MeridianErrors.Protocol;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

/** 可信时间事实与结算结果的 Protobuf 映射；不授予上传时间的客户端权限。 */
public final class TimeProtobuf {
    private TimeProtobuf() {
        // 时间数据沿用同一协议边界，不维护第二种跨语言格式。
    }

    public static byte[] encodePlan(MeridianMessages.TimeAdvancePlan plan) {
        // 宿主跨进程传递时间事实也必须经过结构和数值校验。
        fromMessage(plan);
        return ProtoWire.write(plan);
    }

    public static MeridianMessages.TimeAdvancePlan decodePlan(byte[] payload) {
        // 只供已认证的宿主内部通信，不能注册到客户端请求路由。
        var plan = ProtoWire.read(payload, MeridianMessages.TimeAdvancePlan.parser());
        fromMessage(plan);
        return plan;
    }

    public static BodyTimeAdvancement.Plan fromMessage(MeridianMessages.TimeAdvancePlan plan) {
        // 先拒绝缺失字段和未知枚举，再构造与通信库无关的核心值。
        ProtoWire.validate(plan);
        return new BodyTimeAdvancement.Plan(BodyProtobuf.fromTime(plan.getTarget()),
                plan.getBiologicalSecondsPerSimulationSecond(),
                BodyTimeAdvancement.Mode.valueOf(plan.getMode().name()),
                plan.getRestingTissueIdsList().stream().map(Anatomy.TissueId::new).toList());
    }

    public static MeridianMessages.TimeAdvancePlan toMessage(BodyTimeAdvancement.Plan plan) {
        // 序列化保留时钟域、世代、整数毫秒以及显式零倍率。
        MeridianErrors.required(plan, Data.TIME_PLAN_REQUIRED);
        var message = MeridianMessages.TimeAdvancePlan.newBuilder().setTarget(BodyProtobuf.toTime(plan.target()))
                .setBiologicalSecondsPerSimulationSecond(plan.biologicalSecondsPerSimulationSecond())
                .setMode(MeridianMessages.TimeAdvanceMode.valueOf(plan.mode().name()))
                .addAllRestingTissueIds(plan.restingTissueIds().stream().map(Anatomy.TissueId::value).toList()).build();
        ProtoWire.validate(message);
        return message;
    }

    public static MeridianMessages.TimeAdvanceResult toMessage(MeridianMessages.TimeAdvancePlan plan,
                                                               BodyTimeAdvancement.Result result) {
        // 记录实际采用的宿主事实，恢复时无需再次向已变化的时钟询问过去。
        MeridianErrors.required(result, Data.TIME_RESULT_REQUIRED);
        var message = MeridianMessages.TimeAdvanceResult.newBuilder().setPlan(plan)
                .setSettledAt(BodyProtobuf.toTime(result.body().settledAt())).setDied(result.died())
                .setReleasedQi(BodyProtobuf.toInventory(result.releasedQi())).build();
        validateResult(message);
        return message;
    }

    public static void validateResult(MeridianMessages.TimeAdvanceResult result) {
        // 独立回执只能检查自身关系；与原身体的完整一致性还须在宿主事务 stage 内检查。
        ProtoWire.validate(result);
        BodyTimeAdvancement.Plan plan = fromMessage(result.getPlan());
        TimeCursor settledAt = BodyProtobuf.fromTime(result.getSettledAt());
        QiInventory releasedQi = BodyProtobuf.fromInventory(result.getReleasedQi());

        // 结算必须属于同一时钟，且不能越过宿主指定的目标时刻。
        boolean sameClock = settledAt.domain().equals(plan.target().domain())
                && settledAt.epoch().equals(plan.target().epoch());
        ProtoWire.require(sameClock, Protocol.INVALID_TIME_RESULT);
        ProtoWire.require(settledAt.elapsedMillis() <= plan.target().elapsedMillis(), Protocol.INVALID_TIME_RESULT);

        // 只有死亡允许提前结束区间；活体必须完整结算到目标时刻。
        if (!result.getDied()) {
            ProtoWire.require(settledAt.equals(plan.target()), Protocol.INVALID_TIME_RESULT);
        }

        // 冻结模式下没有加工损耗；只有死亡清账才允许释放真元。
        if (plan.mode() != BodyTimeAdvancement.Mode.ACTIVE && !result.getDied()) {
            ProtoWire.require(releasedQi.entries().isEmpty(), Protocol.INVALID_TIME_RESULT);
        }
    }
}
