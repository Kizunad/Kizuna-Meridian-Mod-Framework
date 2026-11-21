package dev.kizuna.meridian.config;

import dev.kizuna.meridian.environment.EnvironmentConfigLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class TomlFileTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void firstReadExportsDefaultsAndParsesTheCreatedFile() throws Exception {
        // 新路径导出资源默认值，后续再次读取不覆盖文件内容。
        Path file = temporaryFolder.getRoot().toPath().resolve("nested/environment.toml");
        TomlTable parsed = TomlFile.readOrExportAndParse(file, EnvironmentConfigLoader.class,
                EnvironmentConfigLoader.DEFAULT_RESOURCE);
        String exported = Files.readString(file);
        assertTrue(parsed.contains("config_version"));
        assertEquals(exported, TomlFile.readOrExport(file, EnvironmentConfigLoader.class,
                EnvironmentConfigLoader.DEFAULT_RESOURCE));
    }

    @Test
    public void corruptedFilesAndUnknownKeysReportFileAndKey() throws Exception {
        // 语法损坏与键名拼错都拒绝启动，并提供文件路径及具体键诊断。
        Path file = temporaryFolder.newFile("broken.toml").toPath();
        Files.writeString(file, "[broken");
        IllegalArgumentException parseFailure = assertThrows(IllegalArgumentException.class,
                () -> TomlFile.readOrExportAndParse(file, EnvironmentConfigLoader.class,
                        EnvironmentConfigLoader.DEFAULT_RESOURCE));
        assertTrue(parseFailure.getMessage().contains(file.toString()));

        TomlTable parsed = TomlFile.parse("wrong_key = 1", file.toString());
        IllegalArgumentException keyFailure = assertThrows(IllegalArgumentException.class,
                () -> TomlFile.checkKeys(parsed, Set.of(), file.toString(), ""));
        assertTrue(keyFailure.getMessage().contains(file.toString()));
        assertTrue(keyFailure.getMessage().contains("wrong_key"));
    }

    @Test
    public void parseUsesCallerProvidedByteLimit() throws Exception {
        // 自定义上限同时放宽内存文本解析，默认入口仍保持默认限制。
        int customMaxBytes = TomlFile.DEFAULT_MAX_BYTES + 32;
        int valueLength = TomlFile.DEFAULT_MAX_BYTES - 2;
        String text = "value = \"" + "a".repeat(valueLength) + "\"";
        Path file = temporaryFolder.getRoot().toPath().resolve("large.toml");
        Files.writeString(file, text);

        assertThrows(IllegalArgumentException.class, () -> TomlFile.parse(text, file.toString()));
        assertThrows(IOException.class, () -> TomlFile.readAndParse(file));
        TomlTable parsed = TomlFile.parse(text, file.toString(), customMaxBytes);
        TomlTable parsedFile = TomlFile.readAndParse(file, customMaxBytes);

        assertEquals("a".repeat(valueLength), parsed.getString("value"));
        assertEquals(parsed.getString("value"), parsedFile.getString("value"));
    }
}
