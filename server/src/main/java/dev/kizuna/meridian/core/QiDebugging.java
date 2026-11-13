package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** 管理员真元注入的纯计算；宿主必须另行授权，不能作为普通摄入或突破证明。 */
public final class QiDebugging {
    private static final double DEBUG_FREE_DENSITY = 0.01;
    private static final int CAPACITY_SAFETY_ULPS = 8;
    private static final double SHARE_SUM_TOLERANCE = 1e-9;

    private QiDebugging() {
        // 不持有 Store，只有命令事务可以提交候选身体。
    }

    public static void validate(DebugQi request) {
        // wire 和执行入口共用明确的小型契约，拒绝隐含的全身超容量覆盖。
        if (request.getMode() == DebugQiMode.SET_INVENTORY) {
            validateSet(request);
        } else {
            validateFillOrInject(request);
        }

        // 经脉 ID 与属性比例的语法对三种模式一致；属性是否已登记由宿主检查。
        if (!request.getMeridianId().isEmpty()) {
            new Anatomy.MeridianId(request.getMeridianId());
        }
        validateShares(request);
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
                .filter(definition -> request.getMeridianId().isEmpty()
                        || definition.id().value().equals(request.getMeridianId()))
                .toList();
        if (targets.isEmpty()) {
            throw MeridianErrors.rejectIntake(FailureCode.MODULE_NOT_FOUND);
        }

        // 覆盖与补元各自改账；只创建一次版本变更，扩散由同一身体的事件调度接手，不瞬移平均分账。
        var accounts = new QiAccounts(body);
        if (request.getMode() == DebugQiMode.SET_INVENTORY) {
            setInventories(body, targets, accounts, request);
        } else {
            fillLumens(body, targets, accounts, request);
        }
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), body.structure(), body.realm(),
                body.partStates(), body.tissueStates(), body.organStates(), accounts.meridians(), body.transits(),
                body.vitality(), body.settledAt(), body.revision() + 1, body.actions(), body.openingTask(),
                body.addonProgress(), body.modifiers());
    }

    private static void validateSet(DebugQi request) {
        // 覆盖必须给出非负剂量和不低于游离态的密度。
        if (!request.hasAmount() || !Double.isFinite(request.getAmount()) || request.getAmount() < 0
                || !Double.isFinite(request.getDensity()) || request.getDensity() < DEBUG_FREE_DENSITY) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_DEBUG_QI);
        }
    }

    private static void validateFillOrInject(DebugQi request) {
        // 注入必须指定经脉和正剂量；全身补满不能带剂量，局部补到目标的剂量必须为正。
        boolean inject = request.getMode() == DebugQiMode.INJECT_FOR_DIFFUSION;
        boolean fill = request.getMode() == DebugQiMode.FILL_LUMENS;
        if ((!inject && !fill) || (inject && (request.getMeridianId().isBlank() || !request.hasAmount()))
                || (request.hasAmount() && (!Double.isFinite(request.getAmount()) || request.getAmount() <= 0))
                || (request.getMeridianId().isEmpty() && request.hasAmount())) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_DEBUG_QI);
        }

        // 扩散注入固定为无属性游离态；补元的密度 0 表示游离态，否则不能低于游离态。
        double density = request.getDensity();
        boolean densityValid = density == 0 || (Double.isFinite(density) && density >= DEBUG_FREE_DENSITY);
        if (!densityValid || (inject && (density != 0 || request.getSharesCount() > 0))) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_DEBUG_QI);
        }
    }

    private static void validateShares(DebugQi request) {
        // 每份比例落在 (0, 1]、属性不重复；合计超过 1 视为配错，不按比例缩放。
        var attributes = new HashSet<Qi.QiAttribute>();
        double total = 0;
        for (DebugQiShare share : request.getSharesList()) {
            double fraction = share.getFraction();
            if (!Double.isFinite(fraction) || fraction <= 0 || fraction > 1
                    || !attributes.add(Qi.QiAttribute.of(share.getAttribute()))) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_DEBUG_QI_SHARES);
            }
            total += fraction;
        }
        if (total > 1 + SHARE_SUM_TOLERANCE) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_DEBUG_QI_SHARES);
        }
    }

    private static void setInventories(BodySnapshot body, List<Anatomy.MeridianDefinition> targets,
            QiAccounts accounts, DebugQi request) {
        // all 按扣除在途预约后的可用体积分配；小经脉的预约不能挤占其他经脉的空位。
        var availableVolumes = new LinkedHashMap<Anatomy.MeridianId, Double>();
        for (var definition : targets) {
            double volume = request.getWall() ? definition.wallVolume() : definition.lumenVolume();
            double reserved = request.getWall() ? 0 : reservedVolume(body, definition);
            availableVolumes.put(definition.id(), Math.max(0, volume - reserved));
        }
        double totalVolume = availableVolumes.values().stream().mapToDouble(Double::doubleValue).sum();
        if (request.getAmount() / request.getDensity() > totalVolume + 1e-8) {
            throw MeridianErrors.rejectIntake(FailureCode.CAPACITY_EXCEEDED);
        }

        // 总量检查留有 1e-8 体积余量，按经脉截到自身可用体积，避免余量落进单条内腔越过上限；
        // 拆成多份属性明细时再扣去舍入余量。
        for (var definition : targets) {
            double availableVolume = availableVolumes.get(definition.id());
            double share = totalVolume == 0 ? 0 : request.getAmount() * availableVolume / totalVolume;
            double safeVolume = Math.max(0, availableVolume - splitSafetyVolume(availableVolume, 0, request));
            double amount = Math.min(share, safeVolume * request.getDensity());
            QiInventory inventory = blended(amount, request.getDensity(), request);
            MeridianState state = accounts.meridian(definition.id());
            accounts.replaceMeridian(new MeridianState(state.id(), request.getWall() ? state.lumenQi() : inventory,
                    request.getWall() ? inventory : state.wallQi(), state.fatigue(), state.condition(),
                    state.opening()));
        }
    }

    private static void fillLumens(BodySnapshot body, List<Anatomy.MeridianDefinition> targets,
            QiAccounts accounts, DebugQi request) {
        // 补元只补不减；任何一条经脉不完好或超容量都会拒绝整笔请求。
        double density = request.getDensity() == 0 ? DEBUG_FREE_DENSITY : request.getDensity();
        for (Anatomy.MeridianDefinition definition : targets) {
            MeridianState state = accounts.meridian(definition.id());
            if (state.condition() != MeridianState.Condition.INTACT) {
                throw MeridianErrors.rejectIntake(FailureCode.MODULE_UNAVAILABLE);
            }

            // 直接由剩余体积按本次密度换算可注入元数；先加回存量再相减会让高密度存量的舍入差
            // 按低密度放大，越过内腔上限。
            double capacity = definition.lumenVolume();
            double reserved = reservedVolume(body, definition);
            double availableVolume = Math.max(0, capacity - state.lumenQi().volume() - reserved);
            double safetyVolume = splitSafetyVolume(capacity, CAPACITY_SAFETY_ULPS, request);
            double maximumAdded = Math.max(0, availableVolume - safetyVolume) * density;
            double added = addedQi(request, state.lumenQi().totalQi(), maximumAdded);
            if (added > maximumAdded) {
                throw MeridianErrors.rejectIntake(FailureCode.CAPACITY_EXCEEDED);
            }

            // 注入不改变既有明细的相态、开拓和外壁状态；同种新元与存量合并。
            QiInventory cargo = blended(added, density, request);
            if (!cargo.entries().isEmpty()) {
                QiInventory credited = QiAccounting.credit(state.lumenQi(), cargo);
                accounts.replaceMeridian(new MeridianState(state.id(), credited, state.wallQi(),
                        state.fatigue(), state.condition(), state.opening()));
            }
        }
    }

    private static double reservedVolume(BodySnapshot body, Anatomy.MeridianDefinition definition) {
        // 流入该经脉端口的在途真元已预约内腔体积，补元和覆盖都不能占用。
        var ports = definition.ports().stream().map(Anatomy.Port::id).toList();
        var routes = body.structure().connections().stream().filter(route -> ports.contains(route.to()))
                .map(Anatomy.Connection::id).toList();
        return body.transits().stream().filter(transit -> routes.contains(transit.connectionId()))
                .mapToDouble(transit -> transit.cargo().volume()).sum();
    }

    private static double splitSafetyVolume(double capacity, int baseUlps, DebugQi request) {
        // 每多拆一份属性明细，体积求和就多一次舍入；按份数加大余量，满容量时仍不越过上限。
        return (baseUlps + CAPACITY_SAFETY_ULPS * request.getSharesCount()) * Math.ulp(capacity);
    }

    private static QiInventory blended(double amount, double density, DebugQi request) {
        // 按比例拆成各属性明细，余数记无属性；每份都从未分配部分里取，拆后合计不超过已截好的剂量。
        var amounts = new LinkedHashMap<Qi.QiAttribute, Double>();
        double assigned = 0;
        for (DebugQiShare share : request.getSharesList()) {
            double part = Math.min(amount * share.getFraction(), amount - assigned);
            amounts.merge(Qi.QiAttribute.of(share.getAttribute()), part, Double::sum);
            assigned += part;
        }
        amounts.merge(Qi.QiAttribute.NONE, Math.max(0, amount - assigned), Double::sum);

        // 不足最小转移量的份额会被合并精度吞掉，直接舍去；全部舍去即不改账。
        List<Qi> entries = amounts.entrySet().stream()
                .filter(entry -> entry.getValue() >= QiAccounting.MIN_TRANSFER_QI)
                .map(entry -> new Qi(entry.getKey(), entry.getValue(), density))
                .toList();
        return entries.isEmpty() ? QiInventory.empty() : new QiInventory(entries);
    }

    private static double addedQi(DebugQi request, double current, double maximumAdded) {
        // 注入按请求量追加；补满取全部空位，补到目标只补差额，差额不足最小转移量视为已满。
        if (request.getMode() == DebugQiMode.INJECT_FOR_DIFFUSION) {
            return request.getAmount();
        }
        double shortfall = maximumAdded;
        if (request.hasAmount()) {
            shortfall = request.getAmount() - current;
        }
        if (shortfall < QiAccounting.MIN_TRANSFER_QI) {
            return 0;
        }
        return shortfall;
    }
}
