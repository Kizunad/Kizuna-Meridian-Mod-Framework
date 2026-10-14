package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.GatheringProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiSource;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingProfile;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static dev.kizuna.meridian.error.MeridianErrors.rejectGathering;
import static dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.*;

/** 宿主线程上的单轮编排。只持有步骤与回执关联，真元始终属于框架权威账户。 */
public final class GatheringSession<C> implements AutoCloseable {
    private final C context;
    private final BodyId bodyId;
    private final BodyCommandHandler<C> commands;
    private final BodySnapshotReader bodies;
    private final BodyReadPolicy<C> access;
    private final BodyTimeSource<C> time;
    private final Function<BodySnapshot, Optional<EnvironmentQiSource>> environment;
    private final OrganRecipeCatalog recipes;
    private GatheringProgress progress = GatheringProgress.newBuilder().setStage(GatheringStage.IDLE).build();
    private GatheringRequest accepted;
    private GatheringChoice choice;
    private String operationId;
    private String sourceId;
    private String transitId;
    private long expectedRevision;
    private int commandSequence;
    private boolean closed;
    private BreathingDriver<C> breathing;

    public GatheringSession(C context, BodyId bodyId, BodyCommandHandler<C> commands, BodySnapshotReader bodies,
                            BodyReadPolicy<C> access, BodyTimeSource<C> time,
                            Function<BodySnapshot, Optional<EnvironmentQiSource>> environment,
                            OrganRecipeCatalog allowedRecipes) {
        // 身份、来源选择与可见配方由可信宿主装配；一具身体同一时刻只绑定一个编排会话。
        this.context = context;
        this.bodyId = bodyId;
        this.commands = commands;
        this.bodies = bodies;
        this.access = access;
        this.time = time;
        this.environment = environment;
        this.recipes = allowedRecipes;
    }

    public byte[] handle(byte[] payload) {
        // 外部请求先有界解码；读取和每次继续执行都重新确认访问权。
        GatheringRequest request = GatheringProtobuf.decodeRequest(payload);
        var response = GatheringResponse.newBuilder().setProtocolVersion(1).setRequestId(request.getRequestId());
        try {
            if (!request.getBodyId().equals(bodyId.value())) {
                throw rejectGathering(ACCESS_DENIED);
            }
            BodySnapshot body = read();
            if (request.hasBreath()) {
                if (breathing == null) {
                    throw rejectGathering(INTAKE_UNSUPPORTED);
                }
                advance();
                breathing.control(request.getBreath());
            } else if (request.hasStart()) {
                if (breathing != null) {
                    throw rejectGathering(INVALID_REQUEST);
                }
                start(request, body);
            }
            return response.setView(view()).build().toByteArray();
        } catch (MeridianErrors.GatheringRejected rejection) {
            return response.setFailure(RequestFailure.newBuilder().setCode(rejection.code())).build().toByteArray();
        }
    }

    public GatheringView view() {
        // 查询保持只读；打开窗口或刷新不会累积摄入时间或推进寿命。
        BodySnapshot actual = read();
        BodyMessages.BodySnapshot body = BodyProtobuf.toMessage(actual);
        GatheringEnvironment preview = GatheringChoices.environment(environment.apply(actual));
        var result = GatheringView.newBuilder().setBody(body).setEnvironment(preview).setProgress(progress)
                .addAllChoices(GatheringChoices.list(body, recipes, preview.getSupplyPerSecond()));
        if (breathing != null) {
            result.setBreathing(breathing.view(actual));
        }
        return result.build();
    }

    public void enableBreathing(BreathingProfile profile) {
        // 宿主安装生物内容后装配一次，不能借重建界面重置呼吸控制或疲劳。
        if (breathing != null || progress.getNextBoundaryMillis() != 0) {
            throw rejectGathering(GATHERING_BUSY);
        }
        breathing = new BreathingDriver<>(this, profile, read(), recipes, commands.actionCatalog());
    }

    public void synchronizeBodyRevision() {
        // 宿主串行协调行走、恢复等其他合法动作后接纳最新版本；下一步骤仍重新校验器官与资源。
        // 这不是客户端重试入口，也不跳过 execute 内对本次计算期间版本冲突的检查。
        expectedRevision = read().revision();
    }

