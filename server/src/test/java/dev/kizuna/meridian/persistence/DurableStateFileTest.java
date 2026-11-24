package dev.kizuna.meridian.persistence;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public final class DurableStateFileTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void atomicWriteKeepsPayloadAndRemovesFixedTemporaryFile() throws Exception {
        // 成功提交后目标文件只有调用方载荷，固定临时文件不会残留。
        Path file = temporaryFolder.newFile("state.dat").toPath();
        byte[] payload = "gzip nbt payload".getBytes(StandardCharsets.UTF_8);

        DurableStateFile.write(file, output -> output.write(payload), DurableStateFile.DirectorySync.UNSUPPORTED);

        assertArrayEquals(payload, Files.readAllBytes(file));
        assertFalse(Files.exists(fixedTemporaryPath(file)));
    }

    @Test
    public void failedWritePreservesOldFileAndCleansFixedTemporaryFile() throws Exception {
        // 写入阶段失败时旧文件逐字节不变，固定临时文件会被清理。
        Path file = temporaryFolder.newFile("state.dat").toPath();
        byte[] previous = "old payload".getBytes(StandardCharsets.UTF_8);
        DurableStateFile.write(file, output -> output.write(previous), DurableStateFile.DirectorySync.UNSUPPORTED);
        byte[] before = Files.readAllBytes(file);

        assertThrows(IOException.class, () -> DurableStateFile.write(file, output -> {
            output.write("partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("simulated interruption");
        }, DurableStateFile.DirectorySync.UNSUPPORTED));

        assertArrayEquals(before, Files.readAllBytes(file));
        assertFalse(Files.exists(fixedTemporaryPath(file)));
    }

    @Test
    public void writerMayCloseProvidedStreamBeforeCommit() throws Exception {
        // NBT 压缩写入器可能关闭传入流，框架仍须完成临时文件同步和替换。
        Path file = temporaryFolder.newFile("state.dat").toPath();
        byte[] payload = "closed writer payload".getBytes(StandardCharsets.UTF_8);

        DurableStateFile.write(file, output -> {
            output.write(payload);
            output.close();
        }, DurableStateFile.DirectorySync.UNSUPPORTED);

        assertArrayEquals(payload, Files.readAllBytes(file));
    }

    @Test
    public void fileSyncFailurePreservesCommittedPayload() throws Exception {
        // 写入器关流后移走临时文件，模拟 force 无法打开它；平台只可跳过目录同步。
        Path target = temporaryFolder.newFile("state.dat").toPath();
        Files.writeString(target, "旧状态");
        Path temporary = fixedTemporaryPath(target);
        assertThrows(IOException.class, () -> DurableStateFile.write(target, output -> {
            output.close();
            Files.delete(temporary);
        }, DurableStateFile.DirectorySync.UNSUPPORTED));
        assertEquals("旧状态", Files.readString(target));
    }

    @Test
    public void committedFileKeepsDefaultPosixPermissions() throws Exception {
        // 固定同目录临时文件沿用创建文件的权限，避免原子替换后意外变成 600。
        Path directory = temporaryFolder.getRoot().toPath();
        PosixFileAttributeView view = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
        Assume.assumeNotNull(view);

        Path baseline = directory.resolve("permissions-baseline.dat");
        Files.writeString(baseline, "baseline", StandardCharsets.UTF_8);
        Set<PosixFilePermission> expected = Files.getPosixFilePermissions(baseline);
        Files.delete(baseline);
        Path file = directory.resolve("state.dat");

        DurableStateFile.write(file, output -> output.write("payload".getBytes(StandardCharsets.UTF_8)),
                DurableStateFile.DirectorySync.UNSUPPORTED);

        assertEquals(expected, Files.getPosixFilePermissions(file));
    }

    @Test
    public void windowsDirectorySyncCanWriteWithoutDirectoryChannel() throws Exception {
        // Windows 不支持目录通道时仍完成文件原子替换。
        Path file = temporaryFolder.newFile("state.dat").toPath();

        DurableStateFile.write(file, OutputStream::close, DurableStateFile.directorySync("Windows 11"));

        assertEquals(0L, Files.size(file));
    }

    private static Path fixedTemporaryPath(Path file) {
        return file.toAbsolutePath().resolveSibling(file.getFileName() + ".tmp");
    }
}
