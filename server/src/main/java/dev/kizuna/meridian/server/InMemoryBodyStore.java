package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.BodyActions;
import dev.kizuna.meridian.protocol.ActionProtobuf;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.QiTransport;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.OrganProcessing;
import dev.kizuna.meridian.core.OpeningRuntime;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Host;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.OpeningProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.TimeProtobuf;
import dev.kizuna.meridian.protocol.TransportProtobuf;
import dev.kizuna.meridian.protocol.CapabilityProtobuf;
import dev.kizuna.meridian.protocol.EnvironmentProtobuf;
import dev.kizuna.meridian.environment.EnvironmentAbsorption;
import dev.kizuna.meridian.environment.EnvironmentQiRules;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiSource;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TimeCursor;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CommandReceipt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** 可运行的内存事务参考；不写磁盘，不能替代 Fabric/Bong 持久化适配。 */
public final class InMemoryBodyStore implements BodyTransactionStore, BodySnapshotReader {
    private final Map<BodyId, StoredBody> bodies = new HashMap<>();
    private final Map<String, EnvironmentQiSource> sources = new HashMap<>();
    private QiInventory environmentQi;
    private boolean transactionOpen;

    public InMemoryBodyStore(List<BodySnapshot> snapshots, List<CommandReceipt> receipts) {
        // 无环境历史时可从空账启动；已有死亡释放或加工损耗必须一起恢复环境余额。
        this(snapshots, receipts, QiInventory.empty());
        if (receipts.stream().anyMatch(receipt -> (receipt.hasTimeAdvance()
                && receipt.getTimeAdvance().getReleasedQi().getEntriesCount() > 0)
                || (receipt.hasOpening() && receipt.getOpening().getReleasedQi().getEntriesCount() > 0)
                || (receipt.getCommand().hasExecuteActivity()
                && receipt.getCommand().getExecuteActivity().getReleasedQi().getEntriesCount() > 0)
                || (receipt.hasHealth() && receipt.getHealth().getReleasedQi().getEntriesCount() > 0))) {
            throw MeridianErrors.invalid(Host.ENVIRONMENT_REQUIRED);
        }
    }

    public InMemoryBodyStore(List<BodySnapshot> snapshots, List<CommandReceipt> receipts, QiInventory environmentQi) {
        // 旧装配没有区域库存；带摄入历史的存档必须走完整恢复入口。
        this(snapshots, receipts, environmentQi, List.of());
    }

    public InMemoryBodyStore(List<BodySnapshot> snapshots, List<CommandReceipt> receipts,
                             QiInventory environmentQi, List<EnvironmentQiSource> sources) {
        // 环境账恢复已保存值，绝不能遍历历史回执再次发放真元。
        this.environmentQi = MeridianErrors.required(environmentQi, Host.ENVIRONMENT_REQUIRED);
        for (EnvironmentQiSource source : List.copyOf(sources)) {
            EnvironmentProtobuf.validateSource(source);
            if (this.sources.putIfAbsent(source.getSpace().getSourceId(), source) != null) {
                throw MeridianErrors.invalid(Host.DUPLICATE_SOURCE);
            }
        }
        // 恢复时显式提供身体与成功回执，禁止自动补满或用重复身份覆盖旧身体。
        for (var snapshot : List.copyOf(snapshots)) {
            BodyProtobuf.toMessage(snapshot);
            if (bodies.putIfAbsent(snapshot.id(), new StoredBody(snapshot, new HashMap<>())) != null) {
                throw MeridianErrors.invalid(Host.DUPLICATE_BODY);
            }
        }
        for (var receipt : List.copyOf(receipts)) {
            MeridianProtocol.validateReceipt(receipt);
            if (receipt.hasOrganIntake() || receipt.hasQiCapture()) {
                // 恢复必须连同来源当前库存与预算；回执只用于去重，绝不重演扣账。
                var environmentReceipt = receipt.hasQiCapture() ? receipt.getQiCapture().getEnvironment()
                        : receipt.getOrganIntake().getEnvironment();
                var source = this.sources.get(environmentReceipt.getExchange().getOffer().getSourceId());
                if (source == null || source.getRevision() < environmentReceipt.getSourceRevision()) {
                    throw MeridianErrors.invalid(Host.ENVIRONMENT_REQUIRED);
                }
            }
            var id = new BodyId(receipt.getCommand().getBodyId());
            var stored = bodies.get(id);
            if (stored == null || receipt.getCommitted().getRevision() > stored.snapshot().revision()) {
                throw MeridianErrors.invalid(Host.RECEIPT_WITHOUT_BODY);
            }
            // 构造期间索引尚未对外发布，逐条校验后直接加入，避免反复复制全部历史。
            if (stored.receipts().putIfAbsent(key(receipt), receipt) != null) {
                throw MeridianErrors.invalid(Host.DUPLICATE_RECEIPT);
            }
        }
    }

