package dev.kizuna.meridian.environment;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.EnvironmentConfigProtobuf;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.BlockQiTransmission;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiConfig;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentNumericRange;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.FiveElementWeights;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.TerrainQiProfile;
import org.tomlj.TomlArray;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 启动时读取宿主指定的 TOML；输出完整 Protobuf 配置，不持有世界状态。 */
public final class EnvironmentConfigLoader {
    public static final int MAX_CONFIG_BYTES = 262_144;
    public static final String DEFAULT_RESOURCE = "/meridian-environment.toml";

    private EnvironmentConfigLoader() {
        // 加载器只构造不可变消息，由宿主决定何时安装到环境运行实例。
    }

    public static EnvironmentQiConfig load(Path path) {
        // 显式指定的文件读不到就报错，避免服务器以意外的默认规则启动。
        MeridianErrors.required(path, Environment.CONFIG_PATH_REQUIRED);
        try (InputStream input = Files.newInputStream(path)) {
            return parse(readText(input));
        } catch (IOException exception) {
            throw MeridianErrors.invalid(Environment.readFailed(path), exception);
        }
    }

    public static EnvironmentQiConfig defaults() {
        // JAR 内的 TOML 是默认参数唯一来源，用户可以复制该资源后自行覆盖。
        try (InputStream input = EnvironmentConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) {
                throw MeridianErrors.inconsistent(Environment.DEFAULT_RESOURCE);
            }
            var builder = EnvironmentQiConfig.newBuilder();
            apply(parseTable(readText(input)), builder);
            EnvironmentQiConfig config = builder.build();
            EnvironmentConfigProtobuf.validate(config);
            return config;
        } catch (IOException exception) {
            throw MeridianErrors.invalid(Environment.readFailed(DEFAULT_RESOURCE), exception);
        }
    }

    public static EnvironmentQiConfig parse(String text) {
        // 配置文件允许只写覆盖项；发布到宿主边界前补齐全部默认值并统一校验。
        var builder = defaults().toBuilder();
        apply(parseTable(text), builder);
        EnvironmentQiConfig config = builder.build();
        EnvironmentConfigProtobuf.validate(config);
        return config;
    }

    private static String readText(InputStream input) throws IOException {
        // 读取上限外的一个字节检测超长文件，避免先把不受限的文件整体载入内存。
        byte[] bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
        if (bytes.length > MAX_CONFIG_BYTES) {
            throw MeridianErrors.invalid(Environment.CONFIG_TOO_LARGE);
        }
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static TomlTable parseTable(String text) {
        // 文本调用与文件调用遵守同样的 UTF-8 字节上限，解析失败保留首个行列诊断。
        MeridianErrors.required(text, Environment.CONFIG_TEXT_REQUIRED);
        if (text.length() > MAX_CONFIG_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw MeridianErrors.invalid(Environment.CONFIG_TOO_LARGE);
        }
        TomlParseResult parsed = Toml.parse(text);
        if (parsed.hasErrors()) {
            throw MeridianErrors.invalid(Environment.parseFailed(parsed.errors().get(0)));
        }
        return parsed;
    }

    private static void apply(TomlTable root, EnvironmentQiConfig.Builder builder) {
        // 按文件章节顺序覆盖，未知字段立即报错，不能让拼错的配置静默失效。
        checkKeys(root, "", Set.of("config_version", "region", "absorption", "diffusion", "generation"));
        if (root.contains("config_version")) {
            builder.setConfigVersion(integer(root, "config_version", "config_version"));
        }

        TomlTable region = table(root, "region", "region");
        if (region != null) {
            checkKeys(region, "region.", Set.of("size_chunks"));
            if (region.contains("size_chunks")) {
                builder.setRegionSizeChunks(integer(region, "size_chunks", "region.size_chunks"));
            }
        }

        TomlTable absorption = table(root, "absorption", "absorption");
        if (absorption != null) {
            checkKeys(absorption, "absorption.", Set.of("volume_blocks_per_second"));
            if (absorption.contains("volume_blocks_per_second")) {
                builder.setAbsorptionVolumeBlocksPerSecond(number(absorption, "volume_blocks_per_second",
                        "absorption.volume_blocks_per_second"));
            }
        }

        TomlTable diffusion = table(root, "diffusion", "diffusion");
        if (diffusion != null) {
            checkKeys(diffusion, "diffusion.", Set.of("speed_blocks_per_second", "default_block_transmission", "blocks"));
            if (diffusion.contains("speed_blocks_per_second")) {
                builder.setDiffusionSpeedBlocksPerSecond(number(diffusion, "speed_blocks_per_second",
                        "diffusion.speed_blocks_per_second"));
            }
            if (diffusion.contains("default_block_transmission")) {
                builder.setDefaultBlockTransmission(number(diffusion, "default_block_transmission",
                        "diffusion.default_block_transmission"));
            }
            TomlTable blocks = table(diffusion, "blocks", "diffusion.blocks");
            if (blocks != null) {
                applyBlocks(blocks, builder);
            }
        }

        TomlTable generation = table(root, "generation", "generation");
        if (generation != null) {
            applyGeneration(generation, builder);
        }
    }

    private static void applyGeneration(TomlTable table, EnvironmentQiConfig.Builder config) {
        // 通用范围与地形内容分开覆盖；最终发布仍由同一 Protobuf 校验入口检查。
        checkKeys(table, "generation.", Set.of("rules_version", "dimension_id", "unclassified_min",
                "unclassified_max", "boundary_min", "boundary_max", "preference_jitter", "profiles"));
        var generation = config.getGeneration().toBuilder();
        if (table.contains("rules_version")) {
            generation.setRulesVersion(integer(table, "rules_version", "generation.rules_version"));
        }
        if (table.contains("dimension_id")) {
            generation.setDimensionId(string(table.get("dimension_id"), "generation.dimension_id"));
        }
        generation.setUnclassifiedConcentration(range(table, "unclassified_", "generation.",
                generation.getUnclassifiedConcentration()));
        generation.setBoundaryTransmission(range(table, "boundary_", "generation.",
                generation.getBoundaryTransmission()));
        if (table.contains("preference_jitter")) {
            generation.setPreferenceJitter(number(table, "preference_jitter", "generation.preference_jitter"));
        }

        // 按预设 ID 合并；biomes 和 weights 是完整替换，不能让旧数组元素残留。
        TomlTable profiles = table(table, "profiles", "generation.profiles");
        if (profiles != null) {
            Map<String, TerrainQiProfile> merged = new TreeMap<>();
            generation.getProfilesList().forEach(profile -> merged.put(profile.getId(), profile));
            for (String id : profiles.keySet()) {
                String path = "generation.profiles." + id + ".";
                TomlTable definition = table(profiles, id, path);
                checkKeys(definition, path, Set.of("biomes", "concentration_min", "concentration_max", "weights"));
                var profile = merged.getOrDefault(id, TerrainQiProfile.getDefaultInstance()).toBuilder().setId(id);
                profile.setConcentration(range(definition, "concentration_", path, profile.getConcentration()));
                if (definition.contains("biomes")) {
                    TomlArray biomes = array(definition, "biomes", path + "biomes");
                    profile.clearBiomeIds();
                    for (int index = 0; index < biomes.size(); index++) {
                        profile.addBiomeIds(string(biomes.get(index), path + "biomes"));
                    }
                }
                if (definition.contains("weights")) {
                    TomlArray weights = array(definition, "weights", path + "weights");
                    if (weights.size() != 5) {
                        throw MeridianErrors.invalid(Environment.GENERATION_WEIGHTS);
                    }
                    profile.setWeights(FiveElementWeights.newBuilder()
                            .setMetal(numericValue(weights.get(0), path + "weights"))
                            .setWood(numericValue(weights.get(1), path + "weights"))
                            .setWater(numericValue(weights.get(2), path + "weights"))
                            .setFire(numericValue(weights.get(3), path + "weights"))
                            .setEarth(numericValue(weights.get(4), path + "weights")));
                }
                merged.put(id, profile.build());
            }
            generation.clearProfiles().addAllProfiles(merged.values());
        }
        config.setGeneration(generation);
    }

    private static EnvironmentNumericRange range(TomlTable table, String prefix, String path,
                                                  EnvironmentNumericRange original) {
        // 区间两端可分别覆盖，缺省端点沿用原值；新预设缺失端点会在存在性校验中拒绝。
        var range = original.toBuilder();
        if (table.contains(prefix + "min")) {
            range.setMinimum(number(table, prefix + "min", path + prefix + "min"));
        }
        if (table.contains(prefix + "max")) {
            range.setMaximum(number(table, prefix + "max", path + prefix + "max"));
        }
        return range.build();
    }

    private static TomlArray array(TomlTable table, String key, String path) {
        // 地形列表和五行权重必须是数组，拒绝把单值猜测成一个元素的列表。
        if (!(table.get(List.of(key)) instanceof TomlArray result)) {
            throw MeridianErrors.invalid(Environment.wrongType(path, "数组"));
        }
        return result;
    }

    private static String string(Object value, String path) {
        // 内容 ID 的语法在协议层统一检查，此处只拒绝 TOML 类型错误。
        if (!(value instanceof String result)) {
            throw MeridianErrors.invalid(Environment.wrongType(path, "字符串"));
        }
        return result;
    }

    private static void applyBlocks(TomlTable blocks, EnvironmentQiConfig.Builder builder) {
        // 同一 ID 覆盖内置值，未覆盖的默认项继续保留；排序让有效配置的字节输出稳定。
        Map<String, Double> transmissions = new TreeMap<>();
        for (var block : builder.getBlockTransmissionsList()) {
            transmissions.put(block.getBlockId(), block.getTransmission());
        }
        for (String blockId : blocks.keySet()) {
            transmissions.put(blockId, number(blocks, blockId, "diffusion.blocks." + blockId));
        }
        builder.clearBlockTransmissions();
        for (var entry : transmissions.entrySet()) {
            builder.addBlockTransmissions(BlockQiTransmission.newBuilder()
                    .setBlockId(entry.getKey()).setTransmission(entry.getValue()));
        }
    }

    private static TomlTable table(TomlTable parent, String key, String path) {
        // 缺失章节沿用默认值，类型错误则拒绝；不能把错误标量当成空章节。
        Object value = parent.get(List.of(key));
        if (value == null) {
            return null;
        }
        if (!(value instanceof TomlTable result)) {
            throw MeridianErrors.invalid(Environment.wrongType(path, "TOML 表"));
        }
        return result;
    }

    private static int integer(TomlTable table, String key, String path) {
        // 尺寸与版本不接受 1.5 或越界整数，也不通过强制转换截断。
        Object value = table.get(List.of(key));
        if (!(value instanceof Long integer) || integer < 1 || integer > Integer.MAX_VALUE) {
            throw MeridianErrors.invalid(Environment.wrongType(path, "正 int32 整数"));
        }
        return integer.intValue();
    }

    private static double number(TomlTable table, String key, String path) {
        // TOML 的 1 和 1.0 都可表达连续数值；有限性与非负范围由协议入口统一检查。
        return numericValue(table.get(List.of(key)), path);
    }

    private static double numericValue(Object value, String path) {
        // 表字段和权重数组共用数字读取规则，避免其中一条入口隐式接受字符串。
        if (value instanceof Long integer) {
            return integer.doubleValue();
        }
        if (value instanceof Double decimal) {
            return decimal;
        }
        throw MeridianErrors.invalid(Environment.wrongType(path, "数值"));
    }

    private static void checkKeys(TomlTable table, String prefix, Set<String> allowed) {
        // 每层单独检查，保留完整字段路径，方便定位拼错的章节或参数。
        for (String key : table.keySet()) {
            if (!allowed.contains(key)) {
                throw MeridianErrors.invalid(Environment.unknownKey(prefix + key));
            }
        }
    }
}
