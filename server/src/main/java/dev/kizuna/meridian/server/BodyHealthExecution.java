package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyDeath;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.OpeningRuntime;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.VitalityState;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianState;
import dev.kizuna.meridian.protocol.pb.HealthMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

import java.util.List;

/** 只执行已确定的局部变化；伤害算法与死亡判断由策略的 DamageResolution 提供。 */
final class BodyHealthExecution {
    private BodyHealthExecution() {
        // 候选计算不写存储，提交与重试统一由身体事务处理。
    }

    record Result(BodySnapshot body, HealthResult fact) { }

    static Result calculate(BodySnapshot body, BodyCommand command,
                            DamageResolution resolution) {
        // 先验证完整输入与权威状态，拒绝复活或绕过版本上限。
        HealthProtobuf.validateCommand(command);
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectHealth(FailureCode.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectHealth(FailureCode.REVISION_EXHAUSTED);
        }
        var candidate = BodyProtobuf.toMessage(body).toBuilder().setRevision(body.revision() + 1);
        boolean died = false;
        boolean deathPrevented = false;

        // 只修改命令或固定处理结果显式列出的账户；相邻组织、部位没有隐式连带变化。
        switch (command.getOperationCase()) {
            case CONFIRM_BODY_DEATH -> {
                // 外部死亡已由可信宿主确认；保留各局部 HP，统一清空活动与释放真元。
                died = true;
            }
            case DAMAGE_BODY -> {
                if (command.getDamageBody().hasHit()) {
                    maximumHp(candidate.getStructure(), command.getDamageBody().getHit());
                }
                HealthProtobuf.validateResolution(resolution);
                for (HpLoss loss : resolution.getLossesList()) {
                    changeHp(candidate, loss.getTarget(), loss.getHp(), false);
                }
                died = resolution.getLethal();
                if (command.getDamageBody().hasDeathProtection()) {
                    // 即使没有致死也校验完整保命方案，不能把非法账户藏在未执行的分支。
                    for (Healing floor : command.getDamageBody().getDeathProtection().getMinimumHpList()) {
                        double maximum = maximumHp(candidate.getStructure(), floor.getTarget());
                        if (floor.getHp() > maximum) {
                            throw MeridianErrors.rejectHealth(FailureCode.CAPACITY_EXCEEDED);
                        }
                        if (died) {
                            restoreMinimumHp(candidate, floor);
                        }
                    }
                    deathPrevented = died;
                    died = false;
                }
            }
            case HEAL_BODY -> {
                for (Healing dose : command.getHealBody().getDosesList()) {
                    changeHp(candidate, dose.getTarget(), dose.getHp(), true);
                }
            }
            case RESTORE_FATIGUE -> {
                for (FatigueRelief dose : command.getRestoreFatigue().getDosesList()) {
                    restoreFatigue(candidate, dose);
                }
            }
            case SET_PART_HEALTH_LIMITS -> {
                for (PartHealthLimit limit : command.getSetPartHealthLimits().getLimitsList()) {
                    var definition = candidate.getStructure().getPartsList().stream()
                            .filter(part -> part.getId().equals(limit.getPartId())).findFirst()
                            .orElseThrow(() -> MeridianErrors.rejectHealth(FailureCode.MODULE_NOT_FOUND));
                    var structure = candidate.getStructure().toBuilder();
                    structure.setParts(structure.getPartsList().indexOf(definition), definition.toBuilder().setMaxHp(limit.getMaxHp()));
                    candidate.setStructure(structure);
                    // 扩大上限不白送 HP；效果移除时，超出新上限的存量直接裁剪。
                    PartState state = candidate.getPartStatesList().stream()
                            .filter(part -> part.getId().equals(limit.getPartId())).findFirst().orElseThrow();
                    candidate.setPartStates(candidate.getPartStatesList().indexOf(state),
                            state.toBuilder().setHp(Math.min(state.getHp(), limit.getMaxHp())));
                }
            }
            default -> throw MeridianErrors.invalid(MeridianErrors.Health.REQUEST);
        }

        // 死亡只清一次全部真元；非致死失能立即中断无法继续执行的增压任务。
        BodySnapshot updated = BodyProtobuf.fromMessage(candidate.build());
        QiInventory released = QiInventory.empty();
        if (died) {
            BodyDeath.Released death = BodyDeath.release(updated.meridianStates(), updated.organStates(), updated.transits());
            VitalityState vitality = updated.vitality();
            updated = new BodySnapshot(updated.schemaVersion(), updated.id(), updated.template(),
                    updated.structure(), updated.realm(), updated.partStates(), updated.tissueStates(), death.organs(),
                    death.meridians(), List.of(), new VitalityState(vitality.ageSeconds(), vitality.lifespanLimitSeconds(),
                    vitality.spentLifespanSeconds(), true), updated.settledAt(), updated.revision(), List.of(), null);
            released = death.qi();
        } else if (command.hasDamageBody()) {
            BodyTimeAdvancement.Result interrupted = OpeningRuntime.interruptUnavailable(updated);
            updated = interrupted.body();
            released = interrupted.releasedQi();
        }

        var fact = HealthResult.newBuilder().setDied(died).setDeathPrevented(deathPrevented)
                .setReleasedQi(BodyProtobuf.toInventory(released));
        if (command.hasDamageBody()) {
            // 固定原处理结果；事务复算和恢复不依赖 Addon 当前版本或随机数。
            fact.setDamageResolution(resolution);
        }
        return new Result(updated, fact.build());
    }

