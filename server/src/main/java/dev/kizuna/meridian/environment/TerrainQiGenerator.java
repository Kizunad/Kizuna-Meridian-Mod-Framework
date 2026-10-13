package dev.kizuna.meridian.environment;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.EnvironmentTopologyProtobuf;
import dev.kizuna.meridian.protocol.TerrainQiProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundaryPatch;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentBoundarySnapshot;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentNumericRange;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.FiveElementWeights;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiBoundaryRequest;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiGenerationConfig;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiProfile;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiRegionRequest;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiRegionSeed;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 由可信地形元数据确定初始条件；不扫描方块，不注册事件，不写环境库存。 */
public final class TerrainQiGenerator {
    private final TerrainQiGenerationConfig config;
    private final Map<String, TerrainQiProfile> profilesByBiome;

    public TerrainQiGenerator(TerrainQiGenerationConfig config) {
        // 先验证整个目录再建立索引，配置顺序不能决定一个群系采用哪个预设。
        TerrainQiProtobuf.validateConfig(config);
        this.config = config;
        Map<String, TerrainQiProfile> profiles = new HashMap<>();
        for (TerrainQiProfile profile : config.getProfilesList()) {
            for (String biomeId : profile.getBiomeIdsList()) {
                profiles.put(biomeId, profile);
            }
        }
        profilesByBiome = Map.copyOf(profiles);
    }

    public TerrainQiRegionSeed generateRegion(TerrainQiRegionRequest request) {
        // 1. 区域身份和群系由宿主生成系统提供，玩家不能提交它们来重新抽取库存。
        TerrainQiProtobuf.validateRegionRequest(request);
        requireDimension(request.getDimensionId());
        TerrainQiProfile profile = profilesByBiome.get(request.getBiomeId());
        EnvironmentNumericRange concentrationRange;
        double[] baseWeights = new double[5];
        if (profile != null) {
            concentrationRange = profile.getConcentration();
            List<Double> configuredWeights = TerrainQiProtobuf.weights(profile.getWeights());
            for (int index = 0; index < baseWeights.length; index++) {
                baseWeights[index] = configuredWeights.get(index);
            }
        } else {
            // 未分类地形先得到整类群系共享的区间与偏好，区域 ID 不参与此阶段。
            double first = terrainSample(request, "unknown-range-a", config.getUnclassifiedConcentration());
            double second = terrainSample(request, "unknown-range-b", config.getUnclassifiedConcentration());
            concentrationRange = EnvironmentNumericRange.newBuilder()
                    .setMinimum(Math.min(first, second)).setMaximum(Math.max(first, second)).build();
            for (int index = 0; index < baseWeights.length; index++) {
                String element = TerrainQiProtobuf.ELEMENT_IDS.get(index);
                baseWeights[index] = 1 + 99 * sample("unknown-weight:" + element,
                        request.getWorldSeed(), request.getDimensionId(), request.getBiomeId());
            }
        }

        // 2. 区域在本地形区间内抽样，五行分别轻微扰动后归一化；总浓度只抽取一次。
        double concentration = interpolate(concentrationRange, sample("region-concentration",
                request.getWorldSeed(), request.getDimensionId(), request.getBiomeId(), request.getSourceId()));
        double totalQi = concentration * request.getVolumeBlocks();
        if (!Double.isFinite(totalQi) || (concentration > 0 && totalQi == 0)) {
            throw MeridianErrors.invalid(Environment.GENERATION_NUMERIC);
        }
        double[] weights = new double[5];
        double totalWeight = 0;
        for (int index = 0; index < weights.length; index++) {
            String element = TerrainQiProtobuf.ELEMENT_IDS.get(index);
            double draw = sample("region-weight:" + element,
                    request.getWorldSeed(), request.getDimensionId(), request.getBiomeId(), request.getSourceId());
            double multiplier = 1 - config.getPreferenceJitter() + 2 * config.getPreferenceJitter() * draw;
            weights[index] = baseWeights[index] * multiplier;
            totalWeight += weights[index];
        }
        if (!Double.isFinite(totalWeight) || Arrays.stream(weights).anyMatch(weight -> weight <= 0)) {
            throw MeridianErrors.invalid(Environment.GENERATION_NUMERIC);
        }

        // 3. 将总量拆成五份游离态灵气；最后一份收尾，避免把舍入余量另记为第六份资源。
        var inventory = QiInventory.newBuilder();
        double remainingQi = totalQi;
        for (int index = 0; totalQi > 0 && index < weights.length; index++) {
            double amount = totalQi * (weights[index] / totalWeight);
            if (index == weights.length - 1) {
                amount = remainingQi;
            }
            if (!Double.isFinite(amount) || amount <= 0) {
                throw MeridianErrors.invalid(Environment.GENERATION_NUMERIC);
            }
            inventory.addEntries(Qi.newBuilder().setPhase(QiPhase.FREE)
                    .setAttribute(TerrainQiProtobuf.ELEMENT_IDS.get(index)).setAmount(amount));
            remainingQi -= amount;
        }

        var result = TerrainQiRegionSeed.newBuilder().setRequest(request).setRulesVersion(config.getRulesVersion())
                .setConcentration(concentrationRange).setWeights(FiveElementWeights.newBuilder()
                        .setMetal(weights[0]).setWood(weights[1]).setWater(weights[2])
                        .setFire(weights[3]).setEarth(weights[4]))
                .setInitialQi(inventory).build();
        TerrainQiProtobuf.validateSeed(result);
        return result;
    }

