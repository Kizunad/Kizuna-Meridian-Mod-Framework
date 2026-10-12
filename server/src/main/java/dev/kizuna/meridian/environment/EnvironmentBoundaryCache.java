package dev.kizuna.meridian.environment;

import com.google.protobuf.CodedOutputStream;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.error.MeridianErrors.Protocol;
import dev.kizuna.meridian.protocol.EnvironmentTopologyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundaryPatch;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundaryPatchUpdate;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundarySnapshot;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 供宿主事件线程使用的单边界派生缓存；一次构建，两端共享，平时查询不访问方块。 */
public final class EnvironmentBoundaryCache {
    private final String boundaryId;
    private final String firstSourceId;
    private final String secondSourceId;
    private final long rulesRevision;
    private final Map<String, EnvironmentBoundaryPatch> patches = new LinkedHashMap<>();
    private long revision;
    private boolean ready;
    private BigDecimal weightedArea = BigDecimal.ZERO;
    private double weightedAreaBlocks;
    private int serializedSize;

    public EnvironmentBoundaryCache(EnvironmentBoundarySnapshot snapshot) {
        // 宿主从生成定义或存档构建缓存，跨语言消息必须先满足同一套约束。
        EnvironmentTopologyProtobuf.validateBoundary(snapshot);
        boundaryId = snapshot.getBoundaryId();
        firstSourceId = snapshot.getFirstSourceId();
        secondSourceId = snapshot.getSecondSourceId();
        rulesRevision = snapshot.getRulesRevision();
        revision = snapshot.getRevision();
        ready = snapshot.getReady();
        serializedSize = snapshot.getSerializedSize();
        for (EnvironmentBoundaryPatch patch : snapshot.getPatchesList()) {
            patches.put(patch.getPatchId(), patch);
            weightedArea = weightedArea.add(contribution(patch));
        }
        weightedAreaBlocks = checkedWeightedArea(weightedArea);
    }

    public void apply(EnvironmentBoundaryPatchUpdate update) {
        // 校验目标、版本和规则世代，再计算替换结果；失败不能留下半次分片变更。
        EnvironmentTopologyProtobuf.validateUpdate(update);
        if (!boundaryId.equals(update.getBoundaryId())) {
            throw MeridianErrors.invalid(Environment.BOUNDARY_ID);
        }
        requireRevision(update.getExpectedRevision());
        requireReady(update.getRulesRevision());

        String patchId = update.getRemovePatchId();
        EnvironmentBoundaryPatch replacement = null;
        if (update.hasUpsert()) {
            replacement = update.getUpsert();
            patchId = replacement.getPatchId();
        }
        EnvironmentBoundaryPatch previous = patches.get(patchId);
        if (replacement == null && previous == null) {
            throw MeridianErrors.invalid(Environment.BOUNDARY_PATCH_MISSING);
        }
        if (replacement != null && previous == null && patches.size() >= MeridianProtocol.MAX_LIST_ENTRIES) {
            throw MeridianErrors.invalid(Environment.BOUNDARY_PATCH_LIMIT);
        }

        // 原贡献来自缓存自身，宿主只给新的绝对值，避免陈旧旧值或重复加法造成累计漂移。
        BigDecimal updatedArea = weightedArea;
        if (previous != null) {
            updatedArea = updatedArea.subtract(contribution(previous));
        }
        if (replacement != null) {
            updatedArea = updatedArea.add(contribution(replacement));
        }
        double updatedAreaBlocks = checkedWeightedArea(updatedArea);

        // 只计算受影响字段的编码大小，保证更新后仍可保存，不为检查大小扫描全部分片。
        int updatedSize = sizeAfterRevisionAdvance();
        if (previous != null) {
            updatedSize -= CodedOutputStream.computeMessageSize(8, previous);
        }
        if (replacement != null) {
            updatedSize += CodedOutputStream.computeMessageSize(8, replacement);
        }
        requireSnapshotSize(updatedSize);

        if (replacement == null) {
            patches.remove(patchId);
        } else {
            patches.put(patchId, replacement);
        }
        weightedArea = updatedArea;
        weightedAreaBlocks = updatedAreaBlocks;
        serializedSize = updatedSize;
        revision++;
    }

