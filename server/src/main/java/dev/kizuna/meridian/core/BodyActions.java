package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.kizuna.meridian.error.MeridianErrors.CapabilityRejectionReason.*;
import static dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason.*;

/** 已结算时刻的动作启停与占用查询；不包含行走、吐纳等具体动作身份。 */
public final class BodyActions {
    public static final int MAX_ACTIVE_ACTIONS = 32;

    private BodyActions() {
        // 只产生候选身体，可信事件时刻、授权和原子提交仍属于现有命令入口。
    }

    public static BodySnapshot start(BodySnapshot body, String instanceId, BodyAction.Definition definition) {
        // 1. 拒绝失去生命或版本空间的身体，检查实例冲突而非排斥其他并行动作。
        requireMutable(body);
        var action = new BodyAction.Active(instanceId, definition, body.settledAt().elapsedMillis());
        if (body.actions().stream().anyMatch(current -> current.instanceId().equals(instanceId))) {
            throw MeridianErrors.rejectCapability(ACTION_ALREADY_ACTIVE);
        }
        if (body.actions().size() >= MAX_ACTIVE_ACTIONS) {
            throw MeridianErrors.rejectCapability(ACTION_LIMIT_EXCEEDED);
        }

        // 2. 动作引用实例模块，组织或器官及其所属部位均须可工作。
        var parts = DataChecks.index(body.partStates(), AnatomyState.Part::id, "部位");
        var tissues = DataChecks.index(body.structure().tissues(), Anatomy.TissueDefinition::id, "组织定义");
        var tissueStates = DataChecks.index(body.tissueStates(), AnatomyState.Tissue::id, "组织状态");
        for (Anatomy.TissueId tissueId : definition.tissueIds()) {
            Anatomy.TissueDefinition tissue = tissues.get(tissueId);
            if (tissue == null) {
                throw MeridianErrors.rejectCapability(MODULE_NOT_FOUND);
            }
            if (tissueStates.get(tissueId).hp() == 0 || parts.get(tissue.partId()).hp() == 0) {
                throw MeridianErrors.rejectCapability(MODULE_UNAVAILABLE);
            }
        }
        var organs = DataChecks.index(body.structure().organs(), Anatomy.OrganDefinition::id, "器官定义");
        var organStates = DataChecks.index(body.organStates(), AnatomyState.Organ::id, "器官状态");
        for (Anatomy.OrganId organId : definition.organIds()) {
            Anatomy.OrganDefinition organ = organs.get(organId);
            if (organ == null) {
                throw MeridianErrors.rejectCapability(MODULE_NOT_FOUND);
            }
            if (organStates.get(organId).hp() == 0 || parts.get(organ.partId()).hp() == 0) {
                throw MeridianErrors.rejectCapability(MODULE_UNAVAILABLE);
            }
        }

        // 3. 保留其他动作的固定定义与开始时刻，不因占用重叠而覆盖它们。
        var actions = new ArrayList<>(body.actions());
        actions.add(action);
        return replace(body, actions);
    }

    public static BodySnapshot stop(BodySnapshot body, String instanceId) {
        // 结束只依赖保存的实例；内容包卸载或模块受损后也能解除这一份占用。
        requireMutable(body);
        DataChecks.localId(instanceId);
        if (body.openingTask() != null && body.openingTask().active()
                && body.openingTask().actionInstanceId().equals(instanceId)) {
            // 开脉动作只能随任务清理，防止任务仍在运行却失去资源占用。
            throw MeridianErrors.rejectIntake(dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.OPENING_BUSY);
        }
        List<BodyAction.Active> remaining = body.actions().stream()
                .filter(action -> !action.instanceId().equals(instanceId)).toList();
        if (remaining.size() == body.actions().size()) {
            throw MeridianErrors.rejectCapability(ACTION_NOT_ACTIVE);
        }
        return replace(body, remaining);
    }

    public static Set<Anatomy.TissueId> usedTissues(BodySnapshot body) {
        // 占用取并集，结束一个动作不会让仍被另一个动作使用的肌肉开始恢复。
        var used = new HashSet<Anatomy.TissueId>();
        for (BodyAction.Active action : body.actions()) {
            used.addAll(action.definition().tissueIds());
        }
        return Set.copyOf(used);
    }

    private static void requireMutable(BodySnapshot body) {
        // 与其他身体命令保持一致的生命及版本约束。
        MeridianErrors.required(body, MeridianErrors.Data.BODY_REQUIRED);
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectTransport(BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectTransport(REVISION_EXHAUSTED);
        }
    }

    private static BodySnapshot replace(BodySnapshot body, List<BodyAction.Active> actions) {
        // 一次启停只变更动作与版本，恢复在切换之前通过时间算子处理。
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), body.organStates(), body.meridianStates(), body.transits(),
                body.vitality(), body.settledAt(), body.revision() + 1, actions, body.openingTask(), body.addonProgress());
    }
}
