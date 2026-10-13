package dev.kizuna.meridian.environment;

import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.EnvironmentProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TimeCursor;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** 环境真实扣账与共享供给的纯计算；调用者须与身体变更一起原子发布。 */
public final class EnvironmentAbsorption {
    private EnvironmentAbsorption() {
        // 不保存另一份环境状态，所有候选结果由宿主事务持有。
    }

    public static EnvironmentQiSource initialize(EnvironmentSpace space, QiInventory inventory,
                                                 TimeCursor from, TimeCursor until, EnvironmentQiRules rules) {
        // 只用于来源首次创建；既有来源通过续窗保留库存、版本和已使用时间。
        double rate = rules.absorptionLimitPerSecond(inventory, space.getVolumeBlocks());
        var source = EnvironmentQiSource.newBuilder().setSpace(space).setRevision(0)
                .setInventory(BodyProtobuf.toInventory(inventory)).setValidFrom(from).setValidUntil(until)
                .setMaxAbsorptionPerSecond(rate).build();
        EnvironmentProtobuf.validateSource(source);
        return source;
    }

    public static EnvironmentQiSource renewWindow(EnvironmentQiSource source, TimeCursor from,
                                                  TimeCursor until, EnvironmentQiRules rules) {
        // 旧窗口只能向前关闭；延长同一窗口并清空用量会让历史带宽被再次消费。
        EnvironmentProtobuf.validateSource(source);
        TimeCursor previousEnd = source.getValidUntil();
        if (!sameClock(previousEnd, from) || from.getElapsedMillis() < previousEnd.getElapsedMillis()) {
            throw MeridianErrors.rejectIntake(FailureCode.ENVIRONMENT_INTERVAL_INVALID);
        }
        if (source.getRevision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectIntake(FailureCode.REVISION_EXHAUSTED);
        }
        QiInventory inventory = BodyProtobuf.fromInventory(source.getInventory());
        double rate = rules.absorptionLimitPerSecond(inventory, source.getSpace().getVolumeBlocks());
        var renewed = source.toBuilder().setRevision(source.getRevision() + 1)
                .setValidFrom(from).setValidUntil(until).setMaxAbsorptionPerSecond(rate).clearUsage().build();
        EnvironmentProtobuf.validateSource(renewed);
        return renewed;
    }

    public static EnvironmentQiOffer offer(EnvironmentQiSource source, String bodyId,
                                           TimeCursor from, TimeCursor until) {
        // 来源由宿主选择；窗口内速率固定，逐类可用量始终读取当前实际库存。
        EnvironmentProtobuf.validateSource(source);
        if (!sameClock(source.getValidFrom(), from) || !sameClock(from, until)
                || from.getElapsedMillis() < source.getValidFrom().getElapsedMillis()
                || from.getElapsedMillis() >= source.getValidUntil().getElapsedMillis()
                || until.getElapsedMillis() < from.getElapsedMillis()
                || until.getElapsedMillis() > source.getValidUntil().getElapsedMillis()) {
            throw MeridianErrors.rejectIntake(FailureCode.ENVIRONMENT_INTERVAL_INVALID);
        }
        var offered = EnvironmentQiOffer.newBuilder().setProtocolVersion(1).setSourceId(source.getSpace().getSourceId())
                .setSourceRevision(source.getRevision()).setBodyId(bodyId).setAvailableQi(source.getInventory())
                .setMaxAbsorptionPerSecond(source.getMaxAbsorptionPerSecond())
                .setValidFrom(source.getValidFrom()).setValidUntil(source.getValidUntil()).build();
        EnvironmentProtobuf.validateOffer(offered);
        return offered;
    }