    public void invalidate(long expectedRevision) {
        // 定义或规则变更后先停用旧缓存，旧数据保留供重建诊断。
        requireRevision(expectedRevision);
        int updatedSize = sizeAfterRevisionAdvance();
        requireSnapshotSize(updatedSize);
        ready = false;
        serializedSize = updatedSize;
        revision++;
    }

    public double candidateDiffusionPerSecond(EnvironmentQiRules rules, long currentRulesRevision,
            String sourceId, double sourceQiPerBlock, double targetQiPerBlock) {
        // 无论哪一端发起，都使用同一边界贡献；未就绪的定义不能被当成零流量结果。
        MeridianErrors.required(rules, Environment.BOUNDARY_RULES_REQUIRED);
        requireReady(currentRulesRevision);
        if (!firstSourceId.equals(sourceId) && !secondSourceId.equals(sourceId)) {
            throw MeridianErrors.invalid(Environment.BOUNDARY_SOURCE);
        }
        return rules.candidateDiffusionPerSecond(sourceQiPerBlock, targetQiPerBlock, weightedAreaBlocks, 1);
    }

    public EnvironmentBoundarySnapshot snapshot() {
        // 只在保存或交换缓存时复制分片；正常流量查询无需扫描这张表。
        return EnvironmentBoundarySnapshot.newBuilder().setProtocolVersion(1).setBoundaryId(boundaryId)
                .setFirstSourceId(firstSourceId).setSecondSourceId(secondSourceId)
                .setRevision(revision).setRulesRevision(rulesRevision).setReady(ready)
                .addAllPatches(patches.values()).build();
    }

    private void requireRevision(long expectedRevision) {
        // 事件只能更新它实际观察过的缓存版本，重复事件不会重复累计传播能力。
        if (expectedRevision != revision || revision == Long.MAX_VALUE) {
            throw MeridianErrors.invalid(Environment.BOUNDARY_REVISION);
        }
    }

    private void requireReady(long currentRulesRevision) {
        // 安装新的传播参数后，config_version 相同也不能沿用旧规则计算的贡献。
        if (currentRulesRevision != rulesRevision) {
            throw MeridianErrors.invalid(Environment.BOUNDARY_RULES_REVISION);
        }
        if (!ready) {
            throw MeridianErrors.invalid(Environment.BOUNDARY_NOT_READY);
        }
    }

    private int sizeAfterRevisionAdvance() {
        // revision 是显式字段，跨越 varint 长度边界时快照会多占一个字节。
        int previousSize = CodedOutputStream.computeInt64Size(5, revision);
        int advancedSize = CodedOutputStream.computeInt64Size(5, revision + 1);
        return serializedSize - previousSize + advancedSize;
    }

    private static void requireSnapshotSize(int size) {
        // 多次小更新不能绕过完整缓存的线协议载荷上限。
        if (size > MeridianProtocol.MAX_PAYLOAD_BYTES) {
            throw MeridianErrors.invalid(Protocol.OVERSIZED_MESSAGE);
        }
    }

    private static BigDecimal contribution(EnvironmentBoundaryPatch patch) {
        // 精确累加派生权重，避免巨大增强分片遮住微小贡献，拆除增强方块后产生永久残留。
        return BigDecimal.valueOf(patch.getAreaBlocks()).multiply(BigDecimal.valueOf(patch.getTransmission()));
    }

    private static double checkedWeightedArea(BigDecimal area) {
        // 运行速率仍使用 double，提交缓存前拒绝无法表达的总传播能力。
        double result = area.doubleValue();
        if (!Double.isFinite(result) || result < 0) {
            throw MeridianErrors.invalid(Environment.BOUNDARY_NUMERIC_LIMIT);
        }
        return result;
    }
}