    private static void restoreMinimumHp(BodyMessages.BodySnapshot.Builder body, Healing floor) {
        // 只有活体本次即将致死的事务可执行；这是防止死亡，并非复活已有死体。
        HealthTarget target = floor.getTarget();
        double current = switch (target.getModuleCase()) {
            case PART_ID -> body.getPartStatesList().stream()
                    .filter(state -> state.getId().equals(target.getPartId()))
                    .findFirst().orElseThrow().getHp();
            case TISSUE_ID -> body.getTissueStatesList().stream()
                    .filter(state -> state.getId().equals(target.getTissueId()))
                    .findFirst().orElseThrow().getHp();
            case ORGAN_ID -> body.getOrganStatesList().stream()
                    .filter(state -> state.getId().equals(target.getOrganId()))
                    .findFirst().orElseThrow().getHp();
            case MODULE_NOT_SET -> throw MeridianErrors.invalid(MeridianErrors.Health.REQUEST);
        };
        if (current < floor.getHp()) {
            changeHp(body, target, floor.getHp() - current, true);
        }
    }

    private static void changeHp(BodyMessages.BodySnapshot.Builder body,
                                 HealthTarget target, double amount, boolean healing) {
        // 上限与目标都来自实际构型，不能用模板新版替换既有角色的独立状态。
        double maximum = maximumHp(body.getStructure(), target);
        switch (target.getModuleCase()) {
            case PART_ID -> {
                PartState state = body.getPartStatesList().stream()
                        .filter(part -> part.getId().equals(target.getPartId())).findFirst().orElseThrow();
                double hp = adjustedHp(state.getHp(), maximum, amount, healing);
                body.setPartStates(body.getPartStatesList().indexOf(state), state.toBuilder().setHp(hp));
            }
            case TISSUE_ID -> {
                TissueState state = body.getTissueStatesList().stream()
                        .filter(tissue -> tissue.getId().equals(target.getTissueId())).findFirst().orElseThrow();
                double hp = adjustedHp(state.getHp(), maximum, amount, healing);
                body.setTissueStates(body.getTissueStatesList().indexOf(state), state.toBuilder().setHp(hp));
            }
            case ORGAN_ID -> {
                OrganState state = body.getOrganStatesList().stream()
                        .filter(organ -> organ.getId().equals(target.getOrganId())).findFirst().orElseThrow();
                double hp = adjustedHp(state.getHp(), maximum, amount, healing);
                body.setOrganStates(body.getOrganStatesList().indexOf(state), state.toBuilder().setHp(hp));
            }
            case MODULE_NOT_SET -> throw MeridianErrors.invalid(MeridianErrors.Health.REQUEST);
        }
    }