    private void start(GatheringRequest request, BodySnapshot body) {
        // 同一个成功请求重放只读当前进展；旧请求的旧 revision 不能再次启动新一轮。
        if (accepted != null && accepted.getRequestId().equals(request.getRequestId())) {
            if (!accepted.equals(request)) {
                throw rejectGathering(REQUEST_ID_CONFLICT);
            }
            return;
        }
        if (progress.getNextBoundaryMillis() != 0) {
            throw rejectGathering(GATHERING_BUSY);
        }
        if (request.getStart().getExpectedRevision() != body.revision()) {
            throw rejectGathering(REVISION_CONFLICT);
        }
        GatheringView preview = view();
        StartGathering intent = request.getStart();
        GatheringChoice selected = preview.getChoicesList().stream()
                .filter(candidate -> candidate.getOrganId().equals(intent.getOrganId())
                        && candidate.getConnectionId().equals(intent.getConnectionId())
                        && candidate.getRecipe().getId().equals(intent.getRecipeId())
                        && candidate.getRecipe().getVersion() == intent.getRecipeVersion())
                .findFirst().orElseThrow(() -> rejectGathering(RECIPE_INCOMPATIBLE));
        preflight(preview, selected);

        // 先结清开始前的闲置时间，避免按一次按钮就领取整段离线摄入量。
        choice = selected;
        expectedRevision = body.revision();
        operationId = UUID.randomUUID().toString();
        commandSequence = 0;
        if (time.resolve(context, body).getMode() != TimeAdvanceMode.ACTIVE) {
            throw rejectGathering(TRANSPORT_FROZEN);
        }
        try {
            execute(BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance()));
        } catch (MeridianErrors.GatheringRejected rejection) {
            // 同毫秒且没有待结算事件时，核心不创建空回执；这仍是有效的开始边界。
            if (rejection.code() != TIME_NOT_ADVANCED) {
                throw rejection;
            }
        }
        GatheringView settled = view();
        selected = settled.getChoicesList().stream().filter(candidate -> candidate.getOrganId().equals(intent.getOrganId())
                && candidate.getConnectionId().equals(intent.getConnectionId())
                && candidate.getRecipe().equals(choice.getRecipe())).findFirst().orElseThrow(() -> rejectGathering(ROUTE_NOT_FOUND));
        preflight(settled, selected);
        choice = selected;
        BodySnapshot current = read();
        EnvironmentQiSource source = environment.apply(current).orElseThrow(() -> rejectGathering(ENVIRONMENT_UNAVAILABLE));
        sourceId = source.getSpace().getSourceId();
        long waitMillis = intakeDuration(selected);
        long startMillis = current.settledAt().elapsedMillis();
        if (waitMillis > Long.MAX_VALUE - startMillis) {
            throw rejectGathering(NUMERIC_LIMIT);
        }
        long boundary = startMillis + waitMillis;
        if (source.getValidFrom().getElapsedMillis() > startMillis || source.getValidUntil().getElapsedMillis() < boundary) {
            throw rejectGathering(ENVIRONMENT_INTERVAL_INVALID);
        }
        accepted = request;
        stage(GatheringStage.INTAKING, startMillis, boundary);
    }

    private void preflight(GatheringView view, GatheringChoice selected) {
        // 先检查无副作用的失败条件，再启动时间结算；最终账本操作仍逐次执行完整校验。
        BodyMessages.BodySnapshot body = view.getBody();
        if (body.getVitality().getDead()) {
            throw rejectGathering(BODY_DEAD);
        }
        if (!view.getEnvironment().getAvailable() || selected.getIntakePerSecond() <= 0) {
            throw rejectGathering(ENVIRONMENT_UNAVAILABLE);
        }
        BodyMessages.OrganDefinition organ = body.getStructure().getOrgansList().stream()
                .filter(value -> value.getId().equals(selected.getOrganId())).findFirst().orElseThrow();
        BodyMessages.OrganState state = body.getOrganStatesList().stream()
                .filter(value -> value.getId().equals(organ.getId())).findFirst().orElseThrow();
        double partHp = body.getPartStatesList().stream().filter(value -> value.getId().equals(organ.getPartId()))
                .findFirst().orElseThrow().getHp();
        if (state.getHp() <= 0 || partHp <= 0) {
            throw rejectGathering(MODULE_UNAVAILABLE);
        }
        if (state.hasProcess()) {
            throw rejectGathering(ORGAN_BUSY);
        }
        BodyMessages.MeridianState targetState = body.getMeridianStatesList().stream()
                .filter(value -> value.getId().equals(selected.getTargetMeridianId())).findFirst().orElseThrow();
        if (targetState.getCondition() != BodyMessages.MeridianCondition.INTACT) {
            throw rejectGathering(ROUTE_UNAVAILABLE);
        }
        double input = GatheringChoices.total(selected.getRecipe().getInput());
        double remaining = organ.getQiCapacity() - GatheringChoices.total(state.getBuffer())
                - GatheringChoices.reserved(body, organ.getPortsList());
        if (input > remaining || GatheringChoices.total(selected.getRecipe().getOutput()) > selected.getTargetRemainingQi()) {
            throw rejectGathering(CAPACITY_EXCEEDED);
        }
        for (BodyMessages.Qi needed : selected.getRecipe().getInput().getEntriesList()) {
            double available = view.getEnvironment().getQi().getEntriesList().stream()
                    .filter(qi -> qi.getPhase() == needed.getPhase() && qi.getAttribute().equals(needed.getAttribute()))
                    .mapToDouble(BodyMessages.Qi::getAmount).sum();
            if (available < needed.getAmount()) {
                throw rejectGathering(INSUFFICIENT_QI);
            }
        }
    }

    private long intakeDuration(GatheringChoice selected) {
        // 向上取整到模拟毫秒，避免浮点误差让整批剂量略超过器官速度上限。
        try {
            return BigDecimal.valueOf(GatheringChoices.total(selected.getRecipe().getInput()))
                    .multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(selected.getIntakePerSecond()),
                            0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException failure) {
            throw rejectGathering(NUMERIC_LIMIT);
        }
    }

    public boolean advance() {
        // 加工边界优先结算；主动气流只在输入或下一片呼吸区间到期时计算。
        boolean changed = advanceGathering();
        if (breathing != null) {
            changed |= breathing.advance();
        }
        return changed;
    }

    private boolean advanceGathering() {
        // 宿主只需在 nextBoundaryMillis 到期或条件变化时调用；提前调用不会提交任何身体操作。
        if (closed || progress.getNextBoundaryMillis() == 0) {
            return false;
        }
        try {
            BodySnapshot body = read();
            TimeAdvancePlan plan = time.resolve(context, body);
            if (!plan.getTarget().getDomain().equals(body.settledAt().domain().value())
                    || !plan.getTarget().getEpoch().equals(body.settledAt().epoch())) {
                throw rejectGathering(CLOCK_MISMATCH);
            }
            if (plan.getMode() != TimeAdvanceMode.ACTIVE) {
                throw rejectGathering(TRANSPORT_FROZEN);
            }
            if (body.revision() != expectedRevision) {
                throw rejectGathering(REVISION_CONFLICT);
            }
            if (plan.getTarget().getElapsedMillis() < progress.getNextBoundaryMillis()) {
                return false;
            }

            // 每阶段只消费自己的已存在事实；推进失败保留已完成步骤与原始失败码。
            switch (progress.getStage()) {
                case INTAKING -> {
                    EnvironmentQiSource source = environment.apply(body).orElseThrow(() -> rejectGathering(ENVIRONMENT_UNAVAILABLE));
                    if (!source.getSpace().getSourceId().equals(sourceId)) {
                        throw rejectGathering(ENVIRONMENT_INTERVAL_INVALID);
                    }
                    execute(BodyCommand.newBuilder().setIntakeQi(IntakeQi.newBuilder()
                            .setOrganId(choice.getOrganId()).setQi(choice.getRecipe().getInput())));
                    execute(BodyCommand.newBuilder().setStartOrganRecipe(StartOrganRecipe.newBuilder()
                            .setOrganId(choice.getOrganId()).setRecipeId(choice.getRecipe().getId())
                            .setRecipeVersion(choice.getRecipe().getVersion())));
                    BodyMessages.BodySnapshot current = BodyProtobuf.toMessage(read());
                    BodyMessages.OrganState organ = current.getOrganStatesList().stream()
                            .filter(value -> value.getId().equals(choice.getOrganId())).findFirst().orElseThrow();
                    stage(GatheringStage.REFINING, current.getSettledAt().getElapsedMillis(), organ.getProcess().getCompletionMillis());
                }
                case REFINING -> {
                    // 宿主可能已为其他动作推进到本时刻；加工事实直接读取，避免再次空推进。
                    settleManual();
                    BodyMessages.BodySnapshot current = BodyProtobuf.toMessage(read());
                    double organRate = current.getStructure().getOrgansList().stream()
                            .filter(value -> value.getId().equals(choice.getOrganId())).findFirst().orElseThrow().getThroughputPerSecond();
                    double targetRate = current.getStructure().getMeridiansList().stream()
                            .filter(value -> value.getId().equals(choice.getTargetMeridianId())).findFirst().orElseThrow().getThroughputPerSecond();
                    java.util.Set<String> existingTransits = current.getTransitsList().stream()
                            .map(BodyMessages.QiTransit::getId).collect(java.util.stream.Collectors.toSet());
                    execute(BodyCommand.newBuilder().setTransportQi(TransportQi.newBuilder()
                            .setConnectionId(choice.getConnectionId()).setCargo(choice.getRecipe().getOutput())
                            .setThroughputPerSecond(Math.min(organRate, targetRate))));
                    BodySnapshot dispatched = read();
                    var transit = dispatched.transits().stream().filter(value -> !existingTransits.contains(value.id()))
                            .findFirst().orElseThrow();
                    transitId = transit.id();
                    stage(GatheringStage.TRANSPORTING, dispatched.settledAt().elapsedMillis(), transit.arrivalMillis());
                }
                case TRANSPORTING -> {
                    // 时间推进由同一身体共享，到达已经结算时继续检查货物即可。
                    settleManual();
                    BodySnapshot arrived = read();
                    var remaining = arrived.transits().stream().filter(value -> value.id().equals(transitId)).findFirst();
                    if (remaining.isPresent()) {
                        throw rejectGathering(ROUTE_UNAVAILABLE);
                    }
                    if (arrived.vitality().dead()) {
                        throw rejectGathering(BODY_DEAD);
                    }
                    stage(GatheringStage.COMPLETED, arrived.settledAt().elapsedMillis(), 0);
                    progress = progress.toBuilder().setStoredQi(GatheringChoices.total(choice.getRecipe().getOutput())).build();
                }
                default -> throw rejectGathering(INVALID_REQUEST);
            }
            return true;
        } catch (MeridianErrors.GatheringRejected rejection) {
            progress = progress.toBuilder().setStage(GatheringStage.FAILED).setNextBoundaryMillis(0)
                    .setFailure(rejection.code()).build();
            return true;
        }
    }

    void execute(BodyCommand.Builder command) {
        // 复用真实命令处理器，授权、反作弊、版本和原子提交仍走原有唯一入口。
        BodySnapshot body = read();
        if (body.revision() != expectedRevision) {
            throw rejectGathering(REVISION_CONFLICT);
        }
        command.setBodyId(bodyId.value()).setExpectedRevision(body.revision());
        RequestEnvelope request = RequestEnvelope.newBuilder().setProtocolVersion(1)
                .setRequestId(operationId + "/" + commandSequence++).setBodyCommand(command).build();
        ResponseEnvelope response = MeridianProtocol.decodeResponse(commands.handle(context, request.toByteArray()));
        if (response.hasFailure()) {
            throw rejectGathering(response.getFailure().getCode());
        }
        expectedRevision = response.getCommandCommitted().getRevision();
    }

    BodySnapshot read() {
        // 授权先于读取，撤权后连快照和环境预览都不会返回。
        if (closed || context == null || !access.canRead(context, bodyId)) {
            throw rejectGathering(ACCESS_DENIED);
        }
        return bodies.find(bodyId).orElseThrow(() -> rejectGathering(BODY_NOT_FOUND));
    }

    private void stage(GatheringStage stage, long startedMillis, long boundaryMillis) {
        // 阶段只描述编排，不保存任何可花费余额；UI 可用时长插值展示进度。
        progress = GatheringProgress.newBuilder().setStage(stage).setOrganId(choice.getOrganId())
                .setTargetMeridianId(choice.getTargetMeridianId()).setStartedMillis(startedMillis)
                .setNextBoundaryMillis(boundaryMillis).build();
    }

    public long nextBoundaryMillis() {
        // 宿主据此安排一次唤醒，不需要逐帧调用身体算子。
        if (breathing != null && breathing.nextBoundaryMillis() != 0) {
            if (progress.getNextBoundaryMillis() == 0) {
                return breathing.nextBoundaryMillis();
            }
            return Math.min(progress.getNextBoundaryMillis(), breathing.nextBoundaryMillis());
        }
        return progress.getNextBoundaryMillis();
    }

    long now() {
        // 时间域与活动模式必须来自宿主；暂停、换世界和冻结不能积累呼吸收益。
        BodySnapshot body = read();
        TimeAdvancePlan plan = time.resolve(context, body);
        if (!plan.getTarget().getDomain().equals(body.settledAt().domain().value())
                || !plan.getTarget().getEpoch().equals(body.settledAt().epoch())) {
            throw rejectGathering(CLOCK_MISMATCH);
        }
        if (plan.getMode() != TimeAdvanceMode.ACTIVE) {
            throw rejectGathering(TRANSPORT_FROZEN);
        }
        return plan.getTarget().getElapsedMillis();
    }

    void beginManual() {
        // 新练习只接管空闲编排，身体内原有疲劳仍从唯一快照读取。
        if (progress.getNextBoundaryMillis() != 0) {
            throw rejectGathering(GATHERING_BUSY);
        }
        expectedRevision = read().revision();
        operationId = UUID.randomUUID().toString();
        commandSequence = 0;
        settleManual();
    }

    void settleManual() {
        // 同毫秒无需空提交；其余时间仍复用寿命、器官加工和在途的统一推进。
        if (read().settledAt().elapsedMillis() != now()) {
            execute(BodyCommand.newBuilder().setAdvanceTime(AdvanceTime.getDefaultInstance()));
        }
    }

    void stopManualAction(String instanceId) {
        // 外部动作或伤势可能使原计划版本失效；清理只针对自己的实例，不继续原来的摄入计划。
        BodySnapshot body = read();
        expectedRevision = body.revision();
        if (body.actions().stream().noneMatch(action -> action.instanceId().equals(instanceId))) {
            return;
        }
        settleManual();

        // 时间推进可能恰好寿尽并清空动作，无需再向死体提交停止请求。
        if (read().actions().stream().anyMatch(action -> action.instanceId().equals(instanceId))) {
            execute(BodyCommand.newBuilder().setStopAction(StopAction.newBuilder().setInstanceId(instanceId)));
        }
    }

    boolean canCaptureInterval(long maximumMillis) {
        // 摄入仅使用本次主动操作的短区间；停气与环境换窗前的闲置时间先单独结清。
        BodySnapshot body = read();
        long fromMillis = body.settledAt().elapsedMillis();
        long untilMillis = now();
        Optional<EnvironmentQiSource> source = environment.apply(body);
        return untilMillis > fromMillis && untilMillis - fromMillis <= maximumMillis && source.isPresent()
                && source.get().getValidFrom().getElapsedMillis() <= fromMillis
                && source.get().getValidUntil().getElapsedMillis() >= untilMillis;
    }

    boolean capture(GatheringChoice selected, ExertTissues loads) {
        // 场景命中只是摄入候选；实际剂量仍经过环境原子扣账与器官能力校验。
        if (progress.getNextBoundaryMillis() != 0 || read().settledAt().elapsedMillis() >= now()) {
            return false;
        }
        preflight(view(), selected);
        choice = selected;
        execute(BodyCommand.newBuilder().setIntakeQi(IntakeQi.newBuilder()
                .setOrganId(choice.getOrganId()).setQi(choice.getRecipe().getInput()).addAllLoads(loads.getLoadsList())));
        execute(BodyCommand.newBuilder().setStartOrganRecipe(StartOrganRecipe.newBuilder()
                .setOrganId(choice.getOrganId()).setRecipeId(choice.getRecipe().getId())
                .setRecipeVersion(choice.getRecipe().getVersion())));
        var current = BodyProtobuf.toMessage(read());
        var organ = current.getOrganStatesList().stream()
                .filter(value -> value.getId().equals(choice.getOrganId())).findFirst().orElseThrow();
        stage(GatheringStage.REFINING, current.getSettledAt().getElapsedMillis(), organ.getProcess().getCompletionMillis());
        return true;
    }

    @Override
    public void close() {
        // 断线停止后续自动步骤；已摄入、加工与在途货物留在真实身体，交由宿主正常结算。
        if (breathing != null) {
            // 在撤销读写上下文前结束本会话动作；失败交给宿主重试，不能默默遗留永久占用。
            breathing.stop();
        }
        closed = true;
    }
}
