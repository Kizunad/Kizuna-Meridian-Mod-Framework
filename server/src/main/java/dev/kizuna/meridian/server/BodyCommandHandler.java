package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.LifespanSpending;
import dev.kizuna.meridian.core.QiTransport;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.OrganProcessing;
import dev.kizuna.meridian.core.OrganRecipe;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Host;
import dev.kizuna.meridian.error.MeridianErrors.LifespanRejected;
import dev.kizuna.meridian.error.MeridianErrors.LifespanRejectionReason;
import dev.kizuna.meridian.error.MeridianErrors.Protocol;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejected;
import dev.kizuna.meridian.error.MeridianErrors.TimeRejectionReason;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejected;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejectionReason;
import dev.kizuna.meridian.error.MeridianErrors.CapabilityRejected;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.TimeProtobuf;
import dev.kizuna.meridian.protocol.TransportProtobuf;
import dev.kizuna.meridian.protocol.CapabilityProtobuf;
import dev.kizuna.meridian.protocol.ActionProtobuf;
import dev.kizuna.meridian.core.BodyActions;
import dev.kizuna.meridian.core.OpeningRuntime;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.HealthProtobuf;
import dev.kizuna.meridian.protocol.pb.HealthMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiSource;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;

/**
 * Java 参考变更入口：授权意图、在身体事务中执行核心算子，并原子保存身体与回执。
 * 不认证连接、不实现数据库、不替宿主发送环境副作用。
 */
public final class BodyCommandHandler<C> {
    private static final System.Logger LOGGER = System.getLogger(BodyCommandHandler.class.getName());

    private final BodyCommandAccess<C> access;
    private final BodySecurityPolicy<C> security;
    private final BodyTimeSource<C> timeSource;
    private final BodyTransactionStore store;
    private final BodyAuditSink<C> audit;
    private final OrganRecipeCatalog recipes;
    private final BodyEnvironmentSource<C> environmentSource;
    private final ActionCatalog actions;
    private final MeridianOpeningCatalog openings;
    private final java.util.function.Predicate<C> debugAccess;
    private final BodyDamageHandler damageHandler;

    public BodyCommandHandler(BodyCommandAccess<C> access, BodySecurityPolicy<C> security,
                              BodyTimeSource<C> timeSource, BodyTransactionStore store, BodyAuditSink<C> audit) {
        // 不安装内容的宿主仍可运行基础身体命令，加工请求会明确返回配方不存在。
        this(access, security, timeSource, store, audit, new OrganRecipeCatalog(List.of()));
    }

    public BodyCommandHandler(BodyCommandAccess<C> access, BodySecurityPolicy<C> security,
                              BodyTimeSource<C> timeSource, BodyTransactionStore store, BodyAuditSink<C> audit,
                              OrganRecipeCatalog recipes) {
        // 既有宿主没有环境选择器时拒绝摄入，不推断某个默认世界或来源。
        this(access, security, timeSource, store, audit, recipes, (context, body, plan) -> Optional.empty());
    }

    public BodyCommandHandler(BodyCommandAccess<C> access, BodySecurityPolicy<C> security,
                              BodyTimeSource<C> timeSource, BodyTransactionStore store, BodyAuditSink<C> audit,
                              OrganRecipeCatalog recipes, BodyEnvironmentSource<C> environmentSource) {
        // 未装配动作内容的宿主仍能执行既有命令，动作启动会明确拒绝缺少定义。
        this(access, security, timeSource, store, audit, recipes, environmentSource, new ActionCatalog(List.of()));
    }

    public BodyCommandHandler(BodyCommandAccess<C> access, BodySecurityPolicy<C> security,
                              BodyTimeSource<C> timeSource, BodyTransactionStore store, BodyAuditSink<C> audit,
                              OrganRecipeCatalog recipes, BodyEnvironmentSource<C> environmentSource, ActionCatalog actions) {
        // 旧装配没有开脉内容时仍可运行其他身体命令。
        this(access, security, timeSource, store, audit, recipes, environmentSource, actions,
                new MeridianOpeningCatalog(List.of()));
    }

