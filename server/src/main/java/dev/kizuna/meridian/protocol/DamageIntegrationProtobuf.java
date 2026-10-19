package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationContext;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationPlan;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.DamageIntegrationCommit;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageContext;

/** 伤害与效果附属之间的有界两阶段协议，不持有额外身体账本。 */
public final class DamageIntegrationProtobuf {
    private DamageIntegrationProtobuf() {
        // 方案只读，提交通知在身体成功提交后发送一次。
    }

    public static byte[] encodeContext(DamageIntegrationContext context) {
        // 复用健康协议校验真实身体和最终伤害剂量。
        validateContext(context);
        return ProtoWire.write(context);
    }

    public static DamageIntegrationContext decodeContext(byte[] bytes) {
        // 集成方不能使用未通过格式和身体约束的局部上下文。
        var context = ProtoWire.read(bytes, DamageIntegrationContext.parser());
        validateContext(context);
        return context;
    }

    private static void validateContext(DamageIntegrationContext context) {
        // 这里需要已固定命中，防止吸收在一个部位扣除、伤害却落到另一个部位。
        ProtoWire.validate(context);
        HealthProtobuf.encodeContext(DamageContext.newBuilder().setBody(context.getBody()).setDamage(context.getDamage()).build());
        ProtoWire.require(context.getDamage().hasHit(), MeridianErrors.Health.HOST_EXCHANGE);
    }

    public static byte[] encodePlan(DamageIntegrationPlan plan) {
        // 不认领时返回显式 handled=false；省略保命是正常情况。
        validatePlan(plan);
        return ProtoWire.write(plan);
    }

    public static DamageIntegrationPlan decodePlan(byte[] bytes) {
        // 字节解析与业务校验共同保护跨附属边界。
        var plan = ProtoWire.read(bytes, DamageIntegrationPlan.parser());
        validatePlan(plan);
        return plan;
    }

    private static void validatePlan(DamageIntegrationPlan plan) {
        // 计划允许完全吸收的零剂量，但保命方案仍须满足通用健康校验。
        ProtoWire.validate(plan);
        if (plan.hasDeathProtection()) {
            HealthProtobuf.validateProtection(plan.getDeathProtection());
        }
    }

    public static byte[] encodeCommit(DamageIntegrationCommit commit) {
        // 固定提交前方案与提交后快照，效果资源只依据同一次事实消费。
        validateCommit(commit);
        return ProtoWire.write(commit);
    }

    public static DamageIntegrationCommit decodeCommit(byte[] bytes) {
        // 提交通知同样走有界 Protobuf，不接受旁路 Java 状态覆盖。
        var commit = ProtoWire.read(bytes, DamageIntegrationCommit.parser());
        validateCommit(commit);
        return commit;
    }

    private static void validateCommit(DamageIntegrationCommit commit) {
        // 身体身份不变，伤害只能被削减，禁用保命的来源不能被计划绕过。
        ProtoWire.validate(commit);
        validateContext(commit.getContext());
        validatePlan(commit.getPlan());
        BodyProtobuf.fromMessage(commit.getBody());
        var context = commit.getContext();
        var plan = commit.getPlan();
        ProtoWire.require(plan.getHandled() && plan.getRemainingDamageHp() <= context.getDamage().getDamageHp()
                && context.getBody().getId().equals(commit.getBody().getId())
                && (!context.getBypassesDeathProtection() || !plan.hasDeathProtection())
                && (!commit.getDeathPrevented() || plan.hasDeathProtection()), MeridianErrors.Health.HOST_EXCHANGE);
    }
}
