package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentNumericRange;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.FiveElementWeights;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiBoundaryRequest;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiGenerationConfig;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiRegionRequest;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiRegionSeed;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 可信地形生成输入和初始库存候选；不注册为客户端请求或执行库存写入。 */
public final class TerrainQiProtobuf {
    public static final List<String> ELEMENT_IDS = List.of(
            "meridian:metal", "meridian:wood", "meridian:water", "meridian:fire", "meridian:earth");

    private TerrainQiProtobuf() {
        // 配置、生成和存档交换共用同一校验边界。
    }

    public static void validateConfig(TerrainQiGenerationConfig config) {
        // 所有字段必须显式提供；维度与地形映射使用内容 ID，不依赖 Minecraft 类。
        ProtoWire.validate(config);
        ProtoWire.require(config.getRulesVersion() == 1, Environment.GENERATION_VERSION);
        new DefinitionId(config.getDimensionId());
        validateRange(config.getUnclassifiedConcentration());
        validateRange(config.getBoundaryTransmission());
        ProtoWire.require(config.getPreferenceJitter() < 1, Environment.GENERATION_JITTER);

        Set<String> profileIds = new HashSet<>();
        Set<String> biomeIds = new HashSet<>();
        for (var profile : config.getProfilesList()) {
            new DefinitionId(profile.getId());
            ProtoWire.require(profileIds.add(profile.getId()), Environment.DUPLICATE_TERRAIN);
            validateRange(profile.getConcentration());
            validateWeights(profile.getWeights());
            for (String biome : profile.getBiomeIdsList()) {
                new DefinitionId(biome);
                ProtoWire.require(biomeIds.add(biome), Environment.DUPLICATE_TERRAIN);
            }
        }
    }

    public static byte[] encodeRegionRequest(TerrainQiRegionRequest request) {
        // 发布前检查完整身份与正体积，零体积不能参与浓度计算。
        validateRegionRequest(request);
        return ProtoWire.write(request);
    }

    public static TerrainQiRegionRequest decodeRegionRequest(byte[] payload) {
        // 不可信字节先经过规模限制，再验证可信宿主需要提供的事实结构。
        var request = ProtoWire.read(payload, TerrainQiRegionRequest.parser());
        validateRegionRequest(request);
        return request;
    }

    public static void validateRegionRequest(TerrainQiRegionRequest request) {
        // source_id 是宿主稳定区域键，允许包含负坐标；维度和群系仍采用内容 ID。
        ProtoWire.validate(request);
        ProtoWire.require(request.getProtocolVersion() == 1, Environment.SPACE_VERSION);
        new DefinitionId(request.getDimensionId());
        new DefinitionId(request.getBiomeId());
        ProtoWire.require(request.getVolumeBlocks() > 0, Environment.SPACE_VOLUME);
    }

    public static byte[] encodeBoundaryRequest(TerrainQiBoundaryRequest request) {
        // 两个端点描述同一条共享边界，不允许自连接。
        validateBoundaryRequest(request);
        return ProtoWire.write(request);
    }

    public static TerrainQiBoundaryRequest decodeBoundaryRequest(byte[] payload) {
        // 与本地生成使用相同身份、面积和版本校验。
        var request = ProtoWire.read(payload, TerrainQiBoundaryRequest.parser());
        validateBoundaryRequest(request);
        return request;
    }

    public static void validateBoundaryRequest(TerrainQiBoundaryRequest request) {
        // rules_revision 是宿主的安装世代，0 可用，但必须显式存在。
        ProtoWire.validate(request);
        ProtoWire.require(request.getProtocolVersion() == 1, Environment.SPACE_VERSION);
        new DefinitionId(request.getDimensionId());
        ProtoWire.require(!request.getFirstSourceId().equals(request.getSecondSourceId()),
                Environment.SAME_BOUNDARY_SOURCE);
        ProtoWire.require(request.getAreaBlocks() > 0, Environment.PATCH_AREA);
    }

    public static byte[] encodeSeed(TerrainQiRegionSeed seed) {
        // 初始库存候选也必须守住总浓度与五行分配的一致性。
        validateSeed(seed);
        return ProtoWire.write(seed);
    }

    public static TerrainQiRegionSeed decodeSeed(byte[] payload) {
        // 恢复生成记录不会向环境重新发放初始真元。
        var seed = ProtoWire.read(payload, TerrainQiRegionSeed.parser());
        validateSeed(seed);
        return seed;
    }

    public static void validateSeed(TerrainQiRegionSeed seed) {
        // 先恢复结构，再从唯一明细计算总量；不信任另一份可写的浓度或总额。
        ProtoWire.validate(seed);
        validateRegionRequest(seed.getRequest());
        ProtoWire.require(seed.getRulesVersion() == 1, Environment.GENERATION_VERSION);
        validateRange(seed.getConcentration());
        validateWeights(seed.getWeights());
        QiInventory inventory = BodyProtobuf.fromInventory(seed.getInitialQi());
        double totalQi = inventory.totalQi();
        double concentration = totalQi / seed.getRequest().getVolumeBlocks();
        double lower = seed.getConcentration().getMinimum();
        double upper = seed.getConcentration().getMaximum();
        // 乘体积再除回可能有末位舍入，仅容许少量 ULP，不放宽实际数值区间。
        ProtoWire.require(Double.isFinite(concentration) && concentration >= lower - 8 * Math.ulp(lower)
                && concentration <= upper + 8 * Math.ulp(upper), Environment.GENERATION_SEED);
        if (totalQi == 0) {
            // 空库存只能由包含零的区间产生，不能让极小正下界被舍入容差吞掉。
            ProtoWire.require(lower == 0, Environment.GENERATION_SEED);
            return;
        }

        List<Double> weights = weights(seed.getWeights());
        double totalWeight = weights.stream().mapToDouble(Double::doubleValue).sum();
        ProtoWire.require(inventory.entries().size() == 5, Environment.GENERATION_SEED);
        for (Qi entry : inventory.entries()) {
            int index = ELEMENT_IDS.indexOf(entry.attribute().id().value());
            ProtoWire.require(index >= 0 && entry.phase() == Qi.QiPhase.FREE, Environment.GENERATION_SEED);
            double expectedAmount = totalQi * (weights.get(index) / totalWeight);
            ProtoWire.require(Math.abs(entry.amount() - expectedAmount) <= 16 * Math.ulp(totalQi),
                    Environment.GENERATION_SEED);
        }
    }

    public static List<Double> weights(FiveElementWeights weights) {
        // 固定顺序是算法和 TOML 的共同合同，不能依赖消息字段遍历或哈希表顺序。
        return List.of(weights.getMetal(), weights.getWood(), weights.getWater(), weights.getFire(), weights.getEarth());
    }

    private static void validateRange(EnvironmentNumericRange range) {
        // 有限非负已由通用边界检查，端点相等可表达固定值。
        ProtoWire.require(range.getMinimum() <= range.getMaximum(), Environment.GENERATION_RANGE);
    }

    private static void validateWeights(FiveElementWeights value) {
        // 初始环境始终拥有五行来源；偏好改变占比而不把其余属性彻底删除。
        List<Double> weights = weights(value);
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        ProtoWire.require(weights.stream().allMatch(weight -> weight > 0) && Double.isFinite(total),
                Environment.GENERATION_WEIGHTS);
    }
}