    public BodyCommandHandler(BodyCommandAccess<C> access, BodySecurityPolicy<C> security,
                              BodyTimeSource<C> timeSource, BodyTransactionStore store, BodyAuditSink<C> audit,
                              OrganRecipeCatalog recipes, BodyEnvironmentSource<C> environmentSource,
                              ActionCatalog actions, MeridianOpeningCatalog openings) {
        // 常规装配默认拒绝调试命令，即使宿主原来的普通授权策略允许所有身体操作。
        this(access, security, timeSource, store, audit, recipes, environmentSource, actions, openings, context -> false);
    }

    public BodyCommandHandler(BodyCommandAccess<C> access, BodySecurityPolicy<C> security,
                              BodyTimeSource<C> timeSource, BodyTransactionStore store, BodyAuditSink<C> audit,
                              OrganRecipeCatalog recipes, BodyEnvironmentSource<C> environmentSource,
                              ActionCatalog actions, MeridianOpeningCatalog openings,
                              java.util.function.Predicate<C> debugAccess) {
        // 未装配生物策略时使用通用部位兜底；具体生物可通过完整构造器替换它。
        this(access, security, timeSource, store, audit, recipes, environmentSource, actions, openings,
                debugAccess, new DefaultBodyDamageHandler());
    }

    public BodyCommandHandler(BodyCommandAccess<C> access, BodySecurityPolicy<C> security,
                              BodyTimeSource<C> timeSource, BodyTransactionStore store, BodyAuditSink<C> audit,
                              OrganRecipeCatalog recipes, BodyEnvironmentSource<C> environmentSource,
                              ActionCatalog actions, MeridianOpeningCatalog openings,
                              java.util.function.Predicate<C> debugAccess, BodyDamageHandler damageHandler) {
        // 所有依赖都显式装配；公共宿主可选择放行的私有策略，但不能省略基础授权。
        this.access = MeridianErrors.required(access, Host.ACCESS_REQUIRED);
        this.security = MeridianErrors.required(security, Host.SECURITY_REQUIRED);
        this.timeSource = MeridianErrors.required(timeSource, Host.TIME_SOURCE_REQUIRED);
        this.store = MeridianErrors.required(store, Host.STORE_REQUIRED);
        this.audit = MeridianErrors.required(audit, Host.AUDIT_REQUIRED);
        this.recipes = MeridianErrors.required(recipes, Host.RECIPES_REQUIRED);
        this.environmentSource = MeridianErrors.required(environmentSource, Host.ENVIRONMENT_SOURCE_REQUIRED);
        this.actions = MeridianErrors.required(actions, MeridianErrors.Data.ACTION_CATALOG_REQUIRED);
        this.openings = MeridianErrors.required(openings, MeridianErrors.Opening.RULE_ID_REQUIRED);
        this.debugAccess = MeridianErrors.required(debugAccess, Host.ACCESS_REQUIRED);
        this.damageHandler = MeridianErrors.required(damageHandler, MeridianErrors.Health.HANDLER);
    }

    ActionCatalog actionCatalog() {
        // 通用吐纳编排在启用前核对所绑定的动作，避免调用后才发现内容漏掉呼吸组织。
        return actions;
    }

    public byte[] handle(C trustedContext, byte[] payload) {
        // 普通协议入口不接受客户端宣称命中；接触事件必须由可信宿主单独提交。
        return handle(trustedContext, payload, false, false);
    }

    public byte[] handleCapturedQi(C trustedContext, byte[] payload) {
        // 仅供已完成权威碰撞检查的宿主调用，仍经过身份、私有策略与原子事务。
        return handle(trustedContext, payload, true, false);
    }

    public byte[] handleHealthEffect(C trustedContext, byte[] payload) {
        // 宿主确认攻击或治疗来源后使用此入口；不注册成接受玩家自报剂量的网络处理器。
        return handle(trustedContext, payload, false, true);
    }

