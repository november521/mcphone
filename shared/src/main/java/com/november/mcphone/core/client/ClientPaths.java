package com.november.mcphone.core.client;

import com.november.mcphone.core.PhonePaths;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** Client-only binding of the shared path table to this Minecraft instance. */
public final class ClientPaths {
    private ClientPaths() {}

    /** No client directory means no client file access, rather than using another working directory. */
    public static Path gameDirectoryOrNull() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.gameDirectory == null ? null : mc.gameDirectory.toPath();
    }

    public static Path gameDirectory() {
        Path directory = gameDirectoryOrNull();
        if (directory == null) throw new IllegalStateException("客户端游戏目录尚未初始化");
        return directory;
    }

    public static Path resolve(PhonePaths location) { return location.in(gameDirectory()); }
}
