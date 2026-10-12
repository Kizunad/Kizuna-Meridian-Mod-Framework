package dev.kizuna.meridian.environment;

import dev.kizuna.meridian.protocol.EnvironmentTopologyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundaryPatch;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundaryPatchUpdate;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundarySnapshot;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpace;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentSpaceKind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class EnvironmentTopologyTest {
    @Test
    public void roomsChannelsAndBackgroundUseSameSourceContract() {
        // 特殊空间复用来源身份和体积，跨语言往返不产生额外库存或硬编码的人类空间。
        for (EnvironmentSpaceKind kind : new EnvironmentSpaceKind[] {
                EnvironmentSpaceKind.REGION_BACKGROUND, EnvironmentSpaceKind.LOCAL_ROOM,
                EnvironmentSpaceKind.LOCAL_CHANNEL
        }) {
            var space = EnvironmentSpace.newBuilder().setProtocolVersion(1)
                    .setSourceId("world:space").setKind(kind).setVolumeBlocks(64).build();
            byte[] payload = EnvironmentTopologyProtobuf.encodeSpace(space);
            assertEquals(space, EnvironmentTopologyProtobuf.decodeSpace(payload));
            assertThrows(IllegalArgumentException.class, () -> MeridianProtocol.decodeRequest(payload));
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentTopologyProtobuf.decodeSpace(space.toBuilder().setVolumeBlocks(0).build().toByteArray()));
        }
    }

    @Test
    public void cachedBoundaryUpdatesOnlyChangedPatchAndRestoresFromProtobuf() {
        // 房间墙面和通道端口共用一条边界，特殊端口可高于空气基准。
        var rules = new EnvironmentQiRules(EnvironmentConfigLoader.defaults());
        var cache = new EnvironmentBoundaryCache(boundary().addPatches(patch("wall", 100, 0))
                .addPatches(patch("port", 4, 2.5)).build());
        assertEquals(20, cache.candidateDiffusionPerSecond(rules, 7, "room", 3, 1), 0);
        assertEquals(20, cache.candidateDiffusionPerSecond(rules, 7, "background", 3, 1), 0);

        var update = update(0).setUpsert(patch("port", 4, 1)).build();
        cache.apply(EnvironmentTopologyProtobuf.decodeUpdate(EnvironmentTopologyProtobuf.encodeUpdate(update)));
        assertEquals(8, cache.candidateDiffusionPerSecond(rules, 7, "room", 3, 1), 0);
        assertEquals(1, cache.snapshot().getRevision());
        assertThrows(IllegalArgumentException.class, () -> cache.apply(update));
        assertEquals(1, cache.snapshot().getRevision());

        EnvironmentBoundarySnapshot saved = EnvironmentTopologyProtobuf.decodeBoundary(
                EnvironmentTopologyProtobuf.encodeBoundary(cache.snapshot()));
        var restored = new EnvironmentBoundaryCache(saved);
        assertEquals(8, restored.candidateDiffusionPerSecond(rules, 7, "room", 3, 1), 0);
        restored.apply(update(1).setRemovePatchId("port").build());
        assertEquals(0, restored.candidateDiffusionPerSecond(rules, 7, "room", 3, 1), 0);
        assertEquals(1, restored.snapshot().getPatchesCount());
    }

    @Test
    public void invalidOrStaleGeometryCannotProduceFlowOrPartiallyUpdateCache() {
        // 重建期间、规则切换后或来自错误边界的消息不能继续使用旧传播能力。
        var rules = new EnvironmentQiRules(EnvironmentConfigLoader.defaults());
        var cache = new EnvironmentBoundaryCache(boundary().addPatches(patch("wall", 1, 1)).build());
        EnvironmentBoundarySnapshot original = cache.snapshot();
        for (EnvironmentBoundaryPatchUpdate invalid : new EnvironmentBoundaryPatchUpdate[] {
                update(0).setBoundaryId("other").setUpsert(patch("wall", 1, 2)).build(),
                update(0).setRulesRevision(8).setUpsert(patch("wall", 1, 2)).build(),
                update(1).setUpsert(patch("wall", 1, 2)).build(),
                update(0).setRemovePatchId("absent").build(),
                update(0).setUpsert(patch("wall", Double.MAX_VALUE, 2)).build()
        }) {
            assertThrows(IllegalArgumentException.class, () -> cache.apply(invalid));
            assertEquals(original, cache.snapshot());
        }
        assertThrows(IllegalArgumentException.class,
                () -> cache.candidateDiffusionPerSecond(rules, 8, "room", 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> cache.candidateDiffusionPerSecond(rules, 7, "elsewhere", 2, 1));

        cache.invalidate(0);
        assertThrows(IllegalArgumentException.class,
                () -> cache.candidateDiffusionPerSecond(rules, 7, "room", 2, 1));
        assertThrows(IllegalArgumentException.class, () -> cache.apply(update(1).setUpsert(patch("wall", 1, 1)).build()));
        var restored = new EnvironmentBoundaryCache(EnvironmentTopologyProtobuf.decodeBoundary(
                EnvironmentTopologyProtobuf.encodeBoundary(cache.snapshot())));
        assertThrows(IllegalArgumentException.class,
                () -> restored.candidateDiffusionPerSecond(rules, 7, "room", 2, 1));
    }

    @Test
    public void replacingEnhancedPatchDoesNotEraseSmallContributions() {
        // 极大增强端口移除后，小端口必须恢复原值；重复增删不能因浮点相消留下错误密闭状态。
        var rules = new EnvironmentQiRules(EnvironmentConfigLoader.defaults());
        var cache = new EnvironmentBoundaryCache(boundary().addPatches(patch("small", 1, 0.1))
                .addPatches(patch("large", 1, 1e100)).build());
        cache.apply(update(0).setRemovePatchId("large").build());
        assertEquals(0.1, cache.candidateDiffusionPerSecond(rules, 7, "room", 2, 1), 0);
        for (int cycle = 0; cycle < 100; cycle++) {
            cache.apply(update(1 + cycle * 2).setUpsert(patch("large", 1, 1e100)).build());
            cache.apply(update(2 + cycle * 2).setRemovePatchId("large").build());
        }
        assertEquals(0.1, cache.candidateDiffusionPerSecond(rules, 7, "room", 2, 1), 0);
        assertEquals(cache.snapshot(), EnvironmentTopologyProtobuf.decodeBoundary(
                EnvironmentTopologyProtobuf.encodeBoundary(cache.snapshot())));
    }

    @Test
    public void malformedTopologyIsRejectedAtBothCodecBoundaries() {
        // 保存恢复也要检查身份、显式零值、重复分片和数值溢出，防止绕过运行入口。
        var valid = boundary().addPatches(patch("wall", 256, 0)).build();
        for (EnvironmentBoundarySnapshot invalid : new EnvironmentBoundarySnapshot[] {
                valid.toBuilder().setProtocolVersion(2).build(),
                valid.toBuilder().clearRevision().build(),
                valid.toBuilder().clearRulesRevision().build(),
                valid.toBuilder().clearReady().build(),
                valid.toBuilder().setSecondSourceId("room").build(),
                valid.toBuilder().addPatches(patch("wall", 1, 1)).build(),
                valid.toBuilder().addPatches(patch("other", 0, 1)).build(),
                valid.toBuilder().addPatches(patch("other", 1, -1)).build(),
                valid.toBuilder().addPatches(patch("other", 1, Double.NaN)).build(),
                valid.toBuilder().addPatches(patch("other", Double.MAX_VALUE, 2)).build(),
                valid.toBuilder().addPatches(patch("huge-a", 1, Double.MAX_VALUE))
                        .addPatches(patch("huge-b", 1, Double.MAX_VALUE)).build()
        }) {
            assertThrows(IllegalArgumentException.class, () -> EnvironmentTopologyProtobuf.encodeBoundary(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentTopologyProtobuf.decodeBoundary(invalid.toByteArray()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> EnvironmentTopologyProtobuf.decodeUpdate(update(0).build().toByteArray()));
    }

    @Test
    public void incrementalUpdatesCannotOutgrowSavableSnapshot() {
        // 连续小消息也不能让累计缓存突破完整载荷上限，超限时仍保留原版本和全部分片。
        var snapshot = boundary().setRevision(127);
        String idPadding = "x".repeat(1020);
        for (int index = 1000; index < 2000; index++) {
            snapshot.addPatches(patch(index + idPadding, 1, 1));
        }
        var cache = new EnvironmentBoundaryCache(snapshot.build());
        cache.apply(update(127).setUpsert(patch("1000" + idPadding, 1, 2.5)).build());
        EnvironmentBoundarySnapshot saved = cache.snapshot();
        assertEquals(128, saved.getRevision());
        assertEquals(saved, EnvironmentTopologyProtobuf.decodeBoundary(EnvironmentTopologyProtobuf.encodeBoundary(saved)));

        var tooLarge = update(128).setUpsert(patch("2000" + idPadding, 1, 1)).build();
        assertThrows(IllegalArgumentException.class, () -> cache.apply(tooLarge));
        assertEquals(saved, cache.snapshot());
        cache.invalidate(128);
        assertEquals(cache.snapshot(), EnvironmentTopologyProtobuf.decodeBoundary(
                EnvironmentTopologyProtobuf.encodeBoundary(cache.snapshot())));
    }

    private static EnvironmentBoundarySnapshot.Builder boundary() {
        // 一个房间与背景来源共用同一边界，规则世代与缓存版本明确分开。
        return EnvironmentBoundarySnapshot.newBuilder().setProtocolVersion(1).setBoundaryId("room-boundary")
                .setFirstSourceId("room").setSecondSourceId("background")
                .setRevision(0).setRulesRevision(7).setReady(true);
    }

    private static EnvironmentBoundaryPatch patch(String id, double area, double transmission) {
        // 分片可以表达 section 截面或特殊端口，测试不需要依赖 Minecraft 世界对象。
        return EnvironmentBoundaryPatch.newBuilder().setPatchId(id).setAreaBlocks(area)
                .setTransmission(transmission).build();
    }

    private static EnvironmentBoundaryPatchUpdate.Builder update(long revision) {
        // 使用绝对分片替换和预期版本，重复事件必须被拒绝而非再次增加导通能力。
        return EnvironmentBoundaryPatchUpdate.newBuilder().setBoundaryId("room-boundary")
                .setExpectedRevision(revision).setRulesRevision(7);
    }
}
