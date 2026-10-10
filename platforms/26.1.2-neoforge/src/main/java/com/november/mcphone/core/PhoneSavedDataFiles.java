package com.november.mcphone.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** 26.1 的命名空间目录迁移：复制旧文件、保留原件，有冲突或写入失败就停止。 */
final class PhoneSavedDataFiles {
    private PhoneSavedDataFiles() {}

    static Path current(Path dataDirectory, String name) {
        if (!name.matches("[a-z0-9_]+")) throw new IllegalArgumentException("存档名称不合法：" + name);
        return dataDirectory.resolve("mcphone").resolve(name + ".dat");
    }

    static synchronized Path prepare(Path dataDirectory, String name) throws IOException {
        Path destination = current(dataDirectory, name);
        // 已有新记录就只认它，不能用遗留副本覆盖已删除消息等较新的状态。
        if (Files.exists(destination)) return destination;
        List<Path> candidates = List.of(dataDirectory.resolve(name + ".dat"),
                dataDirectory.resolve("minecraft").resolve(name + ".dat"));
        Path source = null;
        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) continue;
            if (source != null && Files.mismatch(source, candidate) != -1) {
                throw new IOException("两份遗留 MCphone 存档内容不同，拒绝猜测：" + source + "、" + candidate);
            }
            source = candidate;
        }
        if (source == null) return destination;
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), name + "-migration-", ".tmp");
        try {
            Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (Files.mismatch(source, temporary) != -1) throw new IOException("迁移副本与原件不一致：" + source);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            // 同目录移动，不使用 REPLACE_EXISTING：出现已有目标时失败，不能覆盖新数据。
            Files.move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return destination;
    }
}