    @Override
    public synchronized Optional<BodySnapshot> find(BodyId bodyId) {
        // 查询只观察已提交状态；同一监视器保证不会读到暂存的半份事务。
        var stored = bodies.get(MeridianErrors.required(bodyId, Host.BODY_ID_REQUIRED));
        return stored == null ? Optional.empty() : Optional.of(stored.snapshot());
    }

    public synchronized QiInventory environmentQi() {
        // 普通时间命令的默认释放账户；摄入事务的损耗直接进入选定来源，不重复入这里。
        return environmentQi;
    }

    public synchronized List<CommandReceipt> receipts() {
        // 宿主保存身体时同时取得已提交回执；不可变消息不会暴露可写事务或去重索引。
        return bodies.values().stream().flatMap(body -> body.receipts().values().stream()).toList();
    }

    public synchronized void registerBody(BodySnapshot initial) {
        // 可信宿主只登记首次出生的身体；既有身份不能借重新登录覆盖资源与回执。
        if (transactionOpen) {
            throw MeridianErrors.inconsistent(Host.NESTED_TRANSACTION);
        }
        BodyProtobuf.toMessage(initial);
        if (bodies.putIfAbsent(initial.id(), new StoredBody(initial, new HashMap<>())) != null) {
            throw MeridianErrors.invalid(Host.DUPLICATE_BODY);
        }
    }

    public synchronized void registerEnvironmentSource(EnvironmentQiSource initial) {
        // 新区域与所有身体共用事务锁，重复登记明确拒绝，不能借加载区块补满库存。
        if (transactionOpen) {
            throw MeridianErrors.inconsistent(Host.NESTED_TRANSACTION);
        }
        EnvironmentProtobuf.validateSource(initial);
        if (sources.putIfAbsent(initial.getSpace().getSourceId(), initial) != null) {
            throw MeridianErrors.invalid(Host.DUPLICATE_SOURCE);
        }
    }

    public synchronized List<EnvironmentQiSource> environmentSources() {
        // 保存当前库存、版本和共享预算；返回不可变列表，不暴露可写来源表。
        return List.copyOf(sources.values());
    }

    public synchronized Optional<EnvironmentQiSource> environmentSource(String sourceId) {
        // 只返回完整已提交来源，用于可信宿主保存或读取浓度。
        return Optional.ofNullable(sources.get(sourceId));
    }

    public synchronized void renewEnvironmentWindow(String sourceId, long expectedRevision,
                                                     TimeCursor from, TimeCursor until, EnvironmentQiRules rules) {
        // 可信宿主在旧窗口结算完成后显式续窗；同一锁隔离其他身体摄入和库存读取。
        if (transactionOpen) {
            throw MeridianErrors.inconsistent(Host.NESTED_TRANSACTION);
        }
        EnvironmentQiSource source = sources.get(sourceId);
        if (source == null) {
            throw MeridianErrors.rejectIntake(FailureCode.ENVIRONMENT_UNAVAILABLE);
        }
        if (source.getRevision() != expectedRevision) {
            throw MeridianErrors.rejectIntake(FailureCode.ENVIRONMENT_REVISION_CONFLICT);
        }
        EnvironmentQiSource renewed = EnvironmentAbsorption.renewWindow(source, from, until, rules);
        sources.put(sourceId, renewed);
    }

    @Override
    public synchronized <T> T transact(BodyId bodyId, Function<Transaction, T> work) {
        // 参考实现用一个锁隔离身体和共享来源；正式宿主需联合锁定两者或使用数据库事务。
        MeridianErrors.required(bodyId, Host.BODY_ID_REQUIRED);
        MeridianErrors.required(work, Host.TRANSACTION_WORK_REQUIRED);
        if (transactionOpen) {
            throw MeridianErrors.inconsistent(Host.NESTED_TRANSACTION);
        }
        transactionOpen = true;
        var transaction = new MemoryTransaction(bodyId, bodies.get(bodyId), environmentQi, sources);
        try {
            T result = work.apply(transaction);
            // 所有可能失败的计算在发布之前完成；同一锁内发布身体、回执和环境余额。
            if (transaction.staged != null) {
                // 仅在回调成功后追加新回执；暂存阶段不修改索引，失败仍完整回滚。
                transaction.staged.receipts().put(key(transaction.stagedReceipt), transaction.stagedReceipt);
                bodies.put(bodyId, transaction.staged);
                environmentQi = transaction.stagedEnvironment;
                if (transaction.stagedSource != null) {
                    sources.put(transaction.stagedSource.getSpace().getSourceId(), transaction.stagedSource);
                }
            }
            return result;
        } finally {
            // 无论回调是否成功，都关闭事务；抛异常时尚未发布的状态自然丢弃。
            transaction.active = false;
            transactionOpen = false;
        }
    }

