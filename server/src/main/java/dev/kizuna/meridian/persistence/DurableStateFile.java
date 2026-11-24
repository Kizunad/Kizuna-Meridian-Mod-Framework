package dev.kizuna.meridian.persistence;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** 提供同目录原子文件提交，业务层决定载荷格式和版本表示。 */
public final class DurableStateFile {
    public enum DirectorySync { REQUIRED, UNSUPPORTED }

    @FunctionalInterface
    public interface PayloadWriter {
        /** 写入器可以关闭传入流，关闭后的临时文件仍由框架负责同步和提交。 */
        void write(OutputStream output) throws IOException;
    }

    private DurableStateFile() {
        // 文件提交不持有状态，调用方仅在成功返回后发布自己的确认索引。
    }

    public static DirectorySync directorySync(String operatingSystem) {
        // Windows 仍强制同步文件内容，只跳过不支持的目录通道。
        Objects.requireNonNull(operatingSystem, "操作系统名称不能为空");
        if (operatingSystem.startsWith("Windows")) {
            return DirectorySync.UNSUPPORTED;
        }
        return DirectorySync.REQUIRED;
    }

    public static void write(Path file, PayloadWriter writer, DirectorySync directorySync) throws IOException {
        // 临时文件完整同步后才替换目标，编码或同步失败不会覆盖旧文件。
        Objects.requireNonNull(file, "持久化文件不能为空");
        Objects.requireNonNull(writer, "持久化写入器不能为空");
        Objects.requireNonNull(directorySync, "目录同步能力不能为空");

        Path target = file.toAbsolutePath();
        Path directory = target.getParent();
        if (directory == null) {
            throw new IOException("持久化文件没有父目录：" + file);
        }
        Files.createDirectories(directory);
        syncDirectory(directory.getParent(), directorySync);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        // 打开成功后才接管临时文件；路径被目录占用时，保留该目录及原始错误。
        var output = new BufferedOutputStream(Files.newOutputStream(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        try {
            try (output) {
                writer.write(output);
            }
            force(temporary, StandardOpenOption.WRITE);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(directory, directorySync);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void syncDirectory(Path directory, DirectorySync directorySync) throws IOException {
        // 文件同步始终发生，平台标记只控制目录通道。
        if (directorySync == DirectorySync.REQUIRED && directory != null) {
            force(directory, StandardOpenOption.READ);
        }
    }

    private static void force(Path path, StandardOpenOption option) throws IOException {
        // 刷盘错误传回宿主，不能在未同步时确认保存。
        try (FileChannel channel = FileChannel.open(path, option)) {
            channel.force(true /* metaData */);
        }
    }

}
