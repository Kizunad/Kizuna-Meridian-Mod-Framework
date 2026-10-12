package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.QiTransport;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.OrganProcessing;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Host;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.TimeProtobuf;
import dev.kizuna.meridian.protocol.TransportProtobuf;
import dev.kizuna.meridian.protocol.CapabilityProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.CommandReceipt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** 可运行的内存事务参考；不写磁盘，不能替代 Fabric/Bong 持久化适配。 */
public final class InMemoryBodyStore implements BodyTransactionStore, BodySnapshotReader {
    private final Map<BodyId, StoredBody> bodies = new HashMap<>();
    private QiInventory environmentQi;
    private boolean transactionOpen;

    public InMemoryBodyStore(List<BodySnapshot> snapshots, List<CommandReceipt> receipts) {
        // 无环境历史时可从空账启动；已有死亡释放或加工损耗必须一起恢复环境余额。
        this(snapshots, receipts, QiInventory.empty());
        if (receipts.stream().anyMatch(receipt -> receipt.hasTimeAdvance()
                && receipt.getTimeAdvance().getReleasedQi().getEntriesCount() > 0)) {
            throw MeridianErrors.invalid(Host.ENVIRONMENT_REQUIRED);
        }
    }

    public InMemoryBodyStore(List<BodySnapshot> snapshots, List<CommandReceipt> receipts, QiInventory environmentQi) {
        // 环境账恢复已保存值，绝不能遍历历史回执再次发放真元。
        this.environmentQi = MeridianErrors.required(environmentQi, Host.ENVIRONMENT_REQUIRED);
        // 恢复时显式提供身体与成功回执，禁止自动补满或用重复身份覆盖旧身体。
        for (var snapshot : List.copyOf(snapshots)) {
            BodyProtobuf.toMessage(snapshot);
            if (bodies.putIfAbsent(snapshot.id(), new StoredBody(snapshot, Map.of())) != null) {
                throw MeridianErrors.invalid(Host.DUPLICATE_BODY);
            }
        }
        for (var receipt : List.copyOf(receipts)) {
            MeridianProtocol.validateReceipt(receipt);
            var id = new BodyId(receipt.getCommand().getBodyId());
            var stored = bodies.get(id);
            if (stored == null || receipt.getCommitted().getRevision() > stored.snapshot().revision()) {
                throw MeridianErrors.invalid(Host.RECEIPT_WITHOUT_BODY);
            }
            var updated = new HashMap<>(stored.receipts());
            if (updated.putIfAbsent(key(receipt), receipt) != null) {
                throw MeridianErrors.invalid(Host.DUPLICATE_RECEIPT);
            }
            bodies.put(id, new StoredBody(stored.snapshot(), Map.copyOf(updated)));
        }
    }

    @Override
    public synchronized Optional<BodySnapshot> find(BodyId bodyId) {
        // 查询只观察已提交状态；同一监视器保证不会读到暂存的半份事务。
        var stored = bodies.get(MeridianErrors.required(bodyId, Host.BODY_ID_REQUIRED));
        return stored == null ? Optional.empty() : Optional.of(stored.snapshot());
    }

    public synchronized QiInventory environmentQi() {
        // 参考实现只有一个环境账户；真实宿主按身体所在世界和位置选择入账目标。
        return environmentQi;
    }

    @Override
    public synchronized <T> T transact(BodyId bodyId, Function<Transaction, T> work) {
        // 参考实现用一个锁换取清晰原子性；正式宿主可按身体分锁或使用数据库事务。
        MeridianErrors.required(bodyId, Host.BODY_ID_REQUIRED);
        MeridianErrors.required(work, Host.TRANSACTION_WORK_REQUIRED);
        if (transactionOpen) {
            throw MeridianErrors.inconsistent(Host.NESTED_TRANSACTION);
        }
        transactionOpen = true;
        var transaction = new MemoryTransaction(bodyId, bodies.get(bodyId), environmentQi);
        try {
            T result = work.apply(transaction);
            // 所有可能失败的计算在发布之前完成；同一锁内发布身体、回执和环境余额。
            if (transaction.staged != null) {
                bodies.put(bodyId, transaction.staged);
                environmentQi = transaction.stagedEnvironment;
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

    private record StoredBody(BodySnapshot snapshot, Map<ReceiptKey, CommandReceipt> receipts) { }

    private static final class MemoryTransaction implements Transaction {
        private final BodyId bodyId;
        private final StoredBody original;
        private final Thread owner = Thread.currentThread();
        private boolean active = true;
        private StoredBody staged;
        private QiInventory stagedEnvironment;

        private MemoryTransaction(BodyId bodyId, StoredBody original, QiInventory environmentQi) {
            // 暂存区独立于已提交状态，回调失败时无需执行反向补偿。
            this.bodyId = bodyId;
            this.original = original;
            this.stagedEnvironment = environmentQi;
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
            if (receipt.getCommand().hasTransportQi()) {
                // 直接使用事务适配也不能伪造运输余额，核验完整源扣除与唯一在途记录。
                var expected = QiTransport.start(original.snapshot(),
                        TransportProtobuf.fromMessage(receipt.getCommand().getTransportQi()));
                if (!next.equals(expected)) {
                    throw MeridianErrors.inconsistent(Host.TRANSPORT_STAGE_MISMATCH);
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
            var updated = new HashMap<>(original.receipts());
            if (updated.putIfAbsent(key(receipt), receipt) != null) {
                throw MeridianErrors.inconsistent(Host.RECEIPT_OVERWRITE);
            }
            staged = new StoredBody(next, Map.copyOf(updated));
            stagedEnvironment = nextEnvironment;
        }

        private void requireActive() {
            // synchronized 可重入，但事务不能逃逸到回调外或另一线程继续使用。
            if (!active || Thread.currentThread() != owner) {
                throw MeridianErrors.inconsistent(Host.INACTIVE_TRANSACTION);
            }
        }
    }
}
