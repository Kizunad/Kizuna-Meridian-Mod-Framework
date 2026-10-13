package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Protocol;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.error.MeridianErrors.TransportRejected;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiExchange;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiOffer;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiReceipt;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiSource;

/** 环境交换的有界编解码与事实校验，供权威身体实现和宿主交换 Protobuf 消息。 */
public final class EnvironmentProtobuf {
    private EnvironmentProtobuf() {
        // 校验只读取消息；来源授权、账本锁定和原子提交由宿主执行。
    }

    public static byte[] encodeSource(EnvironmentQiSource source) {
        // 库存与已消耗的共享带宽必须一起保存，恢复不能重新发放额度。
        validateSource(source);
        return ProtoWire.write(source);
    }

    public static EnvironmentQiSource decodeSource(byte[] payload) {
        // 恢复只还原状态，不重算初始库存或清空用量。
        EnvironmentQiSource source = ProtoWire.read(payload, EnvironmentQiSource.parser());
        validateSource(source);
        return source;
    }

    public static void validateSource(EnvironmentQiSource source) {
        // 窗口有共同时间基准，库存只有这一份；分段表示带宽占用而非真元余额。
        ProtoWire.validate(source);
        EnvironmentTopologyProtobuf.validateSpace(source.getSpace());
        BodyProtobuf.fromInventory(source.getInventory());
        TimeCursor start = BodyProtobuf.fromTime(source.getValidFrom());
        TimeCursor end = BodyProtobuf.fromTime(source.getValidUntil());
        requireSameClock(start, end);
        ProtoWire.require(start.elapsedMillis() < end.elapsedMillis(), Environment.SOURCE_STATE);
        long previousEnd = start.elapsedMillis();
        for (var usage : source.getUsageList()) {
            // 排序且不重叠，允许中间空闲；合计速率不能超过该窗口固定的供给能力。
            ProtoWire.require(usage.getStartedMillis() >= previousEnd
                    && usage.getEndedMillis() > usage.getStartedMillis()
                    && usage.getEndedMillis() <= end.elapsedMillis()
                    && usage.getQiPerSecond() > 0
                    && usage.getQiPerSecond() <= source.getMaxAbsorptionPerSecond(), Environment.SOURCE_STATE);
            previousEnd = usage.getEndedMillis();
        }
    }

    public static byte[] encodeOffer(EnvironmentQiOffer offer) {
        // 宿主发出的供给事实也要满足对端的版本和时间约束。
        validateOffer(offer);
        return ProtoWire.write(offer);
    }

    public static EnvironmentQiOffer decodeOffer(byte[] payload) {
        // 先限制载荷，再验证供给内容；不能把解析成功视为已获吸收许可。
        EnvironmentQiOffer offer = ProtoWire.read(payload, EnvironmentQiOffer.parser());
        validateOffer(offer);
        return offer;
    }

    public static byte[] encodeExchange(EnvironmentQiExchange exchange) {
        // 候选交换必须满足逐类额度和速率约束，才能交给宿主进行事务校验。
        validateExchange(exchange);
        return ProtoWire.write(exchange);
    }

    public static EnvironmentQiExchange decodeExchange(byte[] payload) {
        // 使用与本地构造相同的检查，避免跨语言接入绕过预算和时钟约束。
        EnvironmentQiExchange exchange = ProtoWire.read(payload, EnvironmentQiExchange.parser());
        validateExchange(exchange);
        return exchange;
    }

    public static byte[] encodeReceipt(EnvironmentQiReceipt receipt) {
        // 回执保存原交换与两个提交版本，恢复时能够识别已经执行的转账。
        validateReceipt(receipt);
        return ProtoWire.write(receipt);
    }

    public static EnvironmentQiReceipt decodeReceipt(byte[] payload) {
        // 回执解码仅恢复事实，不执行任何环境扣款或身体入账。
        EnvironmentQiReceipt receipt = ProtoWire.read(payload, EnvironmentQiReceipt.parser());
        validateReceipt(receipt);
        return receipt;
    }

    public static void validateOffer(EnvironmentQiOffer offer) {
        // 通用边界保证字段存在、数值有限非负以及消息规模；账户继续检查明细唯一性。
        ProtoWire.validate(offer);
        ProtoWire.require(offer.getProtocolVersion() == MeridianProtocol.VERSION, Protocol.ENVIRONMENT_VERSION);
        BodyProtobuf.fromInventory(offer.getAvailableQi());

        // 没有共同时间基准的供给不能用于积分，零时长也不能代表无限期有效。
        TimeCursor validFrom = BodyProtobuf.fromTime(offer.getValidFrom());
        TimeCursor validUntil = BodyProtobuf.fromTime(offer.getValidUntil());
        requireSameClock(validFrom, validUntil);
        ProtoWire.require(validFrom.elapsedMillis() < validUntil.elapsedMillis(), Protocol.ENVIRONMENT_INTERVAL);
    }

