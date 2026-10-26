package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.ActionProtobuf;
import dev.kizuna.meridian.protocol.CapabilityProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/** 不持有身体状态的可信附属策略目录；规则判断全部交给对应附属。 */
final class OpeningPolicies {
    private static final Map<String, OpeningPolicy> POLICIES = discover();

    private OpeningPolicies() {
        // 目录只保存已安装的执行器，不能替代身体事务或保存进度。
    }

    private static Map<String, OpeningPolicy> discover() {
        // Java 参考宿主通过标准 SPI 发现适配器；适配器也可转发同一协议到其他语言核心。
        var policies = new HashMap<String, OpeningPolicy>();
        for (OpeningPolicy policy : ServiceLoader.load(OpeningPolicy.class)) {
            new DefinitionId(policy.id());
            if (policies.putIfAbsent(policy.id(), policy) != null) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.POLICY_INVALID);
            }
        }
        return Map.copyOf(policies);
    }

    static boolean available(MeridianOpeningRule rule) {
        // 缺失执行器时停机，不将其他生物的规则作为默认兜底。
        return POLICIES.containsKey(rule.policyId().value());
    }

    static OpeningPolicyDecision evaluate(BodySnapshot body, MeridianOpeningRule rule,
                                          OpeningPolicyPhase phase, long intervalMillis) {
        // 只传不可变快照与固定规则；附属得到裁定权，但没有 Store 或可写身体引用。
        var request = OpeningPolicyRequest.newBuilder()
                .setProtocolVersion(1)
                .setBody(BodyProtobuf.toMessage(body))
                .setRule(OpeningProtobuf.toRule(rule))
                .setPhase(phase)
                .setIntervalMillis(intervalMillis)
                .build();
        return evaluate(request);
    }

    static OpeningPolicyDecision evaluate(BodyAdvancement body, MeridianOpeningRule rule,
                                          OpeningPolicyPhase phase, long intervalMillis) {
        // 内部候选的未变消息子树复用，策略检查的次数、顺序和完整可见状态保持一致。
        OpeningPolicyRequest request = body.policy().request(body, rule, phase, intervalMillis);
        return evaluate(request);
    }

    private static OpeningPolicyDecision evaluate(OpeningPolicyRequest request) {
        // 策略返回值每次都通过完整校验，输入则由权威候选及已校验定义映射而来。
        OpeningPolicy policy = POLICIES.get(request.getRule().getPolicyId());
        if (policy == null) {
            throw MeridianErrors.rejectIntake(
                    dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.OPENING_POLICY_UNAVAILABLE);
        }
        OpeningPolicyDecision decision = policy.evaluate(request);
        OpeningProtobuf.validatePolicyDecision(decision);
        if (decision.getRejection() != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            throw MeridianErrors.rejectIntake(decision.getRejection());
        }
        return decision;
    }

    static final class Context {
        private final BodyMessages.BodySnapshot.Builder message;
        private BodyStructure structure;
        private List<AnatomyState.Tissue> tissues;
        private List<AnatomyState.Organ> organs;
        private List<MeridianState> meridians;
        private List<QiTransit> transits;
        private List<BodyAction.Active> actions;
        private List<CapabilityModifier> modifiers;
        private long nextExpirationMillis;
        private VitalityState vitality;
        private TimeCursor time;
        private OpeningTask task;
        private MeridianOpeningRule rule;
        private BodyMessages.MeridianOpeningRule ruleMessage;

        Context(BodySnapshot original) {
            // 起始完整快照只映射和校验一次；后续更新只替换发生变化的子消息。
            message = BodyProtobuf.toMessage(original).toBuilder();
            structure = original.structure();
            tissues = original.tissueStates();
            organs = original.organStates();
            meridians = original.meridianStates();
            transits = original.transits();
            actions = original.actions();
            modifiers = original.modifiers();
            nextExpirationMillis = nextExpiration(modifiers);
            vitality = original.vitality();
            time = original.settledAt();
            task = original.openingTask();
        }

        private OpeningPolicyRequest request(BodyAdvancement body, MeridianOpeningRule currentRule,
                                              OpeningPolicyPhase phase, long intervalMillis) {
            // 只缓存输入表示，不缓存裁定；策略仍逐次读取实际扣费前后的完整身体事实。
            projectModules(body);
            projectProgress(body);
            if (rule != currentRule) {
                rule = currentRule;
                ruleMessage = OpeningProtobuf.toRule(currentRule);
            }
            return OpeningPolicyRequest.newBuilder()
                    .setProtocolVersion(1)
                    .setBody(message.build())
                    .setRule(ruleMessage)
                    .setPhase(phase)
                    .setIntervalMillis(intervalMillis)
                    .build();
        }

        private void projectModules(BodyAdvancement body) {
            // 列表内元素不可变，未变列表可以安全保留已有 Protobuf 子树。
            if (structure != body.structure) {
                message.setStructure(BodyProtobuf.toStructure(body.structure));
                structure = body.structure;
            }
            if (tissues != body.tissues) {
                message.clearTissueStates()
                        .addAllTissueStates(body.tissues.stream().map(BodyProtobuf::toTissueState).toList());
                tissues = body.tissues;
            }
            if (organs != body.organs) {
                message.clearOrganStates()
                        .addAllOrganStates(body.organs.stream().map(BodyProtobuf::toOrganState).toList());
                organs = body.organs;
            }
            if (meridians != body.meridians) {
                projectMeridians(body.meridians);
                meridians = body.meridians;
            }
            if (transits != body.transits) {
                message.clearTransits()
                        .addAllTransits(body.transits.stream().map(BodyProtobuf::toTransit).toList());
                transits = body.transits;
            }
        }

        private void projectProgress(BodyAdvancement body) {
            // 到期阶跃在策略可见状态中同样立即移除；动作解除与任务进度在同一消息中更新。
            projectTime(body);
            if (actions != body.actions) {
                message.clearActions()
                        .addAllActions(body.actions.stream().map(ActionProtobuf::toActive).toList());
                actions = body.actions;
            }
            if (task != body.task) {
                if (body.task == null) {
                    message.clearOpeningTask();
                } else {
                    message.setOpeningTask(taskMessage(body.task));
                }
                task = body.task;
            }
        }

        private void projectTime(BodyAdvancement body) {
            // 同毫秒的多次条件检查复用时间与寿命消息；仅跨到期点时重新映射修正列表。
            if (vitality != body.vitality) {
                message.setVitality(BodyProtobuf.toVitality(body.vitality));
                vitality = body.vitality;
            }
            if (time != body.time) {
                message.setSettledAt(BodyProtobuf.toTime(body.time));
                time = body.time;
            }
            long nowMillis = body.time.elapsedMillis();
            if (modifiers.isEmpty() || nowMillis < nextExpirationMillis) {
                return;
            }
            List<CapabilityModifier> remaining = modifiers.stream()
                    .filter(modifier -> modifier.curve().endMillis() > nowMillis)
                    .toList();
            if (remaining.size() != modifiers.size()) {
                message.clearModifiers()
                        .addAllModifiers(remaining.stream().map(CapabilityProtobuf::toModifier).toList());
                modifiers = remaining;
                nextExpirationMillis = nextExpiration(remaining);
            }
        }

        private static long nextExpiration(List<CapabilityModifier> modifiers) {
            // 到期事件数最多为修正条数，普通拐点不扫描或重建整份消息列表。
            return modifiers.stream().mapToLong(modifier -> modifier.curve().endMillis()).min().orElse(Long.MAX_VALUE);
        }

        private void projectMeridians(List<MeridianState> current) {
            // 时间推进保持经脉 ID 和顺序不变；适应进度改变不重编未变的真元明细。
            for (int index = 0; index < current.size(); index++) {
                MeridianState state = current.get(index);
                MeridianState previous = meridians.get(index);
                if (state == previous) {
                    continue;
                }
                var opening = BodyMessages.MeridianOpening.newBuilder()
                        .setStage(BodyMessages.MeridianOpeningStage.valueOf(state.opening().stage().name()))
                        .setProgress(state.opening().progress())
                        .build();
                var builder = message.getMeridianStates(index).toBuilder()
                        .setFatigue(state.fatigue())
                        .setCondition(BodyMessages.MeridianCondition.valueOf(state.condition().name()))
                        .setOpening(opening);
                if (state.lumenQi() != previous.lumenQi()) {
                    builder.setLumenQi(BodyProtobuf.toInventory(state.lumenQi()));
                }
                if (state.wallQi() != previous.wallQi()) {
                    builder.setWallQi(BodyProtobuf.toInventory(state.wallQi()));
                }
                message.setMeridianStates(index, builder.build());
            }
        }

        private BodyMessages.OpeningTask taskMessage(OpeningTask current) {
            // 推进只能改变进度、状态和收据；固定规则与引导计划复用初次校验的消息。
            if (task == null) {
                return OpeningProtobuf.toTask(current);
            }
            var builder = message.getOpeningTask().toBuilder()
                    .setHeldMillis(current.heldMillis())
                    .setStatus(BodyMessages.OpeningTaskStatus.valueOf("OPENING_" + current.status().name()));
            if (!task.intakeSamples().equals(current.intakeSamples())) {
                builder.clearIntakeSamples();
                for (OpeningTask.IntakeSample sample : current.intakeSamples()) {
                    var intake = BodyMessages.OpeningIntakeSample.newBuilder()
                            .setOrganId(sample.organId().value())
                            .setAtMillis(sample.atMillis())
                            .setQi(sample.qi())
                            .build();
                    builder.addIntakeSamples(intake);
                }
            }
            return builder.build();
        }
    }
}
