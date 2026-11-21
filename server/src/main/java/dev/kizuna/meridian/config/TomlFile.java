package dev.kizuna.meridian.config;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 公共 TOML 文件读取与基础类型校验，具体配置结构由各附属定义。 */
public final class TomlFile {
    public static final int DEFAULT_MAX_BYTES = 262_144;

    private TomlFile() {
        // TOML 读取不持有配置状态，调用方在解析后构造自己的不可变配置。
    }

    public static String readOrExport(Path path, Class<?> resourceOwner, String resourceName) {
        // 首次读取时导出随包默认值，已有文件始终优先。
        return readOrExport(path, resourceOwner, resourceName, DEFAULT_MAX_BYTES);
    }

    public static String readOrExport(Path path, Class<?> resourceOwner, String resourceName, int maxBytes) {
        // 资源和外部文件共用字节上限与严格 UTF-8 解码。
        Objects.requireNonNull(path, "TOML 配置路径不能为空");
        Objects.requireNonNull(resourceOwner, "TOML 默认资源所属类不能为空");
        Objects.requireNonNull(resourceName, "TOML 默认资源名不能为空");
        checkLimit(maxBytes);
        try {
            if (!Files.exists(path)) {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String defaults = readResource(resourceOwner, resourceName, maxBytes);
                try {
                    Files.writeString(path, defaults, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                    return defaults;
                } catch (FileAlreadyExistsException concurrentCreate) {
                    return read(path, maxBytes);
                }
            }
            return read(path, maxBytes);
        } catch (IOException failure) {
            throw invalid(path.toString(), "读取失败", failure);
        }
    }

    public static String read(Path path) throws IOException {
        // 默认读取上限与首次导出的上限一致。
        return read(path, DEFAULT_MAX_BYTES);
    }

    public static String read(Path path, int maxBytes) throws IOException {
        // 限制实际读取字节数，再拒绝非法 UTF-8。
        Objects.requireNonNull(path, "TOML 配置路径不能为空");
        checkLimit(maxBytes);
        byte[] bytes = readLimited(Files.newInputStream(path), maxBytes);
        return decode(path.toString(), bytes);
    }

    public static String resource(Class<?> resourceOwner, String resourceName) {
        // 显式资源读取也使用统一的默认上限。
        return resource(resourceOwner, resourceName, DEFAULT_MAX_BYTES);
    }

    public static String resource(Class<?> resourceOwner, String resourceName, int maxBytes) {
        // 供调用方解析内置配置而不创建外部文件。
        Objects.requireNonNull(resourceOwner, "TOML 默认资源所属类不能为空");
        Objects.requireNonNull(resourceName, "TOML 默认资源名不能为空");
        checkLimit(maxBytes);
        return readResource(resourceOwner, resourceName, maxBytes);
    }

    public static TomlTable readAndParse(Path path) throws IOException {
        // 文件读取与 TOML 语法错误都保留来源文件名。
        return readAndParse(path, DEFAULT_MAX_BYTES);
    }

    public static TomlTable readAndParse(Path path, int maxBytes) throws IOException {
        // 读取和解析使用同一字节上限，避免二次解析缩小调用方的限制。
        return parse(read(path, maxBytes), path.toString(), maxBytes);
    }

    public static TomlTable readOrExportAndParse(Path path, Class<?> resourceOwner, String resourceName) {
        // 首次加载创建默认文件，后续加载损坏文件时直接失败。
        return readOrExportAndParse(path, resourceOwner, resourceName, DEFAULT_MAX_BYTES);
    }

    public static TomlTable readOrExportAndParse(Path path, Class<?> resourceOwner, String resourceName,
                                                  int maxBytes) {
        // 读取、默认资源导出和解析使用同一字节上限。
        return parse(readOrExport(path, resourceOwner, resourceName, maxBytes), path.toString(), maxBytes);
    }

    public static TomlTable parse(String text, String fileName) {
        // 语法错误带解析器的行列信息，空配置不能静默变成空表。
        return parse(text, fileName, DEFAULT_MAX_BYTES);
    }

    public static TomlTable parse(String text, String fileName, int maxBytes) {
        // 解析上限跟随调用方，保持内存文本和文件读取的约束一致。
        Objects.requireNonNull(fileName, "TOML 文件名不能为空");
        checkLimit(maxBytes);
        if (text == null || text.isBlank()) {
            throw invalid(fileName, "文本不能为空", null);
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw invalid(fileName, "文本超过 " + maxBytes + " 字节上限", null);
        }
        TomlParseResult parsed = Toml.parse(text);
        if (parsed.hasErrors()) {
            throw invalid(fileName, "解析失败：" + parsed.errors().get(0), null);
        }
        return parsed;
    }

    public static TomlTable table(TomlTable parent, String key, String fileName, String path) {
        // 必需章节缺失或类型错误时报告原始键路径。
        Objects.requireNonNull(parent, "TOML 父表不能为空");
        Object value = parent.get(List.of(key));
        if (!(value instanceof TomlTable table)) {
            throw invalid(fileName, "缺少或错误的表 " + path, null);
        }
        return table;
    }

    public static int positiveInteger(TomlTable table, String key, String fileName, String path) {
        // 只接受可表示的正 int32，禁止小数截断。
        Object value = table.get(List.of(key));
        if (!(value instanceof Long integer) || integer <= 0 || integer > Integer.MAX_VALUE) {
            throw invalid(fileName, path + " 必须是正 int32 整数", null);
        }
        return integer.intValue();
    }

    public static double finiteNumber(TomlTable table, String key, String fileName, String path) {
        // TOML 整数与小数统一读取为有限 double。
        Object value = table.get(List.of(key));
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw invalid(fileName, path + " 必须是有限数值", null);
    }

    public static void checkKeys(TomlTable table, Set<String> allowed, String fileName, String prefix) {
        // 未知键会导致配置拒绝，防止拼写错误静默套用默认值。
        for (String key : table.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(fileName, "未知字段 " + prefix + key, null);
            }
        }
    }

    public static IllegalArgumentException invalid(String fileName, String detail) {
        // 允许配置专用解析器沿用同一种文件名错误格式。
        return invalid(fileName, detail, null);
    }

    private static IllegalArgumentException invalid(String fileName, String detail, Throwable cause) {
        // 保留 I/O 原因链，消息中统一包含配置来源。
        String message = "TOML 文件 " + fileName + "：" + detail;
        if (cause == null) {
            return new IllegalArgumentException(message);
        }
        return new IllegalArgumentException(message, cause);
    }

    private static String readResource(Class<?> resourceOwner, String resourceName, int maxBytes) {
        // 默认资源不存在属于配置发布错误，不能生成空配置。
        try (InputStream input = resourceOwner.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw invalid(resourceName, "缺少默认资源");
            }
            return decode(resourceName, readLimited(input, maxBytes));
        } catch (IOException failure) {
            throw invalid(resourceName, "读取默认资源失败", failure);
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        // 多读一个字节即可检测超限，不将任意大的文件载入内存。
        try (InputStream source = input) {
            byte[] bytes = source.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException("TOML 文件超过大小上限");
            }
            return bytes;
        }
    }

    private static String decode(String fileName, byte[] bytes) {
        // 标准 UTF-8 解码器默认会替换坏字节，这里改为显式拒绝。
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw invalid(fileName, "不是有效的 UTF-8 文本", failure);
        }
    }

    private static void checkLimit(int maxBytes) {
        // 无效上限属于调用错误，不伪装成配置文件损坏。
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("TOML 文件大小上限必须为正数");
        }
    }
}
