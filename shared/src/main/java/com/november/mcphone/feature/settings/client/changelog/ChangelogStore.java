package com.november.mcphone.feature.settings.client.changelog;

import com.november.mcphone.core.PhonePaths;
import com.november.mcphone.core.client.AtomicWrite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** 每个本机账号、世界/服务器一个小文件，只保存上次展示版本。 */
public final class ChangelogStore {
    private ChangelogStore() {}
    public static Path path(Path directory, UUID account, String scope) {
        try {
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(scope.getBytes(StandardCharsets.UTF_8)));
            return PhonePaths.CHANGELOG.in(directory).resolve(account.toString()).resolve(key + ".txt");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String read(Path file) throws IOException {
        if (!Files.exists(file)) return "";
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(256);
            return new String(bytes, StandardCharsets.UTF_8).trim();
        }
    }
    public static void shown(Path file, String version) throws IOException {
        AtomicWrite.replace(file, writer -> writer.write(version));
    }
}