    private static ReceiptKey key(CommandReceipt receipt) {
        // 身体 ID 由外层索引隔离；调用者从可信认证上下文取得。
        return new ReceiptKey(receipt.getCallerId(), receipt.getRequestId());
    }

    private record ReceiptKey(String callerId, String requestId) { }

    // 回执索引只由 Store 持有；构造完成后所有读写都必须在 Store 的监视器内。
    private record StoredBody(BodySnapshot snapshot, Map<ReceiptKey, CommandReceipt> receipts) { }

    private static final class MemoryTransaction implements Transaction {
        private final BodyId bodyId;
        private final StoredBody original;
        private final Thread owner = Thread.currentThread();
        private boolean active = true;
        private StoredBody staged;
        private CommandReceipt stagedReceipt;
        private QiInventory stagedEnvironment;
        private final Map<String, EnvironmentQiSource> sources;
        private EnvironmentQiSource stagedSource;

        private MemoryTransaction(BodyId bodyId, StoredBody original, QiInventory environmentQi,
                                  Map<String, EnvironmentQiSource> sources) {
            // 暂存区独立于已提交状态，回调失败时无需执行反向补偿。
            this.bodyId = bodyId;
            this.original = original;
            this.stagedEnvironment = environmentQi;
            // 整个回调期间共用存储锁，来源索引不能被续窗或其他身体事务改写。
            this.sources = sources;
        }

        @Override
        public Optional<BodySnapshot> snapshot() {
            // 回调内始终返回事务开始时的快照，暂存不能变成第二次命令的输入。
            requireActive();
            return original == null ? Optional.empty() : Optional.of(original.snapshot());
        }

        @Override
        public Optional<CommandReceipt> receipt(String callerId, String requestId) {
            // 不允许按请求 ID 单独查找，避免不同操作者之间串用成功记录。
            requireActive();
            return original == null ? Optional.empty()
                    : Optional.ofNullable(original.receipts().get(new ReceiptKey(callerId, requestId)));
        }

        @Override
        public Optional<EnvironmentQiSource> environmentSource(String sourceId) {
            // 保持与身体读取相同的事务生命周期，禁止把来源事务对象泄露给异步任务。
            requireActive();
            return Optional.ofNullable(sources.get(sourceId));
        }

