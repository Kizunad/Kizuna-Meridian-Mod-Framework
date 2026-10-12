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
import dev.kizuna.meridian.protocol.pb.BodyMessages;
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

    public BodyCommandHandler(BodyCommandAccess<C> access, BodySecurityPolicy<C> security,
                              BodyTimeSource<C> timeSource, BodyTransactionStore store, BodyAuditSink<C> audit) {
        // 不安装内容的宿主仍可运行基础身体命令，加工请求会明确返回配方不存在。
        this(access, security, timeSource, store, audit, new OrganRecipeCatalog(List.of()));
    }

    public BodyCommandHandler(BodyCommandAccess<C> access, BodySecurityPolicy<C> security,
                              BodyTimeSource<C> timeSource, BodyTransactionStore store, BodyAuditSink<C> audit,
                              OrganRecipeCatalog recipes) {
        // 所有依赖都显式装配；公共宿主可选择放行的私有策略，但不能省略基础授权。
        this.access = MeridianErrors.required(access, Host.ACCESS_REQUIRED);
        this.security = MeridianErrors.required(security, Host.SECURITY_REQUIRED);
        this.timeSource = MeridianErrors.required(timeSource, Host.TIME_SOURCE_REQUIRED);
        this.store = MeridianErrors.required(store, Host.STORE_REQUIRED);
        this.audit = MeridianErrors.required(audit, Host.AUDIT_REQUIRED);
        this.recipes = MeridianErrors.required(recipes, Host.RECIPES_REQUIRED);
    }

    public byte[] handle(C trustedContext, byte[] payload) {
        // 解析失败不进入业务异常分支，避免从不可信字节伪造关联 ID 或调用宿主。
        var request = MeridianProtocol.decodeRequest(payload);
        if (!request.hasBodyCommand()) {
            throw MeridianErrors.invalid(Host.COMMAND_ONLY);
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
        try {
            // 资源操作一律基于宿主当前已结算时刻，不能用陈旧状态跳过到达、加工或寿尽。
            if (command.hasTransportQi() || command.hasStartOrganRecipe() || command.hasExertTissue()) {
                requireActiveSettledTime(trustedContext, body);
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
                case OPERATION_NOT_SET -> throw MeridianErrors.invalid(Protocol.MISSING_COMMAND);
            };
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