    private byte[] handle(C trustedContext, byte[] payload, boolean trustedCapture, boolean trustedHealth) {
        // 解析失败不进入业务异常分支，避免从不可信字节伪造关联 ID 或调用宿主。
        var request = MeridianProtocol.decodeRequest(payload);
        if (!request.hasBodyCommand()) {
            throw MeridianErrors.invalid(Host.COMMAND_ONLY);
        }
        if (request.getBodyCommand().hasCaptureQi() != trustedCapture
                || HealthProtobuf.isHealthCommand(request.getBodyCommand()) != trustedHealth) {
            return failure(request.getRequestId(), MeridianMessages.FailureCode.ACCESS_DENIED);
        }
        if (trustedContext == null) {
            // 没有宿主认证上下文时不查询身体存在性，也不调用授权回调。
            return failure(request.getRequestId(), MeridianMessages.FailureCode.ACCESS_DENIED);
        }

        try {
            var command = request.getBodyCommand();
            var caller = authorize(trustedContext, command);
            if (caller.isEmpty()) {
                // 读取权限不能隐式转化为寿元支出权限；授权策略必须明确返回可信调用者。
                return failure(request.getRequestId(), MeridianMessages.FailureCode.ACCESS_DENIED);
            }
            var bodyId = new BodyId(command.getBodyId());
            var result = store.transact(bodyId, transaction -> execute(
                    trustedContext, caller.get(), request.getRequestId(), command, transaction));
            if (result.receipt() == null) {
                // 业务拒绝只返回固定错误；没有暂存就没有审计通知。
                return failure(request.getRequestId(), result.failure());
            }
            if (result.replayed()) {
                // 已提交重试直接复用原回执，不重新运行安全策略、扣款或通知审计。
                return committed(request.getRequestId(), result.receipt().getCommitted());
            }
            try {
                audit.onCommitted(trustedContext, result.receipt(), result.before(), result.after());
            } catch (RuntimeException exception) {
                // 审计是只读通知，不能把已提交身体回滚或再次执行；异常只写日志。
                LOGGER.log(System.Logger.Level.WARNING, Host.AUDIT_FAILED, exception);
            }
            return committed(request.getRequestId(), result.receipt().getCommitted());
        } catch (RuntimeException exception) {
            // 存储、策略或一致性故障失败关闭，不向调用方暴露内部实现细节。
            LOGGER.log(System.Logger.Level.ERROR, Host.COMMAND_FAILED, exception);
            return failure(request.getRequestId(), MeridianMessages.FailureCode.INTERNAL_ERROR);
        }
    }

