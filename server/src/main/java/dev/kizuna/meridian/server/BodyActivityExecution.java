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
    private BodyActivityExecution() {
        // 附属只产生计划，权威提交和回执复算均调用同一个执行器。
    }

    public static void validate(BodyActivity plan) {
        // 限制单笔工作量；具体账户和余额必须在事务内结合身体检查。
        if (plan.getAccountsCount() > 256 || plan.getCircuitsCount() > 16 || plan.getEffortsCount() > 32
                || plan.getCompletionsCount() > 128 || plan.getRecipesCount() > 16
                || plan.getStartActionsCount() > 32 || plan.getStopActionsCount() > 32) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
        plan.getProgressMap().forEach(AddonProgress::validateEntry);
        if (plan.hasDamage() && plan.getDamage().getLethal()) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
        BodyProtobuf.fromInventory(plan.getReleasedQi());
    }

    public static BodySnapshot apply(BodySnapshot body, BodyActivity plan) {
        // 先校验整笔的身份和资源差额，后续失败只丢弃候选，不留下半次转移。
        validate(plan);
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectIntake(MeridianMessages.FailureCode.REVISION_EXHAUSTED);
        }
        var candidate = BodyProtobuf.toMessage(body).toBuilder();
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
        double balanceError = body.totalQi() - updated.totalQi() - released;
        if (Math.abs(balanceError) > 1e-8 * Math.max(1, body.totalQi())) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
        }
        validateReservations(body, updated);

        // 动作占用、做功、器官加工沿用既有算子，避免活动建立第二套疲劳或加工账。
        for (String instance : plan.getStopActionsList()) {
            updated = BodyActions.stop(updated, instance);
        }
        for (ActivityAction action : plan.getStartActionsList()) {
            updated = BodyActions.start(updated, action.getInstanceId(), ActionProtobuf.fromDefinition(action.getDefinition()));
        }
        for (ActivityEffort effort : plan.getEffortsList()) {
            updated = exert(updated, effort);
        }
        for (CompleteMeridian completion : plan.getCompletionsList()) {
            var id = new Anatomy.MeridianId(completion.getMeridianId());
            var definition = updated.structure().meridians().stream().filter(item -> item.id().equals(id))
                    .findFirst().orElseThrow(() -> MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY));
            if (!Double.isFinite(completion.getAddedVolume()) || completion.getAddedVolume() <= 0) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            updated = OpeningRuntime.complete(updated, id, definition.lumenVolume() + completion.getAddedVolume());
        }
        for (ActivityRecipe processing : plan.getRecipesList()) {
            updated = OrganProcessing.start(updated, new Anatomy.OrganId(processing.getOrganId()),
                    CapabilityProtobuf.fromRecipe(processing.getRecipe()));
        }

        // 器官损伤复用健康结算；经脉没有 HP，用独立伤势状态表示失能。
        if (plan.hasDamage() || plan.getMeridianInjuriesCount() > 0) {
            var damage = HealthMessages.DamageBody.newBuilder().setSourceId("meridian:activity")
                    .setDamageHp(1).setDamageType("meridian:overload").setKind(HealthMessages.DamageKind.CUSTOM).build();
            var resolution = plan.getDamage().toBuilder().setLethal(false).addAllMeridianInjuries(plan.getMeridianInjuriesList()).build();
            var result = BodyHealthExecution.calculate(updated,
                    MeridianMessages.BodyCommand.newBuilder().setDamageBody(damage).build(), resolution);
            if (result.fact().getDied() || result.fact().getReleasedQi().getEntriesCount() != 0) {
                // 致死效果必须通过独立健康意图结清，活动负载只表达局部非致死损伤。
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            updated = result.body();
        }
        candidate = BodyProtobuf.toMessage(updated).toBuilder();
        if (plan.hasRealm()) {
            if (plan.getRealmValue() != candidate.getRealmValue() + 1) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            candidate.setRealm(plan.getRealm());
        }
        candidate.putAllAddonProgress(plan.getProgressMap());
        candidate.setRevision(body.revision() + 1);
        return BodyProtobuf.fromMessage(candidate.build());
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