    public static EnvironmentQiSource apply(EnvironmentQiSource source, EnvironmentQiExchange exchange) {
        // 版本和事实先核对，再动临时账户；旧 offer 不能借新库存继续使用。
        EnvironmentProtobuf.validateSource(source);
        EnvironmentQiOffer offered = exchange.getOffer();
        if (!source.getSpace().getSourceId().equals(offered.getSourceId())
                || source.getRevision() != offered.getSourceRevision()) {
            throw MeridianErrors.rejectIntake(FailureCode.ENVIRONMENT_REVISION_CONFLICT);
        }
        if (source.getRevision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectIntake(FailureCode.REVISION_EXHAUSTED);
        }
        EnvironmentQiOffer expected = offer(source, offered.getBodyId(), exchange.getStartedAt(), exchange.getEndedAt());
        if (!expected.equals(offered)) {
            throw MeridianErrors.inconsistent(MeridianErrors.Environment.INTAKE_FACT);
        }

        // 原始吸收先扣真实库存；同笔加工释放不能为这次摄入垫资。
        QiInventory absorbed = BodyProtobuf.fromInventory(exchange.getAbsorbedQi());
        QiInventory remaining = QiAccounting.debit(BodyProtobuf.fromInventory(source.getInventory()), absorbed);
        long durationMillis = exchange.getEndedAt().getElapsedMillis() - exchange.getStartedAt().getElapsedMillis();
        double requestedRate = absorbed.totalQi() / (durationMillis / 1000.0);
        if (!Double.isFinite(requestedRate) || requestedRate <= 0) {
            throw MeridianErrors.rejectIntake(FailureCode.NUMERIC_LIMIT);
        }
        double intervalBudget = source.getMaxAbsorptionPerSecond() * (durationMillis / 1000.0);
        if (!Double.isFinite(intervalBudget)) {
            throw MeridianErrors.rejectIntake(FailureCode.NUMERIC_LIMIT);
        }
        if (absorbed.totalQi() > intervalBudget) {
            throw MeridianErrors.rejectIntake(FailureCode.ENVIRONMENT_BUDGET_EXCEEDED);
        }
        EnvironmentProtobuf.validateExchange(exchange);
        List<EnvironmentQiUsage> usage = reserve(source, exchange.getStartedAt().getElapsedMillis(),
                exchange.getEndedAt().getElapsedMillis(), requestedRate);
        QiInventory updated = QiAccounting.credit(remaining, BodyProtobuf.fromInventory(exchange.getReleasedQi()));
        var result = source.toBuilder().setRevision(source.getRevision() + 1)
                .setInventory(BodyProtobuf.toInventory(updated)).clearUsage().addAllUsage(usage).build();
        EnvironmentProtobuf.validateSource(result);
        return result;
    }

    private static List<EnvironmentQiUsage> reserve(EnvironmentQiSource source, long start, long end, double rate) {
        // 按真正的重叠区间合并速率，不能把整窗剩余额度集中用在一瞬间。
        var boundaries = new TreeSet<Long>();
        boundaries.add(start);
        boundaries.add(end);
        for (var usage : source.getUsageList()) {
            boundaries.add(usage.getStartedMillis());
            boundaries.add(usage.getEndedMillis());
        }
        List<Long> times = List.copyOf(boundaries);
        var result = new ArrayList<EnvironmentQiUsage>();
        int usageIndex = 0;
        for (int i = 0; i + 1 < times.size(); i++) {
            long segmentStart = times.get(i);
            long segmentEnd = times.get(i + 1);
            while (usageIndex < source.getUsageCount()
                    && source.getUsage(usageIndex).getEndedMillis() <= segmentStart) {
                usageIndex++;
            }
            double previousRate = 0;
            if (usageIndex < source.getUsageCount()
                    && source.getUsage(usageIndex).getStartedMillis() <= segmentStart) {
                previousRate = source.getUsage(usageIndex).getQiPerSecond();
            }
            double combinedRate = previousRate;
            if (segmentStart >= start && segmentEnd <= end) {
                combinedRate += rate;
                if (!Double.isFinite(combinedRate) || combinedRate <= previousRate) {
                    throw MeridianErrors.rejectIntake(FailureCode.NUMERIC_LIMIT);
                }
            }
            if (combinedRate > source.getMaxAbsorptionPerSecond()) {
                throw MeridianErrors.rejectIntake(FailureCode.ENVIRONMENT_BUDGET_EXCEEDED);
            }
            if (combinedRate == 0) {
                continue;
            }
            // 相邻等速片段合并，长期保存的是占用曲线，不是所有调用历史。
            if (!result.isEmpty()) {
                EnvironmentQiUsage previous = result.get(result.size() - 1);
                if (previous.getEndedMillis() == segmentStart && previous.getQiPerSecond() == combinedRate) {
                    result.set(result.size() - 1, previous.toBuilder().setEndedMillis(segmentEnd).build());
                    continue;
                }
            }
            result.add(EnvironmentQiUsage.newBuilder().setStartedMillis(segmentStart).setEndedMillis(segmentEnd)
                    .setQiPerSecond(combinedRate).build());
            if (result.size() > MeridianProtocol.MAX_LIST_ENTRIES) {
                // 历史预算不能为节省空间直接遗忘；由宿主结清旧窗后续窗。
                throw MeridianErrors.rejectIntake(FailureCode.ENVIRONMENT_BUDGET_EXCEEDED);
            }
        }
        return List.copyOf(result);
    }

    private static boolean sameClock(TimeCursor first, TimeCursor second) {
        // 毫秒只有在同域同世代时才可比较，重建的世界时钟不能消费旧窗口。
        return first.getDomain().equals(second.getDomain()) && first.getEpoch().equals(second.getEpoch());
    }
}
