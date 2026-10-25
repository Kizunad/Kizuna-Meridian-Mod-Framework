package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.OpeningPolicyMessages.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 开脉的开始、连续适应、完成与收尾；所有结果仍由身体事务统一提交。 */
public final class OpeningRuntime {
    private OpeningRuntime() {
        // 不读取现实时间，也不保存任何全局玩家状态。
    }

    public static BodySnapshot start(BodySnapshot body, MeridianOpeningRule rule, BodyAction.Definition action) {
        // 1. 读取目标与当前状态，拒绝并行开脉和重复领取同一脉的容量成长。
        requireAlive(body);
        if (body.openingTask() != null && body.openingTask().active()) {
            throw MeridianErrors.rejectIntake(FailureCode.OPENING_BUSY);
        }
        var definitions = DataChecks.index(body.structure().meridians(), Anatomy.MeridianDefinition::id, "经脉定义");
        var states = DataChecks.index(body.meridianStates(), MeridianState::id, "经脉状态");
        Anatomy.MeridianDefinition target = definitions.get(rule.targetMeridianId());
        MeridianState targetState = states.get(rule.targetMeridianId());
        if (target == null) {
            throw MeridianErrors.rejectIntake(FailureCode.MODULE_NOT_FOUND);
        }
        if (targetState.opening().stage() == MeridianOpening.Stage.OPENED) {
            throw MeridianErrors.rejectIntake(FailureCode.MERIDIAN_ALREADY_OPENED);
        }
        requireAction(rule, action, body.structure());

        // 2. 附属判断启动条件并编排本轮参数；框架仅验证返回计划的引用与资源边界。
        OpeningPolicyDecision decision = OpeningPolicies.evaluate(body, rule, OpeningPolicyPhase.PREPARE_OPENING, 0);
        var participants = new ArrayList<MeridianOpeningRule.GuidanceTarget>();
        for (var limit : decision.getParticipantsList()) {
            Anatomy.MeridianId id = new Anatomy.MeridianId(limit.getMeridianId());
            if (!states.containsKey(id) || states.get(id).condition() != MeridianState.Condition.INTACT) {
                throw MeridianErrors.rejectIntake(FailureCode.ROUTE_UNAVAILABLE);
            }
            DataChecks.atMost(limit.getVolume(), definitions.get(id).lumenVolume(), "引导目标体积");
            participants.add(new MeridianOpeningRule.GuidanceTarget(id, limit.getVolume()));
        }
        long requiredMillis = decision.getRequiredMillis();
        DataChecks.nonNegative(rule.holdQiPerSecond() * (requiredMillis / 1000.0), "开拓维持总消耗");
        double stableCapacity = target.lumenVolume() + rule.lumenVolumeGain();
        DataChecks.positive(stableCapacity, "开拓后稳定容量");
        if (decision.getTargetStableVolume() != stableCapacity || requiredMillis <= 0) {
            throw MeridianErrors.invalid(MeridianErrors.Opening.POLICY_INVALID);
        }
        var observedOrgans = decision.getObservedOrganIdsList().stream().map(Anatomy.OrganId::new).toList();
        for (Anatomy.OrganId id : observedOrgans) {
            if (!action.organIds().contains(id) || body.structure().organs().stream()
                    .noneMatch(organ -> organ.id().equals(id) && organ.intake() != null)) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.ACTION_RESOURCES);
            }
        }

        // 3. 原子占用动作并固定计划；附属不能在条件回调里直接修改身体或库存。
        String actionInstance = "opening-" + (body.revision() + 1);
        BodySnapshot occupied = BodyActions.start(body, actionInstance, action);
        var task = new OpeningTask(rule, actionInstance, participants, requiredMillis, stableCapacity,
                body.settledAt().elapsedMillis(), 0, OpeningTask.Status.PREPARING, List.of(),
                observedOrgans, decision.getReceiptWindowMillis());
        OpeningTask previous = body.openingTask();
        if (previous != null && previous.rule().equals(rule) && !rule.continuousHold()
                && previous.requiredMillis() == requiredMillis && previous.targetStableVolume() == stableCapacity
                && previous.participants().equals(participants)) {
            // 非连续规则可恢复相同任务的适应时间；条件、目标或参与组变化后重新开始。
            task = task.progress(previous.heldMillis(), OpeningTask.Status.PREPARING, List.of());
        }
        BodySnapshot started = withTask(occupied, task);
        if (OpeningEfforts.availableSeconds(started) < .001) {
            throw MeridianErrors.rejectIntake(FailureCode.OPENING_REQUIREMENTS_UNMET);
        }
        return refresh(started);
    }

    public static BodyTimeAdvancement.Result pause(BodySnapshot body, Anatomy.MeridianId id) {
        // 暂停通过同一身体事务解除动作占用，保存本次任务的终止事实。
        requireAlive(body);
        OpeningTask task = body.openingTask();
        if (task == null || !task.active() || !task.rule().targetMeridianId().equals(id)) {
            throw MeridianErrors.rejectIntake(FailureCode.OPENING_NOT_ACTIVE);
        }
        BodyTimeAdvancement.Result stopped = finish(body, OpeningTask.Status.PAUSED);
        return new BodyTimeAdvancement.Result(revision(stopped.body(), body.revision() + 1), false, stopped.releasedQi());
    }

    public static BodyTimeAdvancement.Result interruptUnavailable(BodySnapshot body) {
        // 伤害使任务所占模块失能时立即解除占用；本次版本已由外层健康事务递增。
        if (body.openingTask() != null && body.openingTask().active() && !modulesAvailable(body)) {
            return finish(body, OpeningTask.Status.DAMAGED);
        }
        return new BodyTimeAdvancement.Result(body, false, QiInventory.empty());
    }

    static BodyTimeAdvancement.Result advance(BodySnapshot original, BodyTimeAdvancement.Plan plan,
                                              Set<Anatomy.TissueId> intervalUsedTissues) {
        // 可信时间校验在普通算子统一完成；反向时间和异世代不能进入事件循环。
        TimeCursor start = original.settledAt();
        if (!start.domain().equals(plan.target().domain()) || !start.epoch().equals(plan.target().epoch())
                || plan.target().elapsedMillis() < start.elapsedMillis() || original.revision() == Long.MAX_VALUE) {
            return BodyTimeAdvancement.advanceInterval(original, plan, intervalUsedTissues);
        }
        BodySnapshot current = original;
        QiInventory released = QiInventory.empty();
        while (true) {
            OpeningTask task = current.openingTask();
            if (task == null || !task.active() || current.vitality().dead()) {
                // 任务中途结束后，剩余区间恢复正常扩散与空闲恢复。
                if (!current.vitality().dead() && current.settledAt().elapsedMillis() < plan.target().elapsedMillis()) {
                    BodyTimeAdvancement.Result remainder = BodyTimeAdvancement.advanceInterval(current, plan, intervalUsedTissues);
                    current = revision(remainder.body(), original.revision());
                    released = QiAccounting.credit(released, remainder.releasedQi());
                }
                break;
            }
            // 按依赖、模块完好、预算的顺序确定终止原因，再统一解除动作占用。
            OpeningTask.Status stopStatus = null;
            if (!OpeningPolicies.available(task.rule())) {
                stopStatus = OpeningTask.Status.PAUSED;
            } else if (!modulesAvailable(current)) {
                stopStatus = OpeningTask.Status.DAMAGED;
            } else if (OpeningEfforts.availableSeconds(current) < .001) {
                stopStatus = OpeningTask.Status.EXHAUSTED;
            }
            if (stopStatus != null) {
                BodyTimeAdvancement.Result stopped = finish(current, stopStatus);
                current = stopped.body();
                released = QiAccounting.credit(released, stopped.releasedQi());
                continue;
            }

            // 起点先处理发运/阻塞重试，随后才能知道这一段真实的首个到达时刻。
            BodyTimeAdvancement.Result atStart = settleInterval(current,
                    new BodyTimeAdvancement.Plan(current.settledAt(), plan.biologicalSecondsPerSimulationSecond(), plan.mode()),
                    intervalUsedTissues);
            current = revision(atStart.body(), original.revision());
            released = QiAccounting.credit(released, atStart.releasedQi());
            if (current.vitality().dead()) {
                break;
            }
            current = refresh(current);
            long nowMillis = current.settledAt().elapsedMillis();
            if (nowMillis == plan.target().elapsedMillis()) {
                break;
            }

            // 到达、加工、摄入证据过期、耐力耗尽和维持完成都是有意义的计算边界。
            long boundaryMillis = plan.target().elapsedMillis();
            boundaryMillis = earlier(boundaryMillis, QiTransport.nextArrivalBoundary(current));
            boundaryMillis = earlier(boundaryMillis, OrganProcessing.nextCompletionBoundary(current));
            boundaryMillis = Math.min(boundaryMillis, nextTaskMillis(current));
            boundaryMillis = earlier(boundaryMillis,
                    BodyTimeAdvancement.nextLifespanBoundary(current, plan.biologicalSecondsPerSimulationSecond()));
            long durationMillis = boundaryMillis - nowMillis;
            OpeningTask intervalTask = current.openingTask();
            boolean holdingInterrupted = !OpeningPolicies.evaluate(current, intervalTask.rule(),
                    OpeningPolicyPhase.CHECK_OPENING, durationMillis).getReady();

            // 本段先扣活动负载，再推进运输与时间；持续适应只能累加整个区间都满足条件的时长。
            BodySnapshot charged = OpeningEfforts.charge(current, durationMillis);
            var targetTime = new TimeCursor(start.domain(), start.epoch(), boundaryMillis);
            BodyTimeAdvancement.Result interval = BodyTimeAdvancement.advanceInterval(charged,
                    new BodyTimeAdvancement.Plan(targetTime, plan.biologicalSecondsPerSimulationSecond(), plan.mode()), intervalUsedTissues);
            current = revision(interval.body(), original.revision());
            released = QiAccounting.credit(released, interval.releasedQi());
            if (current.vitality().dead()) {
                break;
            }
            long heldMillis = intervalTask.heldMillis();
            if (intervalTask.status() == OpeningTask.Status.HOLDING && !holdingInterrupted) {
                heldMillis += durationMillis;
            }
            if (holdingInterrupted && intervalTask.rule().continuousHold()) {
                // 跌至门槛优先于同毫秒到货或完成；补充只能开启新的连续维持区间。
                heldMillis = 0;
            }
            if (heldMillis >= intervalTask.requiredMillis()) {
                // 成功标记、结构扩容和动作解除构成一次状态转换。
                BodyTimeAdvancement.Result completed = finish(current, OpeningTask.Status.COMPLETED);
                current = completed.body();
                released = QiAccounting.credit(released, completed.releasedQi());
            } else {
                current = withTask(current, intervalTask.progress(heldMillis, intervalTask.status(), intervalTask.intakeSamples()));
                current = refresh(current);
            }
        }
        if (current.equals(original)) {
            throw MeridianErrors.rejectTime(MeridianErrors.TimeRejectionReason.TIME_NOT_ADVANCED);
        }
        return new BodyTimeAdvancement.Result(revision(current, original.revision() + 1), current.vitality().dead(), released);
    }

    private static BodyTimeAdvancement.Result settleInterval(BodySnapshot body, BodyTimeAdvancement.Plan plan,
                                                            Set<Anatomy.TissueId> used) {
        // 同毫秒没有资源变化属于正常静止，其他拒绝仍交给事务回滚。
        try {
            return BodyTimeAdvancement.advanceInterval(body, plan, used);
        } catch (MeridianErrors.TimeRejected rejected) {
            if (rejected.reason() != MeridianErrors.TimeRejectionReason.TIME_NOT_ADVANCED) {
                throw rejected;
            }
            return new BodyTimeAdvancement.Result(body, false, QiInventory.empty());
        }
    }

    static BodyTimeAdvancement.Result freeze(BodySnapshot body, BodyTimeAdvancement.Plan plan, Set<Anatomy.TissueId> used) {
        // 冻结结束本次连续尝试，防止用冻结期间的时间补足适应。
        BodyTimeAdvancement.Result stopped = finish(body, OpeningTask.Status.PAUSED);
        BodyTimeAdvancement.Result advanced = settleInterval(stopped.body(), plan, used);
        return new BodyTimeAdvancement.Result(revision(advanced.body(), body.revision() + 1), advanced.died(),
                QiAccounting.credit(stopped.releasedQi(), advanced.releasedQi()));
    }

    public static Optional<TimeCursor> nextBoundary(BodySnapshot body) {
        // 静止任务也只预约供给过期或预算耗尽；没有逐 tick 适应进度循环。
        OpeningTask task = body.openingTask();
        if (task == null || !task.active()) {
            return Optional.empty();
        }
        if (!OpeningPolicies.available(task.rule())) {
            return Optional.of(body.settledAt());
        }
        long at = nextTaskMillis(body);
        if (!modulesAvailable(body) || OpeningEfforts.availableSeconds(body) < .001
                || !refresh(body).equals(body) || OpeningGuidance.canDispatch(body)) {
            at = body.settledAt().elapsedMillis();
        }
        if (at == Long.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(new TimeCursor(body.settledAt().domain(), body.settledAt().epoch(), at));
    }

    private static long nextTaskMillis(BodySnapshot body) {
        // 使用差值比较避免大模拟时钟溢出；不足一毫秒的可用预算由耗尽分支处理。
        OpeningTask task = body.openingTask();
        long now = body.settledAt().elapsedMillis();
        long remainingClock = Long.MAX_VALUE - now;
        long delay = remainingClock;
        if (task.status() == OpeningTask.Status.HOLDING) {
            delay = Math.min(delay, task.requiredMillis() - task.heldMillis());
        }
        double effortMillis = Math.floor(OpeningEfforts.availableSeconds(body) * 1000);
        if (effortMillis < delay) {
            delay = Math.max(1, (long) effortMillis);
        }
        OpeningPolicyDecision decision = OpeningPolicies.evaluate(body, task.rule(), OpeningPolicyPhase.CHECK_OPENING, 0);
        if (decision.hasNextCheckDelayMillis()) {
            delay = Math.min(delay, decision.getNextCheckDelayMillis());
        }
        return now + delay;
    }

    public static BodySnapshot recordIntake(BodySnapshot body, Anatomy.OrganId organId, double qi) {
        // 持续摄入与接触摄入均在事务候选中记录真实毛额，由原子提交复算保护证据。
        OpeningTask task = body.openingTask();
        if (task == null || !task.active() || !task.observedOrganIds().contains(organId)) {
            return body;
        }
        var samples = new ArrayList<>(task.intakeSamples());
        samples.add(new OpeningTask.IntakeSample(organId, body.settledAt().elapsedMillis(), qi));
        BodySnapshot supplied = withTask(body, task.progress(task.heldMillis(), task.status(), samples));
        if (!OpeningPolicies.available(task.rule())) {
            return supplied;
        }
        return refresh(supplied);
    }

    private static BodySnapshot refresh(BodySnapshot body) {
        // 先清除过期证据；条件中断只重置连续时间，实际真元仍留在账户中。
        OpeningTask task = body.openingTask();
        long now = body.settledAt().elapsedMillis();
        List<OpeningTask.IntakeSample> samples = task.intakeSamples().stream()
                .filter(sample -> now - sample.atMillis() < task.receiptWindowMillis()).toList();
        boolean ready = OpeningPolicies.evaluate(body, task.rule(), OpeningPolicyPhase.CHECK_OPENING, 0).getReady();
        long held = task.heldMillis();
        if (!ready && task.rule().continuousHold()) {
            held = 0;
        }
        OpeningTask.Status status = ready ? OpeningTask.Status.HOLDING : OpeningTask.Status.PREPARING;
        return withTask(body, task.progress(held, status, samples));
    }

    private static BodyTimeAdvancement.Result finish(BodySnapshot body, OpeningTask.Status status) {
        // 成功只拓宽目标一次；停止和失败不改变体积，也不排出原本合法的库存。
        OpeningTask task = body.openingTask();
        if (status == OpeningTask.Status.COMPLETED) {
            BodySnapshot completed = complete(body, task.rule().targetMeridianId(), task.targetStableVolume());
            return new BodyTimeAdvancement.Result(completed, false, QiInventory.empty());
        }
        var states = new ArrayList<MeridianState>();
        long held = task.heldMillis();
        if (task.rule().continuousHold()) {
            held = 0;
        }
        for (MeridianState state : body.meridianStates()) {
            QiInventory lumen = state.lumenQi();
            MeridianOpening opening = state.opening();
            if (state.id().equals(task.rule().targetMeridianId())) {
                double progress = (double) held / task.requiredMillis();
                opening = new MeridianOpening(MeridianOpening.Stage.IN_PROGRESS, progress);
            }
            states.add(new MeridianState(state.id(), lumen, state.wallQi(), state.fatigue(), state.condition(), opening));
        }
        List<BodyAction.Active> actions = body.actions().stream()
                .filter(action -> !action.instanceId().equals(task.actionInstanceId())).toList();
        BodySnapshot finished = copy(body, body.structure(), body.tissueStates(), body.organStates(), states, body.transits(), actions,
                task.progress(held, status, List.of()), body.revision());
        return new BodyTimeAdvancement.Result(finished, false, QiInventory.empty());
    }

    public static BodySnapshot complete(BodySnapshot body, Anatomy.MeridianId id, double stableVolume) {
        // 正常完成和管理员完成共用此路径；已经开通的经脉不能再次获得体积。
        List<MeridianState> originalStates = body.meridianStates();
        MeridianState target = originalStates.stream().filter(state -> state.id().equals(id))
                .findFirst().orElseThrow(() -> MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY));
        if (target.opening().stage() == MeridianOpening.Stage.OPENED) {
            return body;
        }
        DataChecks.positive(stableVolume, "完成后内腔体积");

        // 扩容与开拓状态一起切换，保留实际构型中的标签及独立库存。
        BodyStructure originalStructure = body.structure();
        List<Anatomy.MeridianDefinition> definitions = expandedDefinitions(originalStructure, id, stableVolume);
        List<MeridianState> states = originalStates.stream().map(state -> {
            if (!state.id().equals(id)) {
                return state;
            }
            return new MeridianState(id, state.lumenQi(), state.wallQi(), state.fatigue(), state.condition(),
                    new MeridianOpening(MeridianOpening.Stage.OPENED, 1));
        }).toList();
        OpeningTask task = body.openingTask();
        List<BodyAction.Active> actions = body.actions();
        List<BodyAction.Active> remainingActions;
        OpeningTask completedTask;
        if (task != null && task.rule().targetMeridianId().equals(id)) {
            String instance = task.actionInstanceId();
            remainingActions = actions.stream().filter(action -> !action.instanceId().equals(instance)).toList();
            completedTask = task.progress(task.requiredMillis(), OpeningTask.Status.COMPLETED, List.of());
        } else {
            remainingActions = actions;
            completedTask = task;
        }
        var structure = new BodyStructure(originalStructure.parts(), originalStructure.tissues(),
                originalStructure.organs(), definitions, originalStructure.connections());
        return copy(body, structure, body.tissueStates(), body.organStates(), states, body.transits(),
                remainingActions, completedTask, body.revision());
    }

    private static List<Anatomy.MeridianDefinition> expandedDefinitions(BodyStructure structure,
                                                                      Anatomy.MeridianId id, double stableVolume) {
        // 扩容只改变目标内腔体积，标签与其他结构事实必须完整保留。
        var definitions = new ArrayList<Anatomy.MeridianDefinition>();
        for (Anatomy.MeridianDefinition definition : structure.meridians()) {
            if (!definition.id().equals(id)) {
                definitions.add(definition);
                continue;
            }
            if (stableVolume < definition.lumenVolume()) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY);
            }
            var expanded = new Anatomy.MeridianDefinition(id, definition.displayName(), definition.partIds(),
                    stableVolume, definition.wallVolume(), definition.throughputPerSecond(), definition.resilience(),
                    definition.fatigueCapacity(), definition.ports(), definition.tags());
            definitions.add(expanded);
        }
        return List.copyOf(definitions);
    }

    private static boolean modulesAvailable(BodySnapshot body) {
        // 参与脉及动作占用的器官/组织受损失能时立即解除开脉占用。
        OpeningTask task = body.openingTask();
        var states = DataChecks.index(body.meridianStates(), MeridianState::id, "经脉");
        if (task.participants().stream().anyMatch(limit -> states.get(limit.meridianId()).condition() != MeridianState.Condition.INTACT)) {
            return false;
        }
        BodyAction.Active action = body.actions().stream()
                .filter(item -> item.instanceId().equals(task.actionInstanceId()))
                .findFirst().orElseThrow();
        var parts = DataChecks.index(body.partStates(), AnatomyState.Part::id, "部位");
        for (Anatomy.OrganId id : action.definition().organIds()) {
            Anatomy.OrganDefinition definition = body.structure().organs().stream()
                    .filter(organ -> organ.id().equals(id)).findFirst().orElseThrow();
            AnatomyState.Organ state = body.organStates().stream()
                    .filter(organ -> organ.id().equals(id)).findFirst().orElseThrow();
            if (state.hp() == 0 || parts.get(definition.partId()).hp() == 0) {
                return false;
            }
        }
        for (Anatomy.TissueId id : action.definition().tissueIds()) {
            Anatomy.TissueDefinition definition = body.structure().tissues().stream()
                    .filter(tissue -> tissue.id().equals(id)).findFirst().orElseThrow();
            AnatomyState.Tissue state = body.tissueStates().stream()
                    .filter(tissue -> tissue.id().equals(id)).findFirst().orElseThrow();
            if (state.hp() == 0 || parts.get(definition.partId()).hp() == 0) {
                return false;
            }
        }
        return true;
    }

    public static void requireAction(MeridianOpeningRule rule, BodyAction.Definition action, BodyStructure structure) {
        // 动作的精确修订和占用必须涵盖全部成本，避免消耗时仍享受休息恢复。
        if (!rule.actionId().equals(action.id()) || rule.actionVersion() != action.version()) {
            throw MeridianErrors.invalid(MeridianErrors.Opening.ACTION_RESOURCES);
        }
        for (var effort : rule.efforts()) {
            boolean included = action.tissueIds().contains(effort.moduleId()) || action.organIds().contains(effort.moduleId());
            if (!included) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.ACTION_RESOURCES);
            }
            if (structure != null && effort.moduleId() instanceof Anatomy.OrganId id) {
                var organ = structure.organs().stream().filter(item -> item.id().equals(id)).findFirst()
                        .orElseThrow(() -> MeridianErrors.rejectIntake(FailureCode.MODULE_NOT_FOUND));
                if (organ.endurance() == null) {
                    throw MeridianErrors.rejectIntake(FailureCode.OPENING_REQUIREMENTS_UNMET);
                }
            }
        }
    }

    static void validateTask(OpeningTask task, BodyStructure structure, List<MeridianState> meridians,
                             List<BodyAction.Active> actions, TimeCursor time, VitalityState vitality) {
        // 这里只检查存档的引用、容量和动作一致性；人体门槛与序号由附属策略判断。
        var definitions = DataChecks.index(structure.meridians(), Anatomy.MeridianDefinition::id, "经脉定义");
        var states = DataChecks.index(meridians, MeridianState::id, "经脉状态");
        var target = definitions.get(task.rule().targetMeridianId());
        if (target == null || task.startedMillis() > time.elapsedMillis() || (vitality.dead() && task.active())) {
            throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
        }
        for (var limit : task.participants()) {
            if (!definitions.containsKey(limit.meridianId())) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
            }
            DataChecks.atMost(limit.volume(), definitions.get(limit.meridianId()).lumenVolume(), "引导目标体积");
        }
        Optional<BodyAction.Active> action = actions.stream().filter(item -> item.instanceId().equals(task.actionInstanceId())).findFirst();
        if (task.active()) {
            if (action.isEmpty() || task.targetStableVolume() != target.lumenVolume() + task.rule().lumenVolumeGain()
                    || states.get(target.id()).opening().stage() != MeridianOpening.Stage.IN_PROGRESS) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
            }
            if (action.get().startedMillis() != task.startedMillis()
                    || states.get(target.id()).opening().progress() != (double) task.heldMillis() / task.requiredMillis()) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
            }
            requireAction(task.rule(), action.get().definition(), structure);
            if (!action.get().definition().organIds().containsAll(task.observedOrganIds())) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.ACTION_RESOURCES);
            }
        } else if (action.isPresent()) {
            throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
        }
        for (var sample : task.intakeSamples()) {
            if (sample.atMillis() < task.startedMillis() || sample.atMillis() > time.elapsedMillis()
                    || !task.observedOrganIds().contains(sample.organId())) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
            }
        }
    }

    static BodySnapshot withTask(BodySnapshot body, OpeningTask task) {
        // 任务进度与目标脉展示进度同次更新，其他经脉开拓事实保持不变。
        var states = new ArrayList<MeridianState>();
        for (MeridianState state : body.meridianStates()) {
            if (task.active() && state.id().equals(task.rule().targetMeridianId())) {
                double progress = (double) task.heldMillis() / task.requiredMillis();
                var opening = new MeridianOpening(MeridianOpening.Stage.IN_PROGRESS, progress);
                state = new MeridianState(state.id(), state.lumenQi(), state.wallQi(), state.fatigue(),
                        state.condition(), opening);
            }
            states.add(state);
        }
        return copy(body, body.structure(), body.tissueStates(), body.organStates(), states, body.transits(),
                body.actions(), task, body.revision());
    }

    static BodySnapshot revision(BodySnapshot body, long revision) {
        // 事件内候选共用起始版本，外层事务只增加一次 revision。
        return copy(body, body.structure(), body.tissueStates(), body.organStates(), body.meridianStates(), body.transits(),
                body.actions(), body.openingTask(), revision);
    }

    static BodySnapshot copy(BodySnapshot body, BodyStructure structure, List<AnatomyState.Tissue> tissues,
                             List<AnatomyState.Organ> organs, List<MeridianState> meridians, List<QiTransit> transits,
                             List<BodyAction.Active> actions, OpeningTask task, long revision) {
        // 只组装一次计算的不可变候选，时间和寿命继续使用统一时间算子的结果。
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), structure, body.realm(), body.partStates(),
                tissues, organs, meridians, transits, body.vitality(), body.settledAt(), revision, actions, task, body.addonProgress());
    }

    private static long earlier(long current, Optional<TimeCursor> boundary) {
        // 空边界代表没有这一类待结算事件。
        return boundary.map(time -> Math.min(current, time.elapsedMillis())).orElse(current);
    }

    private static void requireAlive(BodySnapshot body) {
        // 开始和暂停沿用其他变更命令的生命与版本约束。
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw MeridianErrors.rejectIntake(FailureCode.BODY_DEAD);
        }
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectIntake(FailureCode.REVISION_EXHAUSTED);
        }
    }
}