    public EnvironmentBoundarySnapshot generateBoundary(TerrainQiBoundaryRequest request) {
        // 两端按 UTF-8 无符号字节排序，不依赖 Java 字符串排序或哪个区域先加载。
        TerrainQiProtobuf.validateBoundaryRequest(request);
        requireDimension(request.getDimensionId());
        String first = request.getFirstSourceId();
        String second = request.getSecondSourceId();
        if (Arrays.compareUnsigned(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8)) > 0) {
            String originalFirst = first;
            first = second;
            second = originalFirst;
        }
        double transmission = interpolate(config.getBoundaryTransmission(), sample("boundary-transmission",
                request.getWorldSeed(), request.getDimensionId(), first, second));
        var boundary = EnvironmentBoundarySnapshot.newBuilder().setProtocolVersion(1)
                .setBoundaryId(request.getBoundaryId()).setFirstSourceId(first).setSecondSourceId(second)
                .setRevision(0).setRulesRevision(request.getRulesRevision()).setReady(true)
                .addPatches(EnvironmentBoundaryPatch.newBuilder().setPatchId("generated")
                        .setAreaBlocks(request.getAreaBlocks()).setTransmission(transmission)).build();
        EnvironmentTopologyProtobuf.validateBoundary(boundary);
        return boundary;
    }

    private double terrainSample(TerrainQiRegionRequest request, String purpose, EnvironmentNumericRange range) {
        // 整类地形使用相同的基础抽样输入，后续新增同类区域不会再随机选择一个地形区间。
        return interpolate(range, sample(purpose, request.getWorldSeed(), request.getDimensionId(), request.getBiomeId()));
    }

    private void requireDimension(String dimension) {
        // 不把主世界的草木偏好自动套给地狱或其他维度；宿主须安装对应维度配置。
        if (!config.getDimensionId().equals(dimension)) {
            throw MeridianErrors.invalid(Environment.GENERATION_DIMENSION);
        }
    }

    private static double interpolate(EnvironmentNumericRange range, double draw) {
        // 先减再乘使有限非负端点间的插值保持有限，固定区间直接得到同一个值。
        return range.getMinimum() + (range.getMaximum() - range.getMinimum()) * draw;
    }

    private double sample(String purpose, String... keys) {
        // 每个用途独立哈希，增加别处的随机抽样不会使后续全部结果错位；长度前缀消除拼接歧义。
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hashText(hash, "kizuna:terrain-qi");
            hashText(hash, Integer.toString(config.getRulesVersion()));
            hashText(hash, purpose);
            for (String key : keys) {
                hashText(hash, key);
            }
            long upper53Bits = ByteBuffer.wrap(hash.digest()).getLong() >>> 11;
            return upper53Bits * 0x1.0p-53;
        } catch (NoSuchAlgorithmException exception) {
            throw MeridianErrors.invalid(Environment.GENERATION_HASH, exception);
        }
    }

    private static void hashText(MessageDigest hash, String text) {
        // 协议约定四字节大端 UTF-8 长度，其他语言可复现相同抽样结果。
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }
}
