package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.*;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

import java.util.HashSet;
import java.util.List;

/** 可信活动的最小原子算子集合：资源计划不能携带身体快照或跳过容量、疲劳与守恒。 */
public final class BodyActivityExecution {
    private static final int MAX_ACCOUNT_CHANGES = 256;
    private static final int MAX_CIRCUIT_CHANGES = 16;
    private static final int MAX_EFFORTS = 32;
    private static final int MAX_COMPLETIONS = 128;
    private static final int MAX_RECIPES = 16;
    private static final double BALANCE_TOLERANCE = 1e-8;

    private BodyActivityExecution() {
        // 附属只产生计划，权威提交和回执复算均调用同一个执行器。
    }

    public static void validate(BodyActivity plan) {
        // 限制单笔工作量；具体账户和余额必须在事务内结合身体检查。
        if (plan.getAccountsCount() > MAX_ACCOUNT_CHANGES || plan.getCircuitsCount() > MAX_CIRCUIT_CHANGES
                || plan.getEffortsCount() > MAX_EFFORTS || plan.getCompletionsCount() > MAX_COMPLETIONS
                || plan.getRecipesCount() > MAX_RECIPES
                || plan.getStartActionsCount() > BodyActions.MAX_ACTIVE_ACTIONS
                || plan.getStopActionsCount() > BodyActions.MAX_ACTIVE_ACTIONS
                || plan.getAddModifiersCount() > CapabilityModifier.MAX_PER_BODY
                || plan.getRemoveModifiersCount() > CapabilityModifier.MAX_PER_BODY) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
        plan.getProgressMap().forEach(AddonProgress::validateEntry);
        if (plan.hasDamage() && plan.getDamage().getLethal()) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
        // 释放明细仅在此校验，数值或重复条目非法时抛 IllegalArgumentException。
        BodyProtobuf.fromInventory(plan.getReleasedQi());
    }

