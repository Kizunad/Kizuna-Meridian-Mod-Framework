package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import java.util.List;

/** 管理员真元注入的纯计算；宿主必须另行授权，不能作为普通摄入或突破证明。 */
public final class QiDebugging {
    private QiDebugging() {
        // 不持有 Store，只有命令事务可以提交候选身体。
    }

    public static void validate(DebugQi request) {
        // wire 和执行入口共用明确的小型契约，拒绝隐含的全身超容量覆盖。
        if (request.getMode() == DebugQiMode.SET_INVENTORY) {
            if (!request.hasAmount() || !Double.isFinite(request.getAmount()) || request.getAmount() < 0
                    || !Double.isFinite(request.getDensity()) || request.getDensity() < .01) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_DEBUG_QI);
            }
            if (!request.getMeridianId().isEmpty()) {
                new Anatomy.MeridianId(request.getMeridianId());
            }
            return;
        }
        boolean inject = request.getMode() == DebugQiMode.INJECT_FOR_DIFFUSION;
        boolean fill = request.getMode() == DebugQiMode.FILL_LUMENS;
        if ((!inject && !fill) || (inject && (request.getMeridianId().isBlank() || !request.hasAmount()))
                || (request.hasAmount() && (!Double.isFinite(request.getAmount()) || request.getAmount() <= 0))
                || (request.getMeridianId().isEmpty() && request.hasAmount())) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_DEBUG_QI);
        }
        if (!request.getMeridianId().isEmpty()) {
            new Anatomy.MeridianId(request.getMeridianId());
        }
    }

    public static BodySnapshot apply(BodySnapshot body, DebugQi request) {
        // 先校验身体及所有目标，任何一条拒绝都不会留下部分填充。
        validate(request);
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectIntake(FailureCode.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectIntake(FailureCode.REVISION_EXHAUSTED);
        }
        List<Anatomy.MeridianDefinition> targets = body.structure().meridians().stream()
                .filter(definition -> request.getMeridianId().isEmpty() || definition.id().value().equals(request.getMeridianId())).toList();
        if (targets.isEmpty()) {
            throw MeridianErrors.rejectIntake(FailureCode.MODULE_NOT_FOUND);
        }
        var accounts = new QiAccounts(body);
        if (request.getMode() == DebugQiMode.SET_INVENTORY) {
            // all 按扣除在途预约后的可用体积分配；小经脉的预约不能挤占其他经脉的空位。
            var availableVolumes = new java.util.LinkedHashMap<Anatomy.MeridianId, Double>();
            for (var definition : targets) {
                double volume = request.getWall() ? definition.wallVolume() : definition.lumenVolume();
                double reserved = 0;
                if (!request.getWall()) {
                    var ports = definition.ports().stream().map(Anatomy.Port::id).toList();
                    var routes = body.structure().connections().stream().filter(route -> ports.contains(route.to()))
                            .map(Anatomy.Connection::id).toList();
                    reserved = body.transits().stream().filter(transit -> routes.contains(transit.connectionId()))
                            .mapToDouble(transit -> transit.cargo().volume()).sum();
                }
                availableVolumes.put(definition.id(), Math.max(0, volume - reserved));
            }
            double totalVolume = availableVolumes.values().stream().mapToDouble(Double::doubleValue).sum();
            if (request.getAmount() / request.getDensity() > totalVolume + 1e-8) {
                throw MeridianErrors.rejectIntake(FailureCode.CAPACITY_EXCEEDED);
            }
            for (var definition : targets) {
                double amount = totalVolume == 0 ? 0 : request.getAmount() * availableVolumes.get(definition.id()) / totalVolume;
                QiInventory inventory = amount == 0 ? QiInventory.empty()
                        : new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, amount, request.getDensity())));
                MeridianState state = accounts.meridian(definition.id());
                accounts.replaceMeridian(new MeridianState(state.id(), request.getWall() ? state.lumenQi() : inventory,
                        request.getWall() ? inventory : state.wallQi(), state.fatigue(), state.condition(), state.opening()));
            }
            return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                    body.partStates(), body.tissueStates(), body.organStates(), accounts.meridians(), body.transits(),
                    body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask(), body.addonProgress());
        }
        for (Anatomy.MeridianDefinition definition : targets) {
            MeridianState state = accounts.meridian(definition.id());
            if (state.condition() != MeridianState.Condition.INTACT) {
                throw MeridianErrors.rejectIntake(FailureCode.MODULE_UNAVAILABLE);
            }
            double capacity = definition.lumenVolume();
            var ports = definition.ports().stream().map(Anatomy.Port::id).toList();
            var incomingRoutes = body.structure().connections().stream().filter(route -> ports.contains(route.to()))
                    .map(Anatomy.Connection::id).toList();
            double reserved = body.transits().stream().filter(transit -> incomingRoutes.contains(transit.connectionId()))
                    .mapToDouble(transit -> transit.cargo().volume()).sum();
            // 调试新元始终为游离态基准，只用剩余体积换算本次可注入元数。
            double availableVolume = Math.max(0, capacity - state.lumenQi().volume() - reserved);
            double current = state.lumenQi().totalQi();
            double maximumStored = current + availableVolume * .01;
            double desired = maximumStored;
            if (request.hasAmount()) {
                desired = request.getAmount();
                if (request.getMode() == DebugQiMode.INJECT_FOR_DIFFUSION) {
                    desired += current;
                }
            }
            if (desired > maximumStored) {
                throw MeridianErrors.rejectIntake(FailureCode.CAPACITY_EXCEEDED);
            }
            // fill 只补足而不扣除既有真元；注入不改变相态、开拓和外壁状态。
            double added = Math.max(0, desired - current);
            if (added > 0) {
                QiInventory credited = QiAccounting.credit(state.lumenQi(),
                        new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, added, .01))));
                accounts.replaceMeridian(new MeridianState(state.id(), credited, state.wallQi(),
                        state.fatigue(), state.condition(), state.opening()));
            }
        }
        // 只创建一次版本变更；扩散由同一身体的事件调度接手，不瞬移平均分账。
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), body.organStates(), accounts.meridians(), body.transits(),
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask(), body.addonProgress());
    }
}