    private Execution execute(C trustedContext, String caller, String requestId,
                              MeridianMessages.BodyCommand command, BodyTransactionStore.Transaction transaction) {
        // 排队等待事务锁期间可能已撤权；读取存在性或历史回执之前重新确认身份与许可。
        if (!authorize(trustedContext, command).equals(Optional.of(caller))) {
            return Execution.conflict(MeridianMessages.FailureCode.ACCESS_DENIED);
        }

        // 先查同一可信调用者的回执；重试必须返回旧结果而不是再次执行支出。
        var previous = transaction.receipt(caller, requestId);
        if (previous.isPresent()) {
            // 不信任适配器索引的正确性，恢复回执也要检查键和内部版本关系。
            var receipt = previous.get();
            MeridianProtocol.validateReceipt(receipt);
            if (!receipt.getCallerId().equals(caller) || !receipt.getRequestId().equals(requestId)
                    || !receipt.getCommand().getBodyId().equals(command.getBodyId())) {
                throw MeridianErrors.inconsistent(Host.RECEIPT_MISMATCH);
            }
            if (!authorize(trustedContext, command).equals(Optional.of(caller))) {
                return Execution.conflict(MeridianMessages.FailureCode.ACCESS_DENIED);
            }
            if (!receipt.getCommand().equals(command)) {
                return Execution.conflict(MeridianMessages.FailureCode.REQUEST_ID_CONFLICT);
            }
            return Execution.replayed(receipt);
        }

        // 没有成功回执时才读取当前身体，并确认请求所依据的版本仍然有效。
        var before = transaction.snapshot();
        if (before.isEmpty()) {
            return Execution.conflict(MeridianMessages.FailureCode.BODY_NOT_FOUND);
        }
        var body = before.get();
        if (!body.id().value().equals(command.getBodyId())) {
            throw MeridianErrors.inconsistent(Host.WRONG_BODY);
        }
        if (body.revision() != command.getExpectedRevision()) {
            return Execution.conflict(MeridianMessages.FailureCode.REVISION_CONFLICT);
        }

        BodySnapshot after;
        MeridianMessages.TimeAdvanceResult timeResult = null;
        BodyMessages.OrganRecipe recipeFact = null;
        MeridianMessages.OrganIntakeResult intakeFact = null;
        MeridianMessages.QiCaptureResult captureFact = null;
        BodyMessages.ActionDefinition actionFact = null;
        MeridianMessages.OpeningCommandResult openingFact = null;
        HealthMessages.HealthResult healthFact = null;
        try {
            // 瞬时发运、加工启动和做功使用已结算时刻；摄入自身消费可信活动区间。
            if (command.hasTransportQi() || command.hasStartOrganRecipe() || command.hasExertTissue() || command.hasExertTissues()
                    || command.hasCaptureQi()
                    || HealthProtobuf.isHealthCommand(command)
                    || command.hasStartAction() || command.hasStopAction()
                    || command.hasStartMeridianOpening() || command.hasPauseMeridianOpening() || command.hasDebugQi()) {
                requireActiveSettledTime(trustedContext, body);
            }
            if (HealthProtobuf.isHealthCommand(command)) {
                // 附属处理器拿到身体前先检查权限与可变更状态，避免无效请求触发插件。
                if (!security.allows(trustedContext, command, body)) {
                    return Execution.conflict(MeridianMessages.FailureCode.ACCESS_DENIED);
                }
                if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
                    return Execution.conflict(MeridianMessages.FailureCode.BODY_DEAD);
                }
                if (body.revision() == Long.MAX_VALUE) {
                    return Execution.conflict(MeridianMessages.FailureCode.REVISION_EXHAUSTED);
                }
            }
            // 先执行纯计算基础校验，私有策略无权跳过余额、死亡或版本上限约束。
            after = switch (command.getOperationCase()) {
                case SPEND_LIFESPAN -> LifespanSpending.apply(body, command.getSpendLifespan().getSeconds());
                case ADVANCE_TIME -> {
                    // 可信时间只读取一次；保存采用的计划，重试或恢复不重新询问当前时钟。
                    MeridianMessages.TimeAdvancePlan planMessage = MeridianErrors.required(
                            timeSource.resolve(trustedContext, body), Host.TIME_PLAN_REQUIRED);
                    BodyTimeAdvancement.Plan plan = TimeProtobuf.fromMessage(planMessage);
                    BodyTimeAdvancement.Result result = BodyTimeAdvancement.apply(body, plan);
                    timeResult = TimeProtobuf.toMessage(planMessage, result);
                    yield result.body();
                }
                case TRANSPORT_QI -> {
                    // 端点、余额、吞吐和容量由运输算子统一验证。
                    QiTransport.Request transportRequest = TransportProtobuf.fromMessage(command.getTransportQi());
                    yield QiTransport.start(body, transportRequest);
                }
                case START_ORGAN_RECIPE -> {
                    // 仅从宿主不可变目录解析精确版本，客户端不能修改剂量、时长或产物。
                    MeridianMessages.StartOrganRecipe requestedRecipe = command.getStartOrganRecipe();
                    recipeFact = recipes.find(requestedRecipe.getRecipeId(), requestedRecipe.getRecipeVersion())
                            .orElseThrow(() -> MeridianErrors.rejectCapability(MeridianErrors.CapabilityRejectionReason.RECIPE_NOT_FOUND));
                    var organId = new Anatomy.OrganId(requestedRecipe.getOrganId());
                    OrganRecipe recipe = CapabilityProtobuf.fromRecipe(recipeFact);
                    yield OrganProcessing.start(body, organId, recipe);
                }
                case EXERT_TISSUE -> {
                    // 解码只提取负载，身体能否完成动作及疲劳变化由核心计算。
                    TissueCapabilities.Exertion exertion = CapabilityProtobuf.fromExertion(command.getExertTissue());
                    yield TissueCapabilities.exert(body, exertion);
                }
                case EXERT_TISSUES -> {
                    // 呼吸肌和控制组织等共同负载使用同一候选身体与一次事务。
                    var loads = command.getExertTissues().getLoadsList().stream()
                            .map(CapabilityProtobuf::fromExertion).toList();
                    yield TissueCapabilities.exertAll(body, loads);
                }
                case INTAKE_QI -> {
                    // 时间和整个区间的来源均来自可信宿主；账户必须在同一事务内读取和锁定。
                    MeridianMessages.TimeAdvancePlan plan = MeridianErrors.required(
                            timeSource.resolve(trustedContext, body), Host.TIME_PLAN_REQUIRED);
                    String sourceId = environmentSource.resolve(trustedContext, body, plan)
                            .orElseThrow(() -> MeridianErrors.rejectIntake(MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE));
                    EnvironmentQiSource source = transaction.environmentSource(sourceId)
                            .orElseThrow(() -> MeridianErrors.rejectIntake(MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE));
                    if (!source.getSpace().getSourceId().equals(sourceId)) {
                        throw MeridianErrors.inconsistent(Host.INTAKE_STAGE_MISMATCH);
                    }
                    OrganIntakeExecution.Result result = OrganIntakeExecution.calculate(body, command.getIntakeQi(), plan, source);
                    intakeFact = result.fact();
                    yield result.body();
                }
                case CAPTURE_QI -> {
                    // 浓度和配方来自锁定来源与可信目录；请求不携带最终产量。
                    var requested = command.getCaptureQi();
                    var recipe = recipes.find(requested.getRecipeId(), requested.getRecipeVersion())
                            .orElseThrow(() -> MeridianErrors.rejectIntake(MeridianMessages.FailureCode.RECIPE_NOT_FOUND));
                    var plan = timeSource.resolve(trustedContext, body);
                    String sourceId = environmentSource.resolve(trustedContext, body, plan)
                            .orElseThrow(() -> MeridianErrors.rejectIntake(MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE));
                    EnvironmentQiSource source = transaction.environmentSource(sourceId)
                            .orElseThrow(() -> MeridianErrors.rejectIntake(MeridianMessages.FailureCode.ENVIRONMENT_UNAVAILABLE));
                    if (!source.getSpace().getSourceId().equals(sourceId)) {
                        throw MeridianErrors.inconsistent(Host.INTAKE_STAGE_MISMATCH);
                    }
                    QiCaptureExecution.Result result = QiCaptureExecution.calculate(body, requested, recipe, source);
                    captureFact = result.fact();
                    yield result.body();
                }
                case START_ACTION -> {
                    // 组织名单只能来自可信目录，动作开始不能追溯改变此前区间的恢复。
                    MeridianMessages.StartAction request = command.getStartAction();
                    actionFact = actions.find(request.getActionId(), request.getActionVersion())
                            .orElseThrow(() -> MeridianErrors.rejectCapability(MeridianErrors.CapabilityRejectionReason.ACTION_NOT_FOUND));
                    yield BodyActions.start(body, request.getInstanceId(), ActionProtobuf.fromDefinition(actionFact));
                }
                case STOP_ACTION -> BodyActions.stop(body, command.getStopAction().getInstanceId());
                case DEBUG_QI -> dev.kizuna.meridian.core.QiDebugging.apply(body, command.getDebugQi());
                case DAMAGE_BODY, HEAL_BODY, RESTORE_FATIGUE, CONFIRM_BODY_DEATH -> {
                    HealthMessages.DamageResolution resolution = null;
                    if (command.hasDamageBody()) {
                        // 死体不能重新选点或调用伤害策略，避免把无候选误报为模块失能。
                        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
                            throw MeridianErrors.rejectHealth(MeridianMessages.FailureCode.BODY_DEAD);
                        }
                        // 策略只计算一次；默认策略和生物附属均通过固定方案进入同一事务。
                        var context = HealthMessages.DamageContext.newBuilder().setBody(BodyProtobuf.toMessage(body))
                                .setDamage(command.getDamageBody()).build();
                        resolution = HealthProtobuf.decodeResolution(damageHandler.resolve(HealthProtobuf.encodeContext(context)));
                    }
                    BodyHealthExecution.Result result = BodyHealthExecution.calculate(body, command, resolution);
                    healthFact = result.fact();
                    yield result.body();
                }
                case START_MERIDIAN_OPENING -> {
                    // 执行参数来自精确目录版本，客户端只能选规则。
                    var request = command.getStartMeridianOpening();
                    var rule = openings.find(request.getRuleId(), request.getRuleVersion())
                            .orElseThrow(() -> MeridianErrors.rejectIntake(MeridianMessages.FailureCode.OPENING_RULE_NOT_FOUND));
                    var action = actions.find(rule.getActionId(), rule.getActionVersion())
                            .orElseThrow(() -> MeridianErrors.rejectIntake(MeridianMessages.FailureCode.ACTION_NOT_FOUND));
                    BodySnapshot started = OpeningRuntime.start(body, OpeningProtobuf.fromRule(rule), ActionProtobuf.fromDefinition(action));
                    openingFact = MeridianMessages.OpeningCommandResult.newBuilder().setRule(rule).setAction(action)
                            .setReleasedQi(BodyMessages.QiInventory.getDefaultInstance()).build();
                    yield started;
                }
                case PAUSE_MERIDIAN_OPENING -> {
                    // 暂停依据当前任务保存的事实，内容卸载后仍可释放超压并解除占用。
                    var result = OpeningRuntime.pause(body, new Anatomy.MeridianId(command.getPauseMeridianOpening().getMeridianId()));
                    var task = body.openingTask();
                    var action = body.actions().stream().filter(item -> item.instanceId().equals(task.actionInstanceId())).findFirst().orElseThrow();
                    openingFact = MeridianMessages.OpeningCommandResult.newBuilder().setRule(OpeningProtobuf.toRule(task.rule()))
                            .setAction(ActionProtobuf.toDefinition(action.definition()))
                            .setReleasedQi(BodyProtobuf.toInventory(result.releasedQi())).build();
                    yield result.body();
                }
                case OPERATION_NOT_SET -> throw MeridianErrors.invalid(Protocol.MISSING_COMMAND);
            };
        } catch (MeridianErrors.HealthRejected rejected) {
            // 一笔效果中任一模块不可用即整笔拒绝，不保存部分回血或疲劳变化。
            return Execution.conflict(rejected.code());
        } catch (MeridianErrors.IntakeRejected rejected) {
            // 环境供给和摄入失败不写任何状态，直接返回 protoc 定义的原因。
            return Execution.conflict(rejected.code());
        } catch (LifespanRejected rejected) {
            // 只有核心业务拒绝映射为业务错误，宿主回调异常仍按内部故障处理。
            return Execution.conflict(lifespanFailureCode(rejected.reason()));
        } catch (TimeRejected rejected) {
            // 倒流、时钟不匹配或尚未处理的事件属于明确业务拒绝，不留下部分推进。
            return Execution.conflict(timeFailureCode(rejected.reason()));
        } catch (TransportRejected rejected) {
            // 容量、余额或通路不足整笔拒绝，不提交一份已经扣源却未发运的身体。
            return Execution.conflict(transportFailureCode(rejected.reason()));
        } catch (CapabilityRejected rejected) {
            // 固定业务原因与 protoc 失败码同名；不会把任意异常文本反射成响应。
            return Execution.conflict(MeridianMessages.FailureCode.valueOf(rejected.reason().name()));
        }

        if (!security.allows(trustedContext, command, body)
                || !authorize(trustedContext, command).equals(Optional.of(caller))) {
            // 私有策略可能耗时；提交前重验授权且身份必须未变，身体由事务保持稳定。
            return Execution.conflict(MeridianMessages.FailureCode.ACCESS_DENIED);
        }

        // 成功事实先在内存中组装，只有完整验证后才能进入事务暂存。
        var committed = MeridianMessages.CommandCommitted.newBuilder()
                .setBodyId(body.id().value()).setPreviousRevision(body.revision())
                .setRevision(after.revision()).build();
        var receiptBuilder = MeridianMessages.CommandReceipt.newBuilder()
                .setCallerId(caller).setRequestId(requestId).setCommand(command).setCommitted(committed);
        if (timeResult != null) {
            // 事务适配器必须依据该记录将死亡释放入环境账，审计回调不承担资源转移。
            receiptBuilder.setTimeAdvance(timeResult);
        }
        if (recipeFact != null) {
            // 持久化发起时采用的定义，恢复和重复请求不读取可能已卸载的目录。
            receiptBuilder.setOrganRecipe(recipeFact);
        }
        if (intakeFact != null) {
            // 保存原环境事实以支持幂等重试；不从当前窗口重建历史成功结果。
            receiptBuilder.setOrganIntake(intakeFact);
        }
        if (captureFact != null) {
            // 接触交换单独记录，恢复时不重放粒子，也不再次发放已经入账的产物。
            receiptBuilder.setQiCapture(captureFact);
        }
        if (actionFact != null) {
            // 重试与事务复算使用原定义，后续目录变更不能改变过去的启动事实。
            receiptBuilder.setActionDefinition(actionFact);
        }
        if (openingFact != null) {
            receiptBuilder.setOpening(openingFact);
        }
        if (healthFact != null) {
            // 死亡和中断增压的释放属于同一事务，不能留给审计回调补发。
            receiptBuilder.setHealth(healthFact);
        }
        var receipt = receiptBuilder.build();

        // 在暂存之前完成协议校验，避免提交一个自身无法编码或恢复的状态。
        BodyProtobuf.toMessage(after);
        MeridianProtocol.validateReceipt(receipt);
        committed(requestId, committed);

        // stage 同时校验 revision、身体身份和回执关系；它成功返回后才允许事务发布。
        transaction.stage(after, receipt);
        return Execution.committed(receipt, body, after);
    }

    private void requireActiveSettledTime(C trustedContext, BodySnapshot body) {
        // 每条资源命令只读一次可信时钟；时钟变化先结算，再用新 revision 提交命令。
        MeridianMessages.TimeAdvancePlan planMessage = MeridianErrors.required(
                timeSource.resolve(trustedContext, body), Host.TIME_PLAN_REQUIRED);
        BodyTimeAdvancement.Plan plan = TimeProtobuf.fromMessage(planMessage);
        if (!plan.target().domain().equals(body.settledAt().domain())
                || !plan.target().epoch().equals(body.settledAt().epoch())) {
            throw MeridianErrors.rejectTime(TimeRejectionReason.CLOCK_MISMATCH);
        }
        if (!plan.target().equals(body.settledAt())) {
            throw MeridianErrors.rejectTransport(TransportRejectionReason.TIME_REQUIRES_SETTLEMENT);
        }
        if (plan.mode() != BodyTimeAdvancement.Mode.ACTIVE) {
            // 沿用已有冻结错误编号，适用于同一资源时间域中的所有主动操作。
            throw MeridianErrors.rejectTransport(TransportRejectionReason.TRANSPORT_FROZEN);
        }
    }

    private Optional<String> authorize(C trustedContext, MeridianMessages.BodyCommand command) {
        // 宿主身份同样遵守持久化协议的文本上限；null 属于适配错误而非明确拒绝。
        if (command.hasDebugQi() && !debugAccess.test(trustedContext)) {
            return Optional.empty();
        }
        var caller = MeridianErrors.required(access.authorize(trustedContext, command), Host.AUTH_RESULT_REQUIRED);
        if (caller.isPresent()) {
            var id = caller.get();
            if (id.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(id)
                    || id.getBytes(StandardCharsets.UTF_8).length > MeridianProtocol.MAX_TEXT_BYTES) {
                throw MeridianErrors.inconsistent(Host.CALLER_ID_INVALID);
            }
        }
        return caller;
    }

    private static MeridianMessages.FailureCode lifespanFailureCode(LifespanRejectionReason reason) {
        // 核心原因映射为固定公开枚举，避免把实现异常名称变成协议。
        return switch (reason) {
            case BODY_DEAD -> MeridianMessages.FailureCode.BODY_DEAD;
            case INSUFFICIENT_LIFESPAN -> MeridianMessages.FailureCode.INSUFFICIENT_LIFESPAN;
            case REVISION_EXHAUSTED -> MeridianMessages.FailureCode.REVISION_EXHAUSTED;
            case NUMERIC_LIMIT -> MeridianMessages.FailureCode.NUMERIC_LIMIT;
        };
    }

    private static MeridianMessages.FailureCode timeFailureCode(TimeRejectionReason reason) {
        // 映射显式列出，新增核心拒绝原因时必须同时考虑公开协议语义。
        return switch (reason) {
            case BODY_DEAD -> MeridianMessages.FailureCode.BODY_DEAD;
            case REVISION_EXHAUSTED -> MeridianMessages.FailureCode.REVISION_EXHAUSTED;
            case CLOCK_MISMATCH -> MeridianMessages.FailureCode.CLOCK_MISMATCH;
            case TIME_NOT_ADVANCED -> MeridianMessages.FailureCode.TIME_NOT_ADVANCED;
            case PENDING_TIME_EVENT -> MeridianMessages.FailureCode.PENDING_TIME_EVENT;
            case NUMERIC_LIMIT -> MeridianMessages.FailureCode.NUMERIC_LIMIT;
        };
    }

    private static MeridianMessages.FailureCode transportFailureCode(TransportRejectionReason reason) {
        // 运输拒绝与协议类别逐项对应，方便调用者调整容量、线路或申请吞吐。
        return switch (reason) {
            case MERIDIAN_UNOPENED -> MeridianMessages.FailureCode.MERIDIAN_UNOPENED;
            case BODY_DEAD -> MeridianMessages.FailureCode.BODY_DEAD;
            case REVISION_EXHAUSTED -> MeridianMessages.FailureCode.REVISION_EXHAUSTED;
            case NUMERIC_LIMIT -> MeridianMessages.FailureCode.NUMERIC_LIMIT;
            case ROUTE_NOT_FOUND -> MeridianMessages.FailureCode.ROUTE_NOT_FOUND;
            case ROUTE_UNSUPPORTED -> MeridianMessages.FailureCode.ROUTE_UNSUPPORTED;
            case ROUTE_UNAVAILABLE -> MeridianMessages.FailureCode.ROUTE_UNAVAILABLE;
            case INSUFFICIENT_QI -> MeridianMessages.FailureCode.INSUFFICIENT_QI;
            case CAPACITY_EXCEEDED -> MeridianMessages.FailureCode.CAPACITY_EXCEEDED;
            case THROUGHPUT_EXCEEDED -> MeridianMessages.FailureCode.THROUGHPUT_EXCEEDED;
            case TIME_REQUIRES_SETTLEMENT -> MeridianMessages.FailureCode.TIME_REQUIRES_SETTLEMENT;
            case TRANSPORT_FROZEN -> MeridianMessages.FailureCode.TRANSPORT_FROZEN;
        };
    }

    private static byte[] committed(String requestId, MeridianMessages.CommandCommitted committed) {
        // 成功响应只有提交版本，不返回可再次上传的身体快照。
        return MeridianProtocol.encodeResponse(MeridianMessages.ResponseEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION).setRequestId(requestId)
                .setCommandCommitted(committed).build());
    }

    private static byte[] failure(String requestId, MeridianMessages.FailureCode code) {
        // 错误响应不泄漏存储、策略或异常细节，仅保留稳定关联 ID 和公开类别。
        return MeridianProtocol.encodeResponse(MeridianMessages.ResponseEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION).setRequestId(requestId)
                .setFailure(MeridianMessages.RequestFailure.newBuilder().setCode(code)).build());
    }

    private record Execution(MeridianMessages.CommandReceipt receipt, BodySnapshot before,
                             BodySnapshot after, MeridianMessages.FailureCode failure, boolean replayed) {
        private static Execution committed(MeridianMessages.CommandReceipt receipt, BodySnapshot before,
                                            BodySnapshot after) {
            // 新提交带有前后快照，审计可以记录差异但不能修改它们。
            return new Execution(receipt, before, after, null, false);
        }

        private static Execution replayed(MeridianMessages.CommandReceipt receipt) {
            // 重试没有新的状态转换，前后快照不供审计调用。
            return new Execution(receipt, null, null, null, true);
        }

        private static Execution conflict(MeridianMessages.FailureCode failure) {
            // 事务内的业务拒绝不暂存任何新状态。
            return new Execution(null, null, null, failure, false);
        }
    }
}
