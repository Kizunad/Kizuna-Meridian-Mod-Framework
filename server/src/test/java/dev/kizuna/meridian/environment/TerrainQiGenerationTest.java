package dev.kizuna.meridian.environment;

import dev.kizuna.meridian.protocol.EnvironmentConfigProtobuf;
import dev.kizuna.meridian.protocol.EnvironmentTopologyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.TerrainQiProtobuf;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiConfig;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiBoundaryRequest;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiRegionRequest;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiRegionSeed;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TerrainQiGenerationTest {
    @Test
    public void terrainConfigurationTravelsThroughTomlAndProtobuf() {
        // 自定义群系可直接归入已有预设；完整配置须包含地形表并经过协议校验。
        EnvironmentQiConfig config = EnvironmentConfigLoader.parse("""
                [generation.profiles."meridian:forest"]
                biomes = ["addon:ancient_forest"]
                concentration_min = 1.2
                concentration_max = 1.2
                weights = [1, 90, 3, 2, 4]
                """);
        assertEquals(2, config.getConfigVersion());
        assertEquals(config, EnvironmentConfigProtobuf.decode(EnvironmentConfigProtobuf.encode(config)));
        var generator = new TerrainQiGenerator(config.getGeneration());
        TerrainQiRegionSeed seed = generator.generateRegion(request("addon:ancient_forest", "a"));
        assertEquals(1.2, concentration(seed), 1e-15);
        assertTrue(seed.getWeights().getWood() > seed.getWeights().getEarth());

        // 错误配置在文件和直接消息入口都拒绝，不能依赖加载顺序覆盖重复映射。
        for (String invalid : new String[] {
                "config_version = 1",
                "[generation]\nrules_version = 2",
                "[generation]\npreference_jitter = 1",
                "[generation]\nunclassified_min = 2",
                "[generation]\nboundary_max = -1",
                "[generation.profiles.\"meridian:forest\"]\nweights = [1, 2]",
                "[generation.profiles.\"meridian:forest\"]\nweights = [1, 2, 0, 3, 4]",
                "[generation.profiles.\"meridian:forest\"]\nbiomes = [\"minecraft:desert\"]",
                "[generation.profiles.\"addon:new\"]\nbiomes = [\"addon:new\"]",
                "[generation.profiles.\"addon:new\"]\nbiomes = [3]"
        }) {
            assertThrows(invalid, IllegalArgumentException.class, () -> EnvironmentConfigLoader.parse(invalid));
        }
        var invalid = config.toBuilder().setGeneration(config.getGeneration().toBuilder()
                .addProfiles(config.getGeneration().getProfiles(0))).build();
        assertThrows(IllegalArgumentException.class, () -> EnvironmentConfigProtobuf.encode(invalid));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentConfigProtobuf.decode(invalid.toByteArray()));
        assertThrows(IllegalArgumentException.class,
                () -> EnvironmentConfigProtobuf.decode(config.toBuilder().clearGeneration().build().toByteArray()));
    }

    @Test
    public void knownTerrainHasStableConcentrationAndElementPreference() {
        // 固定向量由独立 Python SHA-256 核对，保护跨语言算法及负种子、负区域坐标。
        var config = EnvironmentConfigLoader.defaults().getGeneration();
        var generator = new TerrainQiGenerator(config);
        var request = request("minecraft:desert", "region/-1/0");
        var seed = generator.generateRegion(request);
        assertEquals(0.22561997036397266, concentration(seed), 1e-15);
        assertEquals(seed, generator.generateRegion(TerrainQiProtobuf.decodeRegionRequest(
                TerrainQiProtobuf.encodeRegionRequest(request))));
        assertEquals(seed, TerrainQiProtobuf.decodeSeed(TerrainQiProtobuf.encodeSeed(seed)));

        var reordered = new ArrayList<>(config.getProfilesList());
        Collections.reverse(reordered);
        var reorderedGenerator = new TerrainQiGenerator(config.toBuilder().clearProfiles().addAllProfiles(reordered).build());
        assertEquals(seed, reorderedGenerator.generateRegion(request));
        for (int index = 0; index < 20; index++) {
            TerrainQiRegionSeed desert = generator.generateRegion(request("minecraft:desert", "d" + index));
            TerrainQiRegionSeed forest = generator.generateRegion(request("minecraft:forest", "f" + index));
            assertTrue(concentration(desert) >= 0.1 && concentration(desert) <= 0.3);
            assertTrue(concentration(forest) >= 1 && concentration(forest) <= 1.8);
            assertEquals(5, desert.getInitialQi().getEntriesCount());
            assertTrue(desert.getWeights().getEarth() > desert.getWeights().getFire());
            assertTrue(forest.getWeights().getWood() > forest.getWeights().getWater());
        }
    }

    @Test
    public void unknownBiomeGetsStableRangeAndPreferenceBeforeRegionSampling() {
        // 暂停区域扰动后，同世界的同一未知群系共享区间和偏好，区域浓度仍独立抽样。
        var config = EnvironmentConfigLoader.parse("[generation]\npreference_jitter = 0").getGeneration();
        var generator = new TerrainQiGenerator(config);
        TerrainQiRegionSeed first = generator.generateRegion(request("addon:unclassified", "a"));
        TerrainQiRegionSeed second = generator.generateRegion(request("addon:unclassified", "b"));
        assertEquals(first.getConcentration(), second.getConcentration());
        assertEquals(first.getWeights(), second.getWeights());
        assertNotEquals(first.getInitialQi(), second.getInitialQi());
        assertTrue(first.getConcentration().getMinimum() >= 0.2);
        assertTrue(first.getConcentration().getMaximum() <= 1.6);
        assertEquals(first, new TerrainQiGenerator(config).generateRegion(first.getRequest()));
        assertNotEquals(first.getConcentration(), generator.generateRegion(first.getRequest().toBuilder()
                .setWorldSeed("-43").build()).getConcentration());

        // 生成器只返回候选值，恢复记录不会触发生成或修改既有库存。
        assertEquals(first, TerrainQiProtobuf.decodeSeed(TerrainQiProtobuf.encodeSeed(first)));
    }

    @Test
    public void boundaryGenerationIsSymmetricAndFeedsExistingCache() {
        // 相同端点交换输入顺序仍得到相同共享边界；显式增强倍率可以超过 1。
        var config = EnvironmentConfigLoader.parse("[generation]\nboundary_min = 2.5\nboundary_max = 2.5");
        var generator = new TerrainQiGenerator(config.getGeneration());
        var request = TerrainQiBoundaryRequest.newBuilder().setProtocolVersion(1).setWorldSeed("-42")
                .setDimensionId("minecraft:overworld").setBoundaryId("ab").setFirstSourceId("b")
                .setSecondSourceId("a").setAreaBlocks(10).setRulesRevision(7).build();
        var boundary = generator.generateBoundary(TerrainQiProtobuf.decodeBoundaryRequest(
                TerrainQiProtobuf.encodeBoundaryRequest(request)));
        assertEquals(boundary, generator.generateBoundary(request.toBuilder()
                .setFirstSourceId("a").setSecondSourceId("b").build()));
        var restored = EnvironmentTopologyProtobuf.decodeBoundary(EnvironmentTopologyProtobuf.encodeBoundary(boundary));
        var cache = new EnvironmentBoundaryCache(restored);
        assertEquals(25, cache.candidateDiffusionPerSecond(new EnvironmentQiRules(config), 7, "a", 2, 1), 0);
        assertThrows(IllegalArgumentException.class, () -> generator.generateBoundary(request.toBuilder()
                .setFirstSourceId("a").setSecondSourceId("a").build()));
    }

    @Test
    public void invalidGenerationFactsCannotCrossProtocolBoundary() {
        // 主世界配置不能隐式套用其他维度；无效体积、缺失字段及伪造的初始余额全部拒绝。
        var generator = new TerrainQiGenerator(EnvironmentConfigLoader.defaults().getGeneration());
        var request = request("minecraft:plains", "r");
        assertThrows(IllegalArgumentException.class, () -> generator.generateRegion(request.toBuilder()
                .setDimensionId("minecraft:the_nether").build()));
        assertThrows(IllegalArgumentException.class, () -> generator.generateRegion(request.toBuilder().setVolumeBlocks(0).build()));
        assertThrows(IllegalArgumentException.class, () -> generator.generateRegion(request.toBuilder()
                .setBiomeId("minecraft:forest").setVolumeBlocks(Double.MAX_VALUE).build()));
        assertThrows(IllegalArgumentException.class, () -> generator.generateRegion(request.toBuilder()
                .setVolumeBlocks(Double.MIN_VALUE).build()));
        assertThrows(IllegalArgumentException.class, () -> TerrainQiProtobuf.decodeRegionRequest(
                request.toBuilder().clearVolumeBlocks().build().toByteArray()));
        var seed = generator.generateRegion(request);
        var invalid = seed.toBuilder().setInitialQi(seed.getInitialQi().toBuilder()
                .setEntries(0, seed.getInitialQi().getEntries(0).toBuilder().setAmount(10000))).build();
        assertThrows(IllegalArgumentException.class, () -> TerrainQiProtobuf.encodeSeed(invalid));
        assertThrows(IllegalArgumentException.class, () -> TerrainQiProtobuf.decodeSeed(invalid.toByteArray()));
        assertThrows(IllegalArgumentException.class,
                () -> MeridianProtocol.decodeRequest(TerrainQiProtobuf.encodeRegionRequest(request)));
    }

    private static TerrainQiRegionRequest request(String biomeId, String sourceId) {
        // 宿主使用稳定来源身份提供地形元数据，样例不依赖真实世界扫描。
        return TerrainQiRegionRequest.newBuilder().setProtocolVersion(1).setWorldSeed("-42")
                .setDimensionId("minecraft:overworld").setSourceId(sourceId).setBiomeId(biomeId)
                .setVolumeBlocks(1000).build();
    }

    private static double concentration(TerrainQiRegionSeed seed) {
        // 从五行唯一明细求和验证总浓度，不为每种属性重复发放整个区域总量。
        return seed.getInitialQi().getEntriesList().stream().mapToDouble(entry -> entry.getAmount()).sum()
                / seed.getRequest().getVolumeBlocks();
    }
}
