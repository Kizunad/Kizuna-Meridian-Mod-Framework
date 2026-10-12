package dev.kizuna.meridian.environment;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Environment;
import dev.kizuna.meridian.protocol.EnvironmentConfigProtobuf;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.BlockQiTransmission;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiConfig;
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
        checkKeys(root, "", Set.of("config_version", "region", "absorption", "diffusion"));
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
        Object value = table.get(List.of(key));
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