        @Override
        public void stage(BodySnapshot next, CommandReceipt receipt) {
            // 即使宿主进程内误用，也不能创建不存在的身体或在一笔事务中多次写入。
            requireActive();
            if (original == null || staged != null) {
                throw MeridianErrors.inconsistent(Host.INVALID_STAGE);
            }
            BodyProtobuf.toMessage(next);
            MeridianProtocol.validateReceipt(receipt);
            if (!next.id().equals(bodyId) || !receipt.getCommand().getBodyId().equals(bodyId.value())
                    || receipt.getCommand().getExpectedRevision() != original.snapshot().revision()
                    || receipt.getCommitted().getRevision() != next.revision()) {
                throw MeridianErrors.inconsistent(Host.STAGE_MISMATCH);
            }
            var nextEnvironment = stagedEnvironment;
            if (receipt.getCommand().hasExecuteActivity()) {
                // 按固定计划重新计算，身体、进度和排放只允许一起发布。
                var plan = receipt.getCommand().getExecuteActivity();
                var expected = BodyActivityExecution.apply(original.snapshot(), plan);
                if (!next.equals(expected)) {
                    throw MeridianErrors.inconsistent(MeridianErrors.INVALID_ACTIVITY);
                }
                nextEnvironment = QiAccounting.credit(nextEnvironment, BodyProtobuf.fromInventory(plan.getReleasedQi()));
            }
            if (receipt.getCommand().hasSetAddonProgress()) {
                // 重算原子替换，拒绝借进度入口悄悄改写真元、寿命或伤势。
                var update = receipt.getCommand().getSetAddonProgress();
                var expected = dev.kizuna.meridian.core.AddonProgress.replace(original.snapshot(),
                        update.getId(), update.getProgress());
                if (!next.equals(expected)) {
                    throw MeridianErrors.inconsistent(MeridianErrors.ADDON_PROGRESS_STAGE);
                }
            }
            EnvironmentQiSource nextSource = null;
            if (receipt.hasHealth()) {
                // 使用已固定的附属处理结果复算，不再次调用可能已卸载或升级的伤害实现。
                var fact = receipt.getHealth();
                var expected = BodyHealthExecution.calculate(original.snapshot(), receipt.getCommand(),
                        fact.hasDamageResolution() ? fact.getDamageResolution() : null);
                if (!next.equals(expected.body()) || !fact.equals(expected.fact())) {
                    throw MeridianErrors.inconsistent(MeridianErrors.Health.STAGE);
                }
                QiInventory released = BodyProtobuf.fromInventory(expected.fact().getReleasedQi());
                nextEnvironment = QiAccounting.credit(nextEnvironment, released);
            }
            if (receipt.hasOrganIntake()) {
                // 从锁定的旧来源和旧身体复算，不能只检查总数相等便信任任意入账。
                var fact = receipt.getOrganIntake();
                String sourceId = fact.getEnvironment().getExchange().getOffer().getSourceId();
                EnvironmentQiSource source = sources.get(sourceId);
                if (source == null) {
                    throw MeridianErrors.inconsistent(Host.INTAKE_STAGE_MISMATCH);
                }
                var expected = OrganIntakeExecution.calculate(original.snapshot(),
                        receipt.getCommand().getIntakeQi(), fact.getPlan(), source);
                if (!next.equals(expected.body()) || !fact.equals(expected.fact())) {
                    throw MeridianErrors.inconsistent(Host.INTAKE_STAGE_MISMATCH);
                }
                nextSource = expected.source();
            }
            if (receipt.hasQiCapture()) {
                // 以锁定前的浓度、空间和原配方复算，不信任调用方传来的最终身体。
                var fact = receipt.getQiCapture();
                String sourceId = fact.getEnvironment().getExchange().getOffer().getSourceId();
                EnvironmentQiSource source = sources.get(sourceId);
                if (source == null) {
                    throw MeridianErrors.inconsistent(Host.INTAKE_STAGE_MISMATCH);
                }
                var expected = QiCaptureExecution.calculate(original.snapshot(), receipt.getCommand().getCaptureQi(),
                        fact.getRecipe(), source);
                if (!next.equals(expected.body()) || !fact.equals(expected.fact())) {
                    throw MeridianErrors.inconsistent(MeridianErrors.QI_CAPTURE_FACT);
                }
                nextSource = expected.source();
            }
            if (receipt.getCommand().hasStartOrganRecipe()) {
                // 对照原始缓存与固定配方复算，避免伪造已扣除的输入或提前发放产物。
                var expected = OrganProcessing.start(original.snapshot(),
                        new Anatomy.OrganId(receipt.getCommand().getStartOrganRecipe().getOrganId()),
                        CapabilityProtobuf.fromRecipe(receipt.getOrganRecipe()));
                if (!next.equals(expected)) {
                    throw MeridianErrors.inconsistent(Host.CAPABILITY_STAGE_MISMATCH);
                }
            }
            if (receipt.getCommand().hasExertTissue()) {
                // 组织做功必须实际增加对应组织疲劳，不能只保存一个成功回执。
                var expected = TissueCapabilities.exert(original.snapshot(),
                        CapabilityProtobuf.fromExertion(receipt.getCommand().getExertTissue()));
                if (!next.equals(expected)) {
                    throw MeridianErrors.inconsistent(Host.CAPABILITY_STAGE_MISMATCH);
                }
            }
            if (receipt.getCommand().hasExertTissues()) {
                // 多组织消耗同样复核整笔结果，恢复或重试不能漏掉其中一份疲劳。
                var loads = receipt.getCommand().getExertTissues().getLoadsList().stream()
                        .map(CapabilityProtobuf::fromExertion).toList();
                if (!next.equals(TissueCapabilities.exertAll(original.snapshot(), loads))) {
                    throw MeridianErrors.inconsistent(Host.CAPABILITY_STAGE_MISMATCH);
                }
            }
            if (receipt.getCommand().hasDebugQi()) {
                // 管理员注入也重算剂量和预约，不能用调试回执包装任意身体改写。
                var expected = dev.kizuna.meridian.core.QiDebugging.apply(original.snapshot(), receipt.getCommand().getDebugQi());
                if (!expected.equals(next)) {
                    throw MeridianErrors.inconsistent(MeridianErrors.DEBUG_QI_MISMATCH);
                }
            }
            if (receipt.getCommand().hasTransportQi()) {
                // 直接使用事务适配也不能伪造运输余额，核验完整源扣除与唯一在途记录。
                var expected = QiTransport.start(original.snapshot(),
                        TransportProtobuf.fromMessage(receipt.getCommand().getTransportQi()));
                if (!next.equals(expected)) {
                    throw MeridianErrors.inconsistent(Host.TRANSPORT_STAGE_MISMATCH);
                }
            }
            if (receipt.getCommand().hasStartAction()) {
                // 原定义来自启动回执，事务检查动作和身体其他字段都符合纯算子结果。
                var expected = BodyActions.start(original.snapshot(), receipt.getCommand().getStartAction().getInstanceId(),
                        ActionProtobuf.fromDefinition(receipt.getActionDefinition()));
                if (!next.equals(expected)) {
                    throw MeridianErrors.inconsistent(Host.CAPABILITY_STAGE_MISMATCH);
                }
            }
            if (receipt.getCommand().hasStopAction()) {
                var expected = BodyActions.stop(original.snapshot(), receipt.getCommand().getStopAction().getInstanceId());
                if (!next.equals(expected)) {
                    throw MeridianErrors.inconsistent(Host.CAPABILITY_STAGE_MISMATCH);
                }
            }
            if (receipt.hasTimeAdvance()) {
                // stage 是最终原子边界，进程内直接调用也必须提交与原身体一致的完整结算。
                var time = receipt.getTimeAdvance();
                var expected = BodyTimeAdvancement.apply(original.snapshot(), TimeProtobuf.fromMessage(time.getPlan()));
                if (!next.equals(expected.body()) || !time.equals(TimeProtobuf.toMessage(time.getPlan(), expected))) {
                    throw MeridianErrors.inconsistent(Host.TIME_STAGE_MISMATCH);
                }
                // 合并溢出等错误发生在发布之前；失败时身体清账、回执和环境入账一起放弃。
                if (!expected.releasedQi().entries().isEmpty()) {
                    // 普通到达和老化没有环境输出，直接复用环境账，避免无关的复制与合并。
                    nextEnvironment = QiAccounting.credit(stagedEnvironment, expected.releasedQi());
                }
            }
            if (receipt.hasOpening()) {
                // 同释放回执一起复算，防止伪造开脉成功、扩容或额外真元。
                var fact = receipt.getOpening();
                BodySnapshot expectedBody;
                QiInventory released = QiInventory.empty();
                if (receipt.getCommand().hasStartMeridianOpening()) {
                    expectedBody = OpeningRuntime.start(original.snapshot(),
                            OpeningProtobuf.fromRule(fact.getRule()),
                            ActionProtobuf.fromDefinition(fact.getAction()));
                } else {
                    var expected = OpeningRuntime.pause(original.snapshot(),
                            new Anatomy.MeridianId(receipt.getCommand().getPauseMeridianOpening().getMeridianId()));
                    expectedBody = expected.body();
                    released = expected.releasedQi();
                    var originalTask = original.snapshot().openingTask();
                    var originalAction = original.snapshot().actions().stream()
                            .filter(item -> item.instanceId().equals(originalTask.actionInstanceId())).findFirst().orElseThrow();
                    if (!fact.getRule().equals(OpeningProtobuf.toRule(originalTask.rule()))
                            || !fact.getAction().equals(ActionProtobuf.toDefinition(originalAction.definition()))) {
                        throw MeridianErrors.inconsistent(MeridianErrors.Opening.RECEIPT);
                    }
                }
                if (!next.equals(expectedBody) || !fact.getReleasedQi().equals(BodyProtobuf.toInventory(released))) {
                    throw MeridianErrors.inconsistent(MeridianErrors.Opening.RECEIPT);
                }
                nextEnvironment = QiAccounting.credit(nextEnvironment, released);
            }
            if (original.receipts().containsKey(key(receipt))) {
                throw MeridianErrors.inconsistent(Host.RECEIPT_OVERWRITE);
            }
            // 保存新增事实，等事务回调成功后再更新索引，不为每笔命令复制历史回执。
            staged = new StoredBody(next, original.receipts());
            stagedReceipt = receipt;
            stagedEnvironment = nextEnvironment;
            stagedSource = nextSource;
        }

        private void requireActive() {
            // synchronized 可重入，但事务不能逃逸到回调外或另一线程继续使用。
            if (!active || Thread.currentThread() != owner) {
                throw MeridianErrors.inconsistent(Host.INACTIVE_TRANSACTION);
            }
        }
    }
}
