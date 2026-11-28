package dev.kizuna.meridian.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.LumenExport;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.LumenShare;
import dev.kizuna.meridian.protocol.pb.BodyMessages;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

/** 可信宿主间的内腔交接。导出的库存按来源保留，身体死亡只释放仍在身体内的账户。 */
public final class BodyExports {
    public static final String PREFIX = AddonProgress.EXPORT_PREFIX;
    private static final double TRANSFER_TOLERANCE = 1e-8;

    private BodyExports() {
        // 库存和凭据均由宿主持有的身体存档承载。
    }

    public static Optional<LumenExport> find(BodySnapshot body, UUID operation) {
        // 读身体长期凭据，旧命令回执裁剪不影响同号重试。
        Any payload = body.addonProgress().get(PREFIX + operation);
        if (payload == null) {
            return Optional.empty();
        }
        try {
            LumenExport record = payload.unpack(LumenExport.class);
            validate(record);
            if (!record.getOperationId().equals(operation.toString()) || !record.getDonor().equals(body.id().value())) {
                throw invalid();
            }
            return Optional.of(record);
        } catch (InvalidProtocolBufferException malformed) {
            throw invalid();
        }
    }

    public static Optional<LumenExport> quote(BodySnapshot donor, LumenExport destination, double volume) {
        // 只从完好内腔逐项取货。运输中的货已经离开余额，开拓正占用的经脉也留给原任务。
        if (donor.vitality().dead() || !Double.isFinite(volume) || volume <= 0) {
            return Optional.empty();
        }
        var offer = destination.toBuilder()
                .setDonor(donor.id().value())
                .setDonorRevision(donor.revision())
                .clearShares();
        double remaining = volume;
        for (var state : donor.meridianStates()) {
            if (!available(donor, state)) {
                continue;
            }
            QiInventory part = state.lumenQi().take(Double.MAX_VALUE, remaining);
            if (!part.entries().isEmpty()) {
                var cargo = BodyProtobuf.toInventory(part);
                var share = LumenShare.newBuilder()
                        .setSource(state.id().value())
                        .setDebited(cargo)
                        .setPending(cargo);
                offer.addShares(share);
                remaining -= part.volume();
            }
        }
        if (offer.getSharesCount() == 0) {
            return Optional.empty();
        }
        LumenExport result = offer.build();
        validate(result);
        return Optional.of(result);
    }

    private static boolean available(BodySnapshot body, MeridianState state) {
        // 活动开拓可能从多条参与脉引导真元，全部参与者都受保护。
        var task = body.openingTask();
        return state.condition() == MeridianState.Condition.INTACT
                && state.opening().stage() == MeridianOpening.Stage.OPENED
                && (task == null || !task.active()
                || task.participants().stream().noneMatch(part -> part.meridianId().equals(state.id())));
    }

    public static LumenExport allocate(LumenExport funded, double freeVolume) {
        // 依原顺序分配实收和退款，拆分保留每种真元的原密度，避免混合后无法按原路退款。
        validate(funded);
        if (!Double.isFinite(freeVolume) || freeVolume < 0 || !pending(funded)) {
            throw invalid();
        }
        var result = funded.toBuilder().clearShares();
        double remaining = freeVolume;
        for (var share : funded.getSharesList()) {
            var original = BodyProtobuf.fromInventory(share.getDebited());
            QiInventory accepted = original;
            if (remaining < original.volume()) {
                accepted = original.take(Double.MAX_VALUE, remaining);
            }
            QiInventory refund = QiAccounting.debit(original, accepted);
            result.addShares(share.toBuilder()
                    .clearPending()
                    .setCredited(BodyProtobuf.toInventory(accepted))
                    .setReturned(BodyProtobuf.toInventory(refund)));
            remaining = Math.max(0, remaining - accepted.volume());
        }
        var settlement = result.build();
        validate(settlement);
        return settlement;
    }

    public static QiInventory credited(LumenExport settlement) {
        // 各来源分账先分别核算，只有进入同一受方内腔时才合并密度。
        return QiInventory.combine(settlement.getSharesList().stream()
                .map(share -> BodyProtobuf.fromInventory(share.getCredited())).toList());
    }

    public static QiInventory credit(QiInventory account, QiInventory cargo) {
        // 外来真元和原路退款都用账户实增复核分摊；浮点尾差按本笔货量衡量。
        var balance = QiAccounting.credit(account, cargo);
        checkDelta(account, balance, cargo);
        return balance;
    }

    private static void checkDelta(QiInventory lower, QiInventory higher, QiInventory cargo) {
        // 大账户的一个浮点间隔可能大于整笔货物。仅比较总账或只检查余额有变化会误认足额。
        for (var part : cargo.entries()) {
            double delta = amount(higher, part) - amount(lower, part);
            if (Math.abs(delta - part.amount()) > TRANSFER_TOLERANCE * part.amount()) {
                throw invalid();
            }
        }
    }

    private static double amount(QiInventory inventory, Qi kind) {
        // 同相态同属性在一个账户中至多有一行。
        return inventory.entries().stream()
                .filter(entry -> entry.phase() == kind.phase() && entry.attribute().equals(kind.attribute()))
                .mapToDouble(Qi::amount).findFirst().orElse(0);
    }

    public static boolean pending(LumenExport record) {
        // 同一凭据的各行必须同时在途或同时结案。
        return record.getShares(0).getPending().getEntriesCount() > 0;
    }

