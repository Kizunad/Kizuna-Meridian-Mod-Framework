package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;

import java.util.HashSet;

/** 伤害插件和受控健康操作共用的有界 Protobuf 契约。 */
public final class HealthProtobuf {
    private HealthProtobuf() {
        // 静态边界只校验格式，不决定伤害分配、连带损伤或生物死亡条件。
    }

    public static boolean isHealthCommand(BodyCommand command) {
        // 宿主可信入口与回执都使用同一操作集合，避免新操作漏过默认拒绝。
        return command.hasDamageBody() || command.hasHealBody() || command.hasRestoreFatigue()
                || command.hasConfirmBodyDeath() || command.hasSetPartHealthLimits();
    }

    public static void validateCommand(BodyCommand command) {
        // 倍率或配方由附属处理，框架只接受有明确来源和唯一目标的有限正数剂量。
        var targets = new HashSet<Object>();
        switch (command.getOperationCase()) {
            case DAMAGE_BODY -> validateDamage(command.getDamageBody());
            case CONFIRM_BODY_DEATH -> new DefinitionId(command.getConfirmBodyDeath().getSourceId());
            case SET_PART_HEALTH_LIMITS -> {
                var request = command.getSetPartHealthLimits();
                new DefinitionId(request.getSourceId());
                require(request.getLimitsCount() > 0 && request.getLimitsCount() <= 256);
                for (PartHealthLimit limit : request.getLimitsList()) {
                    new Anatomy.PartId(limit.getPartId());
                    require(targets.add(limit.getPartId()) && positive(limit.getMaxHp()));
                }
            }
            case HEAL_BODY -> {
                HealBody request = command.getHealBody();
                new DefinitionId(request.getSourceId());
                require(request.getDosesCount() > 0 && request.getDosesCount() <= 256);
                for (Healing dose : request.getDosesList()) {
                    validateTarget(dose.getTarget());
                    require(targets.add(dose.getTarget()));
                    require(positive(dose.getHp()));
                }
            }
            case RESTORE_FATIGUE -> {
                RestoreFatigue request = command.getRestoreFatigue();
                new DefinitionId(request.getSourceId());
                require(request.getDosesCount() > 0 && request.getDosesCount() <= 32);
                for (FatigueRelief dose : request.getDosesList()) {
                    switch (dose.getModuleCase()) {
                        case TISSUE_ID -> new Anatomy.TissueId(dose.getTissueId());
                        case ORGAN_ID -> new Anatomy.OrganId(dose.getOrganId());
                        case MERIDIAN_ID -> new Anatomy.MeridianId(dose.getMeridianId());
                        case MODULE_NOT_SET -> throw MeridianErrors.invalid(MeridianErrors.Health.REQUEST);
                    }
                    // 清除剂量后比较身份，同一目标不能通过不同剂量重复入批。
                    require(targets.add(dose.toBuilder().clearAmount().build()));
                    require(positive(dose.getAmount()));
                }
            }
            default -> throw MeridianErrors.invalid(MeridianErrors.Health.REQUEST);
        }
    }

    public static void validateTarget(HealthTarget target) {
        // 三类 HP 账户相互独立，同名 ID 仍由 oneof 明确区分。
        switch (target.getModuleCase()) {
            case PART_ID -> new Anatomy.PartId(target.getPartId());
            case TISSUE_ID -> new Anatomy.TissueId(target.getTissueId());
            case ORGAN_ID -> new Anatomy.OrganId(target.getOrganId());
            case MODULE_NOT_SET -> throw MeridianErrors.invalid(MeridianErrors.Health.REQUEST);
        }
    }

    private static void validateDamage(DamageBody request) {
        // 可信宿主可以省略命中点交由策略选择；显式空目标仍是格式错误。
        new DefinitionId(request.getSourceId());
        new DefinitionId(request.getDamageType());
        if (request.hasHit()) {
            validateTarget(request.getHit());
        }
        require(request.getKind() != DamageKind.DAMAGE_KIND_UNSPECIFIED
                && request.getKind() != DamageKind.UNRECOGNIZED);
        require(positive(request.getDamageHp()));
        if (request.hasDeathProtection()) {
            validateProtection(request.getDeathProtection());
        }
    }

