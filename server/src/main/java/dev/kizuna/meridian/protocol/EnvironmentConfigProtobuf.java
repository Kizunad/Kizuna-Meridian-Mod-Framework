package dev.kizuna.meridian.protocol;

import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiConfig;

import java.util.HashMap;
import java.util.Map;

/** 环境配置使用同一 protoc 契约交给 Java 或其他语言宿主。 */
public final class EnvironmentConfigProtobuf {
    private EnvironmentConfigProtobuf() {
        // 配置校验没有运行状态，加载文件和跨语言接入共用这个入口。
    }

    public static byte[] encode(EnvironmentQiConfig config) {
        // 只交换已经补齐默认值的有效配置，避免各宿主自行猜测缺省值。
        validate(config);
        return ProtoWire.write(config);
    }

    public static EnvironmentQiConfig decode(byte[] payload) {
        // 有界解析后复用业务约束，第三方宿主不能绕过空气基准和区域尺寸校验。
        EnvironmentQiConfig config = ProtoWire.read(payload, EnvironmentQiConfig.parser());
        validate(config);
        return config;
    }

    public static void validate(EnvironmentQiConfig config) {
        // 通用边界检查存在性、未知字段和有限非负数；这里检查配置自身关系。
        ProtoWire.validate(config);
        ProtoWire.require(config.getConfigVersion() == 2, Environment.CONFIG_VERSION);
        ProtoWire.require(config.getRegionSizeChunks() > 0, Environment.REGION_SIZE);
        ProtoWire.require(config.getDefaultBlockTransmission() < 1, Environment.DEFAULT_TRANSMISSION);

        Map<String, Double> transmissions = new HashMap<>();
        for (var block : config.getBlockTransmissionsList()) {
            new DefinitionId(block.getBlockId());
            Double previous = transmissions.putIfAbsent(block.getBlockId(), block.getTransmission());
            ProtoWire.require(previous == null, Environment.DUPLICATE_BLOCK);
        }

        // void 指无介质空间，在方块配置中明确使用 void_air，不能与 structure_void 混用。
        for (String air : new String[] {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}) {
            ProtoWire.require(Double.valueOf(1).equals(transmissions.get(air)), Environment.AIR_TRANSMISSION);
        }
        TerrainQiProtobuf.validateConfig(config.getGeneration());
    }
}