    public static void validate(LumenExport record) {
        // 双方与目标不可换号；每个来源只出现一次，并逐种验证实扣等于在途、实收、退回之和。
        UUID operation = UUID.fromString(record.getOperationId());
        if (!operation.toString().equals(record.getOperationId()) || record.getDonor().isBlank()
                || record.getRecipient().isBlank() || record.getDonor().equals(record.getRecipient())
                || record.getTarget().isBlank() || record.getSharesCount() == 0
                || record.getRecipientRevision() < 0 || record.getDonorRevision() < 0) {
            throw invalid();
        }
        var sources = new HashSet<String>();
        boolean inTransit = pending(record);
        for (var share : record.getSharesList()) {
            if (share.getSource().isBlank() || !sources.add(share.getSource())
                    || share.getDebited().getEntriesCount() == 0
                    || inTransit != (share.getPending().getEntriesCount() > 0)) {
                throw invalid();
            }
            var debit = BodyProtobuf.fromInventory(share.getDebited());
            if (inTransit) {
                if (!share.getPending().equals(share.getDebited()) || share.getCredited().getEntriesCount() != 0
                        || share.getReturned().getEntriesCount() != 0) {
                    throw invalid();
                }
            } else {
                var received = BodyProtobuf.fromInventory(share.getCredited());
                var remainder = QiAccounting.debit(debit, received);
                if (!remainder.equals(BodyProtobuf.fromInventory(share.getReturned()))) {
                    throw invalid();
                }
            }
        }
    }

    public static boolean sameFunding(LumenExport left, LumenExport right) {
        // 比较身份、两个报价版本、目标和各来源实扣，回款阶段不属于原约定。
        return principal(left).equals(principal(right));
    }

    private static LumenExport principal(LumenExport record) {
        // 保留原始实扣，移除后续阶段的三个余额。
        var principal = record.toBuilder();
        for (var share : principal.getSharesBuilderList()) {
            share.clearPending();
            share.clearCredited();
            share.clearReturned();
        }
        return principal.build();
    }

    static BodySnapshot apply(BodySnapshot body, LumenExport record) {
        // 重放先查长期凭据；已结案后重发原导出也只能取回旧结果。
        validate(record);
        var operation = UUID.fromString(record.getOperationId());
        var previous = find(body, operation);
        if (!record.getDonor().equals(body.id().value())) {
            throw invalid();
        }
        if (previous.isPresent()) {
            var saved = previous.orElseThrow();
            if (!sameFunding(saved, record)) {
                throw invalid();
            }
            if (pending(record) || saved.equals(record)) {
                return body;
            }
            if (!pending(saved)) {
                throw invalid();
            }
        } else if (!pending(record) || record.getDonorRevision() != body.revision() || body.vitality().dead()) {
            throw invalid();
        }
        // 先在候选中完成所有扣退和容量校验，再把凭据与同一身体版本一起发布。
        var next = BodyProtobuf.toMessage(body).toBuilder();
        for (var share : record.getSharesList()) {
            changeLumen(body, next, share);
        }
        next.putAddonProgress(PREFIX + operation, Any.pack(record));
        next.setRevision(Math.incrementExact(body.revision()));
        return BodyProtobuf.fromMessage(next.build());
    }

    private static void changeLumen(BodySnapshot body, BodyMessages.BodySnapshot.Builder next,
                                    LumenShare share) {
        // 直接核对当前可扣库存；退款仅能回原账户，已经被占用的空间须等到腾出后重试。
        for (int index = 0; index < body.meridianStates().size(); index++) {
            var state = body.meridianStates().get(index);
            if (!state.id().value().equals(share.getSource())) {
                continue;
            }
            QiInventory balance;
            if (share.getPending().getEntriesCount() > 0) {
                if (!available(body, state)) {
                    throw invalid();
                }
                var cargo = BodyProtobuf.fromInventory(share.getDebited());
                QiAccounting.validateTransferable(cargo);
                balance = QiAccounting.debit(state.lumenQi(), cargo);
                checkDelta(balance, state.lumenQi(), cargo);
            } else {
                var refund = BodyProtobuf.fromInventory(share.getReturned());
                if (!refund.entries().isEmpty() && (body.vitality().dead()
                        || refund.volume() > freeVolume(body, state.id(), 0))) {
                    throw invalid();
                }
                balance = credit(state.lumenQi(), refund);
            }
            next.getMeridianStatesBuilder(index).setLumenQi(BodyProtobuf.toInventory(balance));
            return;
        }
        throw invalid();
    }

    public static double freeVolume(BodySnapshot body, Anatomy.MeridianId id, double gain) {
        // 实存与运往此内腔的预约都占用目标容量，报价只额外计入已经选定的扩容量。
        var definition = body.structure().meridians().stream().filter(item -> item.id().equals(id))
                .findFirst().orElseThrow();
        var state = body.meridianStates().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        double occupied = state.lumenQi().volume();
        for (var transit : body.transits()) {
            var connection = CircuitTransport.connection(body.structure(), transit.connectionId().value());
            if (definition.ports().stream().anyMatch(port -> port.id().equals(connection.to()))) {
                occupied += transit.cargo().volume();
            }
        }
        return Math.max(0, definition.lumenVolume() + gain - occupied);
    }

    private static IllegalArgumentException invalid() {
        // 跨宿主统一报告导出账目或绑定无效，原始身体保持原样。
        return MeridianErrors.invalid(MeridianErrors.Host.INVALID_EXPORT);
    }
}