    public static void validateProtection(DeathProtection protection) {
        // 保命是一次致死伤害的受控下限，不允许空方案或对同一账户重复加血。
        new DefinitionId(protection.getSourceId());
        require(protection.getMinimumHpCount() > 0 && protection.getMinimumHpCount() <= 256);
        var targets = new HashSet<HealthTarget>();
        for (Healing floor : protection.getMinimumHpList()) {
            validateTarget(floor.getTarget());
            require(targets.add(floor.getTarget()) && positive(floor.getHp()));
        }
    }

    public static byte[] encodeContext(DamageContext context) {
        // 跨语言插件拿到固定版本的真实身体，不接收客户端上传的快照。
        validateContext(context);
        return ProtoWire.write(context);
    }

    public static DamageContext decodeContext(byte[] payload) {
        // 与正式请求共用字节数、递归和文本限制。
        DamageContext context = ProtoWire.read(payload, DamageContext.parser());
        validateContext(context);
        return context;
    }

    private static void validateContext(DamageContext context) {
        // 完整快照与伤害输入都须可用，不能把半份身体交给 Addon 决策。
        ProtoWire.validate(context);
        BodyProtobuf.fromMessage(context.getBody());
        validateDamage(context.getDamage());
    }

    public static byte[] encodeResolution(DamageResolution resolution) {
        // 处理结果只含减血意图与死亡判断，不能夹带余额或构型覆盖。
        validateResolution(resolution);
        return ProtoWire.write(resolution);
    }

    public static DamageResolution decodeResolution(byte[] payload) {
        // 返回值是附属的不可信计算候选，仍需完整格式校验和后续身体引用校验。
        DamageResolution resolution = ProtoWire.read(payload, DamageResolution.parser());
        validateResolution(resolution);
        return resolution;
    }

    public static void validateResolution(DamageResolution resolution) {
        // 空损失允许表达免疫；框架不限制各层之和等于输入，以免内置某种伤害模型。
        ProtoWire.validate(resolution);
        ProtoWire.require(resolution.getLossesCount() <= 64, MeridianErrors.Health.RESOLUTION);
        var targets = new HashSet<HealthTarget>();
        for (HpLoss loss : resolution.getLossesList()) {
            validateTarget(loss.getTarget());
            ProtoWire.require(targets.add(loss.getTarget()) && positive(loss.getHp()), MeridianErrors.Health.RESOLUTION);
        }
        var meridians = new HashSet<String>();
        ProtoWire.require(resolution.getMeridianInjuriesCount() <= 128, MeridianErrors.Health.RESOLUTION);
        for (var injury : resolution.getMeridianInjuriesList()) {
            // 经脉伤势只能表达损伤，修复需要另外授权的恢复规则。
            new dev.kizuna.meridian.core.Anatomy.MeridianId(injury.getMeridianId());
            ProtoWire.require(meridians.add(injury.getMeridianId())
                    && (injury.getCondition() == dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianCondition.LEAKING
                    || injury.getCondition() == dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianCondition.SEVERED), MeridianErrors.Health.RESOLUTION);
        }
    }

    public static void validateResult(BodyCommand command, HealthResult result) {
        // 伤害回执固定原方案，外部死亡必须判死，治疗与恢复则不能携带致死结果。
        ProtoWire.validate(result);
        BodyProtobuf.fromInventory(result.getReleasedQi());
        ProtoWire.require(result.hasDamageResolution() == command.hasDamageBody(), MeridianErrors.Health.RECEIPT);
        if (command.hasDamageBody()) {
            validateResolution(result.getDamageResolution());
            boolean prevented = result.getDamageResolution().getLethal() && command.getDamageBody().hasDeathProtection();
            ProtoWire.require(result.getDeathPrevented() == prevented
                    && result.getDied() == (result.getDamageResolution().getLethal() && !prevented), MeridianErrors.Health.RECEIPT);
        } else {
            ProtoWire.require(!result.getDeathPrevented() && result.getDied() == command.hasConfirmBodyDeath(), MeridianErrors.Health.RECEIPT);
        }
    }

    private static boolean positive(double amount) {
        // 生成消息本身允许 NaN 或无穷；直接 Java 调用也须拒绝。
        return Double.isFinite(amount) && amount > 0;
    }

    private static void require(boolean condition) {
        // 输入格式错误统一交给协议层，不把格式错误混为业务拒绝。
        ProtoWire.require(condition, MeridianErrors.Health.REQUEST);
    }
}
