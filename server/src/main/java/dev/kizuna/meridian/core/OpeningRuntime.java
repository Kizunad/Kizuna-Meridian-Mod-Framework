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
    private static final double MINIMUM_EFFORT_SECONDS = 0.001;
    private static final double MILLIS_PER_SECOND = 1_000;
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
        BodySnapshot paused = revision(stopped.body(), body.revision() + 1);
        return new BodyTimeAdvancement.Result(paused, stopped.died(), stopped.releasedQi());
    }

    public static BodyTimeAdvancement.Result interruptUnavailable(BodySnapshot body) {
        // 伤害使任务所占模块失能时立即解除占用；本次版本已由外层健康事务递增。
        if (body.openingTask() != null && body.openingTask().active() && !modulesAvailable(body)) {
            return finish(body, OpeningTask.Status.DAMAGED);
        }
        return new BodyTimeAdvancement.Result(body, false, QiInventory.empty());
    }

    static void advance(BodyAdvancement current, BodyTimeAdvancement.Plan plan) {
        // 时间身份已在事务入口校验；所有事件段共用候选、能力索引和策略消息投影。
        while (true) {
            OpeningTask task = current.openingTask();
            if (task == null || !task.active() || current.vitality().dead()) {
                // 任务中途结束后，剩余区间恢复正常扩散与空闲恢复。
                if (!current.vitality().dead() && current.settledAt().elapsedMillis() < plan.target().elapsedMillis()) {
                    BodyTimeAdvancement.advanceInterval(current, plan);
                }
                break;
            }
            // 按依赖、模块完好、预算的顺序确定终止原因，再统一解除动作占用。
            OpeningTask.Status stopStatus = stopStatus(current);
            if (stopStatus != null) {
                finish(current, stopStatus);
                continue;
            }

            // 起点先处理发运/阻塞重试，随后才能知道这一段真实的首个到达时刻。
            settleInterval(current, new BodyTimeAdvancement.Plan(current.settledAt(),
                    plan.biologicalSecondsPerSimulationSecond(), plan.mode()));
            if (current.vitality().dead()) {
                break;
            }
            refresh(current);
            long nowMillis = current.settledAt().elapsedMillis();
            if (nowMillis == plan.target().elapsedMillis()) {
                break;
            }

            long boundaryMillis = nextIntervalMillis(current, plan);
            advanceOpeningInterval(current, plan, boundaryMillis);
        }
        if (current.unchanged()) {
            throw MeridianErrors.rejectTime(MeridianErrors.TimeRejectionReason.TIME_NOT_ADVANCED);
        }
    }

    private static OpeningTask.Status stopStatus(BodyAdvancement body) {
        // 依赖、失能和预算不足按固定优先级决定终止原因。
        if (!OpeningPolicies.available(body.task.rule())) {
            return OpeningTask.Status.PAUSED;
        }
        if (!modulesAvailable(body)) {
            return OpeningTask.Status.DAMAGED;
        }
        if (OpeningEfforts.availableSeconds(body) < MINIMUM_EFFORT_SECONDS) {
            return OpeningTask.Status.EXHAUSTED;
        }
        return null;
    }

    private static long nextIntervalMillis(BodyAdvancement body, BodyTimeAdvancement.Plan plan) {
        // 到达、加工、收据过期、预算耗尽、适应完成及寿尽均可提前结束当前段。
        long arrivalMillis = earlier(plan.target().elapsedMillis(), QiTransport.nextArrivalBoundary(body));
        long resourceMillis = earlier(arrivalMillis, OrganProcessing.nextCompletionBoundary(body));
        long taskMillis = StrictMath.min(resourceMillis, nextTaskMillis(body));
        return earlier(taskMillis,
                BodyTimeAdvancement.nextLifespanBoundary(body, plan.biologicalSecondsPerSimulationSecond()));
    }

    private static void advanceOpeningInterval(BodyAdvancement body, BodyTimeAdvancement.Plan plan,
                                               long boundaryMillis) {
        // 使用旧余额检查全段条件，先扣活动负载，再推进资源和时间，死亡优先于完成。
        TimeCursor start = body.time;
        long durationMillis = boundaryMillis - start.elapsedMillis();
        OpeningTask intervalTask = body.task;
        boolean holdingInterrupted = !OpeningPolicies.evaluate(body, intervalTask.rule(),
                OpeningPolicyPhase.CHECK_OPENING, durationMillis).getReady();
        OpeningEfforts.charge(body, durationMillis);
        var targetTime = new TimeCursor(start.domain(), start.epoch(), boundaryMillis);
        BodyTimeAdvancement.advanceInterval(body,
                new BodyTimeAdvancement.Plan(targetTime, plan.biologicalSecondsPerSimulationSecond(), plan.mode()));
        if (body.vitality.dead()) {
            return;
        }

        long heldMillis = intervalTask.heldMillis();
        if (intervalTask.status() == OpeningTask.Status.HOLDING && !holdingInterrupted) {
            heldMillis += durationMillis;
        }
        if (holdingInterrupted && intervalTask.rule().continuousHold()) {
            // 跌至门槛优先于同毫秒到货，补充只能开启新的连续维持区间。
            heldMillis = 0;
        }
        if (heldMillis >= intervalTask.requiredMillis()) {
            finish(body, OpeningTask.Status.COMPLETED);
        } else {
            OpeningTask progressed = intervalTask.progress(heldMillis, intervalTask.status(),
                    intervalTask.intakeSamples());
            withTask(body, progressed);
            refresh(body);
        }
    }

    private static void settleInterval(BodyAdvancement body, BodyTimeAdvancement.Plan plan) {
        // 同毫秒没有资源变化属于正常静止，其他拒绝仍交给事务回滚。
        try {
            BodyTimeAdvancement.advanceInterval(body, plan);
        } catch (MeridianErrors.TimeRejected rejected) {
            if (rejected.reason() != MeridianErrors.TimeRejectionReason.TIME_NOT_ADVANCED) {
                throw rejected;
            }
        }
    }

    static void freeze(BodyAdvancement body, BodyTimeAdvancement.Plan plan) {
        // 冻结结束本次连续尝试，防止用冻结期间的时间补足适应。
        finish(body, OpeningTask.Status.PAUSED);
        settleInterval(body, plan);
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
        // 单次外部调度与内部推进使用同一预算计算路径。
        return nextTaskMillis(new BodyAdvancement(body, Set.of()));
    }

    private static long nextTaskMillis(BodyAdvancement body) {
        // 使用差值比较避免大模拟时钟溢出；不足一毫秒的可用预算由耗尽分支处理。
        OpeningTask task = body.openingTask();
        long now = body.settledAt().elapsedMillis();
        long remainingClock = Long.MAX_VALUE - now;
        long delay = remainingClock;
        if (task.status() == OpeningTask.Status.HOLDING) {
            delay = StrictMath.min(delay, task.requiredMillis() - task.heldMillis());
        }
        double effortMillis = StrictMath.floor(OpeningEfforts.availableSeconds(body) * MILLIS_PER_SECOND);
        if (effortMillis < delay) {
            delay = StrictMath.max(1, (long) effortMillis);
        }
        OpeningPolicyDecision decision = OpeningPolicies.evaluate(body, task.rule(),
                OpeningPolicyPhase.CHECK_OPENING, 0);
        if (decision.hasNextCheckDelayMillis()) {
            delay = StrictMath.min(delay, decision.getNextCheckDelayMillis());
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
        // 普通命令与连续推进复用任务状态转换，策略输入仍是完整不可变消息。
        OpeningTask task = body.openingTask();
        OpeningPolicyDecision decision = OpeningPolicies.evaluate(body, task.rule(),
                OpeningPolicyPhase.CHECK_OPENING, 0);
        return withTask(body, refreshedTask(body, decision));
    }

    private static void refresh(BodyAdvancement body) {
        // 同一时刻检查复用曲线端点和消息子树，不跨状态缓存策略裁定。
        OpeningTask task = body.task;
        OpeningPolicyDecision decision = OpeningPolicies.evaluate(body, task.rule(),
                OpeningPolicyPhase.CHECK_OPENING, 0);
        withTask(body, refreshedTask(body, decision));
    }

    private static OpeningTask refreshedTask(BodyView body, OpeningPolicyDecision decision) {
        // 先清除过期证据；条件中断只重置连续时间，实际真元仍留在账户中。
        OpeningTask task = body.openingTask();
        long now = body.settledAt().elapsedMillis();
        List<OpeningTask.IntakeSample> samples = task.intakeSamples().stream()
                .filter(sample -> now - sample.atMillis() < task.receiptWindowMillis()).toList();
        boolean ready = decision.getReady();
        long held = task.heldMillis();
        if (!ready && task.rule().continuousHold()) {
            held = 0;
        }
        OpeningTask.Status status = ready ? OpeningTask.Status.HOLDING : OpeningTask.Status.PREPARING;
        return task.progress(held, status, samples);
    }

    private static BodyTimeAdvancement.Result finish(BodySnapshot body, OpeningTask.Status status) {
        // 命令边界输出完整候选，内部推进只应用同一个状态转换结果。
        OpeningChange change = finishedChange(body, status);
        BodySnapshot finished = copy(body, change.structure(), body.tissueStates(), body.organStates(),
                change.meridians(), body.transits(), change.actions(), change.task(), body.revision());
        return new BodyTimeAdvancement.Result(finished, false, QiInventory.empty());
    }

    private static void finish(BodyAdvancement body, OpeningTask.Status status) {
        // 解除占用和完成扩容先保存在事务候选内，最终统一校验。
        OpeningChange change = finishedChange(body, status);
        body.structure = change.structure();
        body.meridians = change.meridians();
        body.actions = change.actions();
        body.task = change.task();
    }

    private record OpeningChange(BodyStructure structure, List<MeridianState> meridians,
                                 List<BodyAction.Active> actions, OpeningTask task) { }

    private static OpeningChange finishedChange(BodyView body, OpeningTask.Status status) {
        // 成功只拓宽目标一次；停止和失败不改变体积，也不排出原本合法的库存。
        OpeningTask task = body.openingTask();
        if (status == OpeningTask.Status.COMPLETED) {
            return completedChange(body, task.rule().targetMeridianId(), task.targetStableVolume());
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
            var stoppedState = new MeridianState(state.id(), lumen, state.wallQi(), state.fatigue(),
                    state.condition(), opening);
            states.add(stoppedState);
        }
        List<BodyAction.Active> actions = body.actions().stream()
                .filter(action -> !action.instanceId().equals(task.actionInstanceId())).toList();
        OpeningTask stoppedTask = task.progress(held, status, List.of());
        return new OpeningChange(body.structure(), List.copyOf(states), actions, stoppedTask);
    }

    public static BodySnapshot complete(BodySnapshot body, Anatomy.MeridianId id, double stableVolume) {
        // 管理员命令和自然完成共用扩容规则；返回边界仍构造经过校验的快照。
        OpeningChange change = completedChange(body, id, stableVolume);
        return copy(body, change.structure(), body.tissueStates(), body.organStates(), change.meridians(),
                body.transits(), change.actions(), change.task(), body.revision());
    }

    private static OpeningChange completedChange(BodyView body, Anatomy.MeridianId id, double stableVolume) {
        // 正常完成和管理员完成共用此路径；已经开通的经脉不能再次获得体积。
        List<MeridianState> originalStates = body.meridianStates();
        MeridianState target = originalStates.stream().filter(state -> state.id().equals(id))
                .findFirst().orElseThrow(() -> MeridianErrors.invalid(MeridianErrors.INVALID_ACTIVITY));
        if (target.opening().stage() == MeridianOpening.Stage.OPENED) {
            return new OpeningChange(body.structure(), originalStates, body.actions(), body.openingTask());
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
        return new OpeningChange(structure, states, remainingActions, completedTask);
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

    private static boolean modulesAvailable(BodyView body) {
        // 参与脉及动作占用的器官/组织受损失能时立即解除开脉占用。
        OpeningTask task = body.openingTask();
        var states = DataChecks.index(body.meridianStates(), MeridianState::id, "经脉");
        boolean participantUnavailable = task.participants().stream()
                .anyMatch(limit -> states.get(limit.meridianId()).condition() != MeridianState.Condition.INTACT);
        if (participantUnavailable) {
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
            boolean included = action.tissueIds().contains(effort.moduleId())
                    || action.organIds().contains(effort.moduleId());
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
        Optional<BodyAction.Active> action = actions.stream()
                .filter(item -> item.instanceId().equals(task.actionInstanceId()))
                .findFirst();
        if (task.active()) {
            if (action.isEmpty() || task.targetStableVolume() != target.lumenVolume() + task.rule().lumenVolumeGain()
                    || states.get(target.id()).opening().stage() != MeridianOpening.Stage.IN_PROGRESS) {
                throw MeridianErrors.invalid(MeridianErrors.Opening.TASK_STATE);
            }
            double expectedProgress = (double) task.heldMillis() / task.requiredMillis();
            double actualProgress = states.get(target.id()).opening().progress();
            if (action.get().startedMillis() != task.startedMillis() || actualProgress != expectedProgress) {
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
        // 命令候选输出保持构造即校验。
        List<MeridianState> states = taskMeridians(body, task);
        return copy(body, body.structure(), body.tissueStates(), body.organStates(), states, body.transits(),
                body.actions(), task, body.revision());
    }

    private static void withTask(BodyAdvancement body, OpeningTask task) {
        // 只替换任务和目标脉展示进度，避免在曲线每个点重建完整快照。
        if (body.task == task) {
            return;
        }
        body.meridians = taskMeridians(body, task);
        body.task = task;
    }

    private static List<MeridianState> taskMeridians(BodyView body, OpeningTask task) {
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
        return List.copyOf(states);
    }

    static BodySnapshot revision(BodySnapshot body, long revision) {
        // 事件内候选共用起始版本，外层事务只增加一次 revision。
        return copy(body, body.structure(), body.tissueStates(), body.organStates(), body.meridianStates(),
                body.transits(), body.actions(), body.openingTask(), revision);
    }

    static BodySnapshot copy(BodySnapshot body, BodyStructure structure, List<AnatomyState.Tissue> tissues,
                             List<AnatomyState.Organ> organs, List<MeridianState> meridians, List<QiTransit> transits,
                             List<BodyAction.Active> actions, OpeningTask task, long revision) {
        // 只组装一次计算的不可变候选，时间和寿命继续使用统一时间算子的结果。
        return new BodySnapshot(body.schemaVersion(), body.id(), body.template(), structure, body.realm(),
                body.partStates(), tissues, organs, meridians, transits, body.vitality(), body.settledAt(),
                revision, actions, task, body.addonProgress(), body.modifiers());
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