    public static void validateExchange(EnvironmentQiExchange exchange) {
        // 先读取完整供给与实际毛额；空明细合法，但一笔交换至少有一个方向发生转移。
        ProtoWire.validate(exchange);
        EnvironmentQiOffer offer = exchange.getOffer();
        validateOffer(offer);
        QiInventory availableQi = BodyProtobuf.fromInventory(offer.getAvailableQi());
        QiInventory absorbedQi = BodyProtobuf.fromInventory(exchange.getAbsorbedQi());
        QiInventory releasedQi = BodyProtobuf.fromInventory(exchange.getReleasedQi());
        ProtoWire.require(!absorbedQi.entries().isEmpty() || !releasedQi.entries().isEmpty(),
                Protocol.ENVIRONMENT_EMPTY_EXCHANGE);

        // 提交会同时递增身体和来源版本，不能让候选交换产生无法表达的成功回执。
        ProtoWire.require(exchange.getExpectedBodyRevision() < Long.MAX_VALUE
                && offer.getSourceRevision() < Long.MAX_VALUE, Protocol.COMMIT_REVISION);

        // 吸收覆盖一个稳定区间；瞬时释放也必须落在来源选择仍有效的时刻。
        TimeCursor validFrom = BodyProtobuf.fromTime(offer.getValidFrom());
        TimeCursor validUntil = BodyProtobuf.fromTime(offer.getValidUntil());
        TimeCursor startedAt = BodyProtobuf.fromTime(exchange.getStartedAt());
        TimeCursor endedAt = BodyProtobuf.fromTime(exchange.getEndedAt());
        requireSameClock(validFrom, startedAt);
        requireSameClock(validFrom, endedAt);
        ProtoWire.require(startedAt.elapsedMillis() >= validFrom.elapsedMillis()
                && startedAt.elapsedMillis() < validUntil.elapsedMillis(), Protocol.ENVIRONMENT_INTERVAL);
        ProtoWire.require(endedAt.elapsedMillis() >= startedAt.elapsedMillis()
                && endedAt.elapsedMillis() <= validUntil.elapsedMillis(), Protocol.ENVIRONMENT_INTERVAL);

        // 原始吸收逐类扣额度；释放不能替吸收垫资，也不能用别的相态或属性抵扣。
        try {
            QiAccounting.debit(availableQi, absorbedQi);
        } catch (TransportRejected exception) {
            throw MeridianErrors.invalid(Protocol.ENVIRONMENT_ABSORPTION, exception);
        }

        if (!absorbedQi.entries().isEmpty()) {
            // 先用整数毫秒求差，再换算模拟秒；生物老化倍率不改变环境供给速度。
            long durationMillis = endedAt.elapsedMillis() - startedAt.elapsedMillis();
            double durationSeconds = durationMillis / 1000.0;
            double maximumAbsorbedQi = offer.getMaxAbsorptionPerSecond() * durationSeconds;
            ProtoWire.require(Double.isFinite(maximumAbsorbedQi), Protocol.ENVIRONMENT_RATE_OVERFLOW);
            ProtoWire.require(absorbedQi.totalQi() <= maximumAbsorbedQi, Protocol.ENVIRONMENT_RATE_LIMIT);
        }
    }

    public static void validateReceipt(EnvironmentQiReceipt receipt) {
        // 只有两个账户版本各前进一次才是本次交换的提交结果，缺失或跳号都拒绝。
        ProtoWire.validate(receipt);
        EnvironmentQiExchange exchange = receipt.getExchange();
        validateExchange(exchange);
        ProtoWire.require(receipt.getBodyRevision() == exchange.getExpectedBodyRevision() + 1
                && receipt.getSourceRevision() == exchange.getOffer().getSourceRevision() + 1,
                Protocol.COMMIT_REVISION);
    }

    private static void requireSameClock(TimeCursor reference, TimeCursor candidate) {
        // 时钟重建后，即使毫秒数接近也不能继续使用旧供给窗口。
        ProtoWire.require(reference.domain().equals(candidate.domain())
                && reference.epoch().equals(candidate.epoch()), Protocol.ENVIRONMENT_CLOCK);
    }
}