    private static double maximumHp(BodyMessages.BodyStructure structure,
                                    HealthTarget target) {
        // 缺少目标是业务拒绝，不能将一个拼错的 ID 当作无伤害或治疗成功。
        return switch (target.getModuleCase()) {
            case PART_ID -> structure.getPartsList().stream().filter(part -> part.getId().equals(target.getPartId()))
                    .findFirst().orElseThrow(() -> MeridianErrors.rejectHealth(FailureCode.MODULE_NOT_FOUND)).getMaxHp();
            case TISSUE_ID -> structure.getTissuesList().stream().filter(tissue -> tissue.getId().equals(target.getTissueId()))
                    .findFirst().orElseThrow(() -> MeridianErrors.rejectHealth(FailureCode.MODULE_NOT_FOUND)).getMaxHp();
            case ORGAN_ID -> structure.getOrgansList().stream().filter(organ -> organ.getId().equals(target.getOrganId()))
                    .findFirst().orElseThrow(() -> MeridianErrors.rejectHealth(FailureCode.MODULE_NOT_FOUND)).getMaxHp();
            case MODULE_NOT_SET -> throw MeridianErrors.invalid(MeridianErrors.Health.REQUEST);
        };
    }

    private static double adjustedHp(double current, double maximum, double amount, boolean healing) {
        // 过量伤害止于零，过量治疗止于上限；可见剂量不能因浮点精度丢失而悄悄消耗。
        double room = current;
        if (healing) {
            room = maximum - current;
        }
        double effectiveAmount = Math.min(room, amount);
        double hp = current - effectiveAmount;
        if (healing) {
            hp = Math.min(maximum, current + effectiveAmount);
        }
        if (effectiveAmount > 0 && hp == current) {
            throw MeridianErrors.rejectHealth(FailureCode.NUMERIC_LIMIT);
        }
        return hp;
    }

    private static void restoreFatigue(BodyMessages.BodySnapshot.Builder body,
                                       FatigueRelief dose) {
        // 主动恢复不读取动作占用；药效可以在使用组织时生效，且不结束原动作。
        switch (dose.getModuleCase()) {
            case TISSUE_ID -> {
                TissueState state = body.getTissueStatesList().stream()
                        .filter(tissue -> tissue.getId().equals(dose.getTissueId())).findFirst()
                        .orElseThrow(() -> MeridianErrors.rejectHealth(FailureCode.MODULE_NOT_FOUND));
                double fatigue = reducedFatigue(state.getFatigue(), dose.getAmount());
                body.setTissueStates(body.getTissueStatesList().indexOf(state), state.toBuilder().setFatigue(fatigue));
            }
            case ORGAN_ID -> {
                OrganState state = body.getOrganStatesList().stream()
                        .filter(organ -> organ.getId().equals(dose.getOrganId())).findFirst()
                        .orElseThrow(() -> MeridianErrors.rejectHealth(FailureCode.MODULE_NOT_FOUND));
                OrganDefinition definition = body.getStructure().getOrgansList().stream()
                        .filter(organ -> organ.getId().equals(state.getId())).findFirst().orElseThrow();
                if (!definition.hasEndurance()) {
                    throw MeridianErrors.rejectHealth(FailureCode.MODULE_UNAVAILABLE);
                }
                double fatigue = reducedFatigue(state.getFatigueJoules(), dose.getAmount());
                body.setOrganStates(body.getOrganStatesList().indexOf(state), state.toBuilder().setFatigueJoules(fatigue));
            }
            case MERIDIAN_ID -> {
                MeridianState state = body.getMeridianStatesList().stream()
                        .filter(meridian -> meridian.getId().equals(dose.getMeridianId())).findFirst()
                        .orElseThrow(() -> MeridianErrors.rejectHealth(FailureCode.MODULE_NOT_FOUND));
                double fatigue = reducedFatigue(state.getFatigue(), dose.getAmount());
                body.setMeridianStates(body.getMeridianStatesList().indexOf(state), state.toBuilder().setFatigue(fatigue));
            }
            case MODULE_NOT_SET -> throw MeridianErrors.invalid(MeridianErrors.Health.REQUEST);
        }
    }

    private static double reducedFatigue(double fatigue, double amount) {
        // 疲劳按自身单位扣除，超出的恢复量不转成 HP、精力或真元。
        double effectiveAmount = Math.min(fatigue, amount);
        double remaining = fatigue - effectiveAmount;
        if (effectiveAmount > 0 && remaining == fatigue) {
            throw MeridianErrors.rejectHealth(FailureCode.NUMERIC_LIMIT);
        }
        return remaining;
    }
}
