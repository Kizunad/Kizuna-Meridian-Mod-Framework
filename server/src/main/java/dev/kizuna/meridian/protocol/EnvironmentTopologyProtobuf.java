package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundaryPatch;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundaryPatchUpdate;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundarySnapshot;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpace;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** 空间描述与派生边界缓存的跨语言边界；范围与连接由可信宿主定义。 */
public final class EnvironmentTopologyProtobuf {
    private EnvironmentTopologyProtobuf() {
        // 有界编解码与运行缓存复用校验，不保存位置或区域库存。
    }

    public static byte[] encodeSpace(EnvironmentSpace space) {
        // 来源描述与交换使用相同来源身份，体积必须足以计算浓度。
        validateSpace(space);
        return ProtoWire.write(space);
    }

    public static EnvironmentSpace decodeSpace(byte[] payload) {
        // 先检查消息规模，再验证已知空间类型和有效体积。
        EnvironmentSpace space = ProtoWire.read(payload, EnvironmentSpace.parser());
        validateSpace(space);
        return space;
    }

    public static void validateSpace(EnvironmentSpace space) {
        // 类型存在性与有限数由公共边界保证；独立空间不能具有零体积。
        ProtoWire.validate(space);
        ProtoWire.require(space.getProtocolVersion() == 1, Environment.SPACE_VERSION);
        ProtoWire.require(space.getVolumeBlocks() > 0, Environment.SPACE_VOLUME);
    }

    public static byte[] encodeBoundary(EnvironmentBoundarySnapshot boundary) {
        // 缓存可以保存为未就绪状态，恢复后仍不得把它当成已确认的密闭边界。
        validateBoundary(boundary);
        return ProtoWire.write(boundary);
    }

    public static EnvironmentBoundarySnapshot decodeBoundary(byte[] payload) {
        // 保留就绪状态、规则世代和分片身份，恢复缓存时能验证其适用范围。
        EnvironmentBoundarySnapshot boundary = ProtoWire.read(payload, EnvironmentBoundarySnapshot.parser());
        validateBoundary(boundary);
        return boundary;
    }

    public static void validateBoundary(EnvironmentBoundarySnapshot boundary) {
        // 同一边界的两个端点必须不同，分片不能因重复 ID 在恢复时悄悄覆盖。
        ProtoWire.validate(boundary);
        ProtoWire.require(boundary.getProtocolVersion() == 1, Environment.SPACE_VERSION);
        ProtoWire.require(!boundary.getFirstSourceId().equals(boundary.getSecondSourceId()),
                Environment.SAME_BOUNDARY_SOURCE);
        Set<String> patchIds = new HashSet<>();
        BigDecimal weightedArea = BigDecimal.ZERO;
        for (EnvironmentBoundaryPatch patch : boundary.getPatchesList()) {
            validatePatch(patch);
            ProtoWire.require(patchIds.add(patch.getPatchId()), Environment.DUPLICATE_BOUNDARY_PATCH);
            BigDecimal contribution = BigDecimal.valueOf(patch.getAreaBlocks())
                    .multiply(BigDecimal.valueOf(patch.getTransmission()));
            weightedArea = weightedArea.add(contribution);
        }
        ProtoWire.require(Double.isFinite(weightedArea.doubleValue()), Environment.BOUNDARY_NUMERIC_LIMIT);
    }

    public static byte[] encodeUpdate(EnvironmentBoundaryPatchUpdate update) {
        // 增量消息携带预期版本，不能通过重复投递再次累计贡献。
        validateUpdate(update);
        return ProtoWire.write(update);
    }

    public static EnvironmentBoundaryPatchUpdate decodeUpdate(byte[] payload) {
        // 更新只允许明确的替换或删除分支，不接受没有意图的空消息。
        EnvironmentBoundaryPatchUpdate update = ProtoWire.read(payload, EnvironmentBoundaryPatchUpdate.parser());
        validateUpdate(update);
        return update;
    }

    public static void validateUpdate(EnvironmentBoundaryPatchUpdate update) {
        // 合法单分片更新也必须能产生下一版本，避免版本溢出后接受旧事件。
        ProtoWire.validate(update);
        ProtoWire.require(update.getExpectedRevision() < Long.MAX_VALUE, Environment.BOUNDARY_REVISION);
        if (update.hasUpsert()) {
            validatePatch(update.getUpsert());
        }
    }

    private static void validatePatch(EnvironmentBoundaryPatch patch) {
        // 面积为零应直接删除分片；系数为零则表示面积仍存在但被屏障封闭。
        ProtoWire.require(patch.getAreaBlocks() > 0, Environment.PATCH_AREA);
        double contribution = patch.getAreaBlocks() * patch.getTransmission();
        ProtoWire.require(Double.isFinite(contribution), Environment.BOUNDARY_NUMERIC_LIMIT);
    }
}