    public static BodySnapshot apply(BodySnapshot body, BodyActivity plan, String callerId) {
        // 先校验整笔的身份和资源差额，后续失败只丢弃候选，不留下半次转移。
        validate(plan);
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.REVISION_EXHAUSTED);
        }
        BodySnapshot resources = applyResources(body, plan, callerId);
        BodySnapshot worked = applyModules(resources, plan);
        BodySnapshot injured = applyInjuries(worked, plan);

        // 附属进度与最终身体一起发布，内部各算子的版本递增折合为一次提交。
        var result = BodyProtobuf.toMessage(injured).toBuilder();
        if (plan.hasRealm()) {
            if (plan.getRealmValue() != result.getRealmValue() + 1) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            result.setRealm(plan.getRealm());
        }
        result.putAllAddonProgress(plan.getProgressMap());
        result.setRevision(body.revision() + 1);
        return BodyProtobuf.fromMessage(result.build());
    }

    private static BodySnapshot applyResources(BodySnapshot body, BodyActivity plan, String callerId) {
        // 修正先完整校验，资源变更再核对余额与守恒，全部通过后才允许做功。
        var candidate = BodyProtobuf.toMessage(body).toBuilder();
        updateModifiers(body, candidate, plan, callerId);
        var accounts = new HashSet<String>();
        for (QiAccountChange change : plan.getAccountsList()) {
            if (!accounts.add(change.getAccountCase() + ":" + accountId(change))) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            replaceAccount(candidate, change);
        }
        var circuitIds = new HashSet<String>();
        for (CircuitChange change : plan.getCircuitsList()) {
            if (!circuitIds.add(change.getId())) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            replaceCircuit(body, candidate, change);
        }
        BodySnapshot updated = BodyProtobuf.fromMessage(candidate.build());
        double released = BodyProtobuf.fromInventory(plan.getReleasedQi()).totalQi();
        double previousTotalQi = body.totalQi();
        double balanceError = previousTotalQi - updated.totalQi() - released;
        if (Math.abs(balanceError) > BALANCE_TOLERANCE * Math.max(1, previousTotalQi)) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
        validateReservations(body, updated);
        return updated;
    }

    private static BodySnapshot applyModules(BodySnapshot body, BodyActivity plan) {
        // 动作占用、做功、器官加工沿用既有算子，避免活动建立第二套疲劳或加工账。
        BodySnapshot acted = applyActions(body, plan);
        BodySnapshot worked = applyEfforts(acted, plan.getEffortsList());
        BodySnapshot expanded = completeMeridians(worked, plan.getCompletionsList());
        return startRecipes(expanded, plan.getRecipesList());
    }

    private static BodySnapshot applyActions(BodySnapshot body, BodyActivity plan) {
        // 先停止再启动，活动内的占用替换仍由动作算子验证。
        BodySnapshot updated = body;
        for (String instance : plan.getStopActionsList()) {
            updated = BodyActions.stop(updated, instance);
        }
        for (ActivityAction action : plan.getStartActionsList()) {
            BodyAction.Definition definition = ActionProtobuf.fromDefinition(action.getDefinition());
            updated = BodyActions.start(updated, action.getInstanceId(), definition);
        }
        return updated;
    }

    private static BodySnapshot applyEfforts(BodySnapshot body, List<ActivityEffort> efforts) {
        // 各项费用顺序作用于同一候选，后续组织不能跳过此前已消耗的预算。
        BodySnapshot updated = body;
        for (ActivityEffort effort : efforts) {
            updated = exert(updated, effort);
        }
        return updated;
    }

    private static BodySnapshot completeMeridians(BodySnapshot body, List<CompleteMeridian> completions) {
        // 按实际构型逐项扩容，未知经脉或非法体积使整个候选失败。
        BodySnapshot updated = body;
        for (CompleteMeridian completion : completions) {
            var id = new Anatomy.MeridianId(completion.getMeridianId());
            Anatomy.MeridianDefinition definition = updated.structure().meridians().stream()
                    .filter(item -> item.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY));
            double addedVolume = completion.getAddedVolume();
            if (!Double.isFinite(addedVolume) || addedVolume <= 0) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            updated = OpeningRuntime.complete(updated, id, definition.lumenVolume() + addedVolume);
        }
        return updated;
    }

    private static BodySnapshot startRecipes(BodySnapshot body, List<ActivityRecipe> recipes) {
        // 加工启动使用已完成费用与构型变更的候选，配方仍执行原有守恒校验。
        BodySnapshot updated = body;
        for (ActivityRecipe processing : recipes) {
            updated = OrganProcessing.start(updated, new Anatomy.OrganId(processing.getOrganId()),
                    CapabilityProtobuf.fromRecipe(processing.getRecipe()));
        }
        return updated;
    }

    private static BodySnapshot applyInjuries(BodySnapshot body, BodyActivity plan) {
        // 器官损伤复用健康结算；经脉没有 HP，用独立伤势状态表示失能。
        if (plan.hasDamage() || plan.getMeridianInjuriesCount() > 0) {
            var damage = HealthMessages.DamageBody.newBuilder()
                    .setSourceId("meridian:activity")
                    .setDamageHp(1)
                    .setDamageType("meridian:overload")
                    .setKind(HealthMessages.DamageKind.CUSTOM)
                    .build();
            var resolution = plan.getDamage().toBuilder()
                    .setLethal(false /* lethal */)
                    .addAllMeridianInjuries(plan.getMeridianInjuriesList())
                    .build();
            var command = MeridianMessages.BodyCommand.newBuilder().setDamageBody(damage).build();
            var result = BodyHealthExecution.calculate(body, command, resolution);
            if (result.fact().getDied() || result.fact().getReleasedQi().getEntriesCount() != 0) {
                // 致死效果必须通过独立健康意图结清，活动负载只表达局部非致死损伤。
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            return result.body();
        }
        return body;
    }

    private static void updateModifiers(BodySnapshot before, BodyMessages.BodySnapshot.Builder candidate,
                                         BodyActivity plan, String callerId) {
        // 只有操作修正时要求命名空间调用身份，普通活动保留原有宿主身份规则。
        if (plan.getAddModifiersCount() == 0 && plan.getRemoveModifiersCount() == 0) {
            return;
        }
        var source = new DefinitionId(callerId);
        var removals = modifierRemovals(before, plan, source);
        var existingIds = new HashSet<>(before.modifiers().stream().map(CapabilityModifier::id).toList());

        // 同笔先撤销再添加；既有 ID 即使撤销也不能重复添加，避免隐式覆盖来源。
        candidate.clearModifiers();
        var retained = before.modifiers().stream()
                .filter(modifier -> !removals.contains(modifier.id()))
                .map(CapabilityProtobuf::toModifier)
                .toList();
        candidate.addAllModifiers(retained);
        for (BodyMessages.CapabilityModifier addition : plan.getAddModifiersList()) {
            CapabilityModifier modifier = CapabilityProtobuf.fromModifier(addition);
            if (!modifier.source().equals(source)) {
                throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.ACCESS_DENIED);
            }
            if (modifier.curve().startMillis() < before.settledAt().elapsedMillis()) {
                throw MeridianErrors.invalid(MeridianErrors.Data.MODIFIER_START_BEFORE_CLOCK);
            }
            if (!existingIds.add(modifier.id())) {
                throw MeridianErrors.invalid(MeridianErrors.Data.INVALID_MODIFIER_STATE);
            }
            candidate.addModifiers(addition);
        }
        // 完整快照构造在做功前检查数量、组织引用与相对期限，失败不会发布任何候选。
    }

    private static HashSet<DefinitionId> modifierRemovals(BodySnapshot before, BodyActivity plan, DefinitionId source) {
        // 未知、重复或跨来源撤销都整笔拒绝，调用方不能删除其他附属的效果。
        var removals = new HashSet<DefinitionId>();
        for (String id : plan.getRemoveModifiersList()) {
            var removal = new DefinitionId(id);
            CapabilityModifier existing = before.modifiers().stream()
                    .filter(modifier -> modifier.id().equals(removal))
                    .findFirst()
                    .orElseThrow(() -> MeridianErrors.invalid(MeridianErrors.Data.INVALID_MODIFIER_REMOVAL));
            if (!existing.source().equals(source)) {
                throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.ACCESS_DENIED);
            }
            if (!removals.add(removal)) {
                throw MeridianErrors.invalid(MeridianErrors.Data.INVALID_MODIFIER_REMOVAL);
            }
        }
        return removals;
    }

    private static String accountId(QiAccountChange change) {
        // 账户种类与 ID 一起定位，内腔和外壁从不混账。
        return switch (change.getAccountCase()) {
            case LUMEN_ID -> change.getLumenId();
            case WALL_ID -> change.getWallId();
            case ORGAN_ID -> change.getOrganId();
            case ACCOUNT_NOT_SET -> throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        };
    }

    private static void replaceAccount(BodyMessages.BodySnapshot.Builder body, QiAccountChange change) {
        // 必须给出精确旧值，防止计划生成后资源变化仍覆盖新余额。
        String id = accountId(change);
        if (change.hasOrganId()) {
            for (int index = 0; index < body.getOrganStatesCount(); index++) {
                var state = body.getOrganStates(index);
                if (state.getId().equals(id)) {
                    requireExpected(state.getBuffer(), change);
                    body.setOrganStates(index, state.toBuilder().setBuffer(change.getReplacement()));
                    return;
                }
            }
        } else {
            for (int index = 0; index < body.getMeridianStatesCount(); index++) {
                var state = body.getMeridianStates(index);
                if (state.getId().equals(id)) {
                    requireExpected(change.hasLumenId() ? state.getLumenQi() : state.getWallQi(), change);
                    var replacement = state.toBuilder();
                    if (change.hasLumenId()) {
                        replacement.setLumenQi(change.getReplacement());
                    } else {
                        replacement.setWallQi(change.getReplacement());
                    }
                    body.setMeridianStates(index, replacement);
                    return;
                }
            }
        }
        throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
    }

    private static void requireExpected(BodyMessages.QiInventory inventory, QiAccountChange change) {
        // 使用数值模型比较，忽略 protobuf 未知字段或序列化形式差异。
        if (!BodyProtobuf.fromInventory(inventory).equals(BodyProtobuf.fromInventory(change.getExpected()))) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
    }

    private static void replaceCircuit(BodySnapshot before, BodyMessages.BodySnapshot.Builder body, CircuitChange change) {
        // 普通在途不得借活动入口删改；闭环出生还须验证开通状态、实际线路与固定耗时。
        var previous = body.getTransitsList().stream().filter(item -> item.getId().equals(change.getId())).findFirst();
        if (previous.isPresent()) {
            if (!previous.get().hasCircuit()) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            body.removeTransits(body.getTransitsList().indexOf(previous.get()));
        }
        if (!change.hasReplacement()) {
            if (previous.isEmpty()) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            return;
        }
        QiTransit transit = BodyProtobuf.fromTransit(change.getReplacement());
        if (transit.circuit() == null || !transit.id().equals(change.getId())) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
        CircuitTransport.validate(before.structure(), transit);
        if (previous.isEmpty()) {
            double duration = CircuitTransport.durationMillis(before.structure(), transit.circuit().getConnectionIdsList(),
                    transit.circuit().getInternalDistanceMeters(), transit.cargo().totalQi(), transit.throughputPerSecond());
            if (Math.abs(duration - transit.circuit().getDurationMillis()) > 1e-6 || transit.circuit().getWorkMillis() != 0) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            for (Anatomy.MeridianId id : CircuitTransport.meridians(before.structure(), transit)) {
                MeridianState state = before.meridianStates().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
                if (state.condition() != MeridianState.Condition.INTACT || state.opening().stage() != MeridianOpening.Stage.OPENED) {
                    throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.MODULE_UNAVAILABLE);
                }
            }
        } else {
            // 新一圈允许按当前批量重新计算服务时长；同一圈中途不能改路线或缩短总时长。
            var old = previous.get().getCircuit();
            boolean sameRoute = old.getConnectionIdsList().equals(transit.circuit().getConnectionIdsList())
                    && old.getInternalDistanceMeters() == transit.circuit().getInternalDistanceMeters();
            boolean sameDuration = old.getDurationMillis() == transit.circuit().getDurationMillis();
            double recalculated = CircuitTransport.durationMillis(before.structure(), transit.circuit().getConnectionIdsList(),
                    transit.circuit().getInternalDistanceMeters(), transit.cargo().totalQi(), transit.throughputPerSecond());
            boolean nextLap = transit.circuit().getWorkMillis() == 0 && Math.abs(recalculated - transit.circuit().getDurationMillis()) < 1e-6;
            if (!sameRoute || (!sameDuration && !nextLap)) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
        }
        body.addTransits(change.getReplacement());
    }

    private static void validateReservations(BodySnapshot before, BodySnapshot body) {
        // 返回容量和沿途吞吐按唯一在途明细派生，普通批次与闭环不能同时占满同一模块。
        for (var definition : body.structure().meridians()) {
            double incomingVolume = 0;
            double rate = 0;
            for (QiTransit transit : body.transits()) {
                var connection = CircuitTransport.connection(body.structure(), transit.connectionId().value());
                boolean incoming = definition.ports().stream().anyMatch(port -> port.id().equals(connection.to()));
                boolean outgoing = definition.ports().stream().anyMatch(port -> port.id().equals(connection.from()));
                if (incoming) {
                    incomingVolume += transit.cargo().volume();
                }
                boolean uses = incoming || outgoing;
                if (transit.circuit() != null) {
                    uses = CircuitTransport.meridians(body.structure(), transit).contains(definition.id());
                }
                if (uses && transit.status() == QiTransit.Status.MOVING) {
                    rate += transit.throughputPerSecond();
                }
            }
            var state = body.meridianStates().stream().filter(item -> item.id().equals(definition.id())).findFirst().orElseThrow();
            var oldState = before.meridianStates().stream().filter(item -> item.id().equals(definition.id())).findFirst().orElseThrow();
            var incomingRoutes = before.structure().connections().stream().filter(connection -> definition.ports().stream()
                            .anyMatch(port -> port.id().equals(connection.to()))).map(Anatomy.Connection::id).toList();
            double oldReserved = before.transits().stream().filter(item -> incomingRoutes.contains(item.connectionId()))
                    .mapToDouble(item -> item.cargo().volume()).sum();
            // 已有受阻运输可以等待腾空，活动不得增加它的超额预约；正常库存仍严格不超过实际体积。
            double reservationLimit = Math.max(definition.lumenVolume(), oldState.lumenQi().volume() + oldReserved);
            if (incomingVolume + state.lumenQi().volume() > reservationLimit + 1e-7
                    || rate > definition.throughputPerSecond() + 1e-9) {
                throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.CAPACITY_EXCEEDED);
            }
        }
    }

    private static BodySnapshot exert(BodySnapshot body, ActivityEffort effort) {
        // 组织复用既有做功；器官按健康和疲劳同时限制功率及能量。
        if (!Double.isFinite(effort.getWatts()) || !Double.isFinite(effort.getSeconds())
                || effort.getWatts() <= 0 || effort.getSeconds() <= 0) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
        if (effort.hasTissueId()) {
            return TissueCapabilities.exert(body, new TissueCapabilities.Exertion(new Anatomy.TissueId(effort.getTissueId()),
                    0, effort.getWatts(), effort.getSeconds()));
        }
        var id = new Anatomy.OrganId(effort.getOrganId());
        var definition = body.structure().organs().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        var state = body.organStates().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        var part = body.structure().parts().stream().filter(item -> item.id().equals(definition.partId())).findFirst().orElseThrow();
        var partState = body.partStates().stream().filter(item -> item.id().equals(part.id())).findFirst().orElseThrow();
        double health = Math.min(state.hp() / definition.maxHp(), partState.hp() / part.maxHp());
        var endurance = definition.endurance();
        if (endurance == null || health <= 0) {
            throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.MODULE_UNAVAILABLE);
        }
        double availableWork = (endurance.enduranceJoules() - state.fatigueJoules()) * health;
        double availablePower = endurance.maximumWatts() * availableWork / endurance.enduranceJoules();
        if (effort.getWatts() > availablePower || effort.getWatts() * effort.getSeconds() > availableWork) {
            throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.MODULE_UNAVAILABLE);
        }
        var candidate = BodyProtobuf.toMessage(body).toBuilder();
        int index = body.organStates().indexOf(state);
        candidate.setOrganStates(index, candidate.getOrganStates(index).toBuilder()
                .setFatigueJoules(state.fatigueJoules() + effort.getWatts() * effort.getSeconds() / health));
        return BodyProtobuf.fromMessage(candidate.build());
    }
}
