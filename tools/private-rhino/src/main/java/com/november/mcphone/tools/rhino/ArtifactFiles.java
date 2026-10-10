package com.november.mcphone.tools.rhino;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 只处理构件：重复条目拒绝、排序及时间固定，不写入机器路径和当前时间。 */
final class ArtifactFiles {
    private ArtifactFiles() {}
    static Map<String, byte[]> read(Path path) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var names = zip.entries();
            while (names.hasMoreElements()) {
                var entry = names.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\"))
                    throw new IOException("不安全的构件路径：" + name);
                try (var input = zip.getInputStream(entry)) {
                    if (entries.put(name, input.readAllBytes()) != null)
                        throw new IOException("重复构件条目：" + name);
                }
            }
        }
        return entries;
    }
    static void write(Path path, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                var zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                output.putNextEntry(zipEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
    static String sha(Path path) throws IOException { return sha(Files.readAllBytes(path)); }
}
