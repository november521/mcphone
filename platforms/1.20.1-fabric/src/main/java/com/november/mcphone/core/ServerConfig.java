package com.november.mcphone.core;

import com.google.gson.GsonBuilder;
import com.november.mcphone.MCphone;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

public final class ServerConfig {
    public record Values(boolean allowFriendTeleport, boolean allowChatImages, int chatImageMaxKb,
                         boolean terminalKeepPowered) {}
    private static final Values DEFAULTS = new Values(true, true, 512, true);
    private static volatile Values server = DEFAULTS;
    private static volatile Values client = DEFAULTS;

    private ServerConfig() {}

    public static Values server() { return server; }
    public static boolean allowFriendTeleport() { return server.allowFriendTeleport(); }
    public static boolean allowChatImages() { return server.allowChatImages(); }
    public static int chatImageMaxBytes() { return server.chatImageMaxKb() * 1024; }
    public static boolean terminalKeepPowered() { return server.terminalKeepPowered(); }
    public static boolean clientAllowFriendTeleport() { return client.allowFriendTeleport(); }
    public static boolean clientAllowChatImages() { return client.allowChatImages(); }
    public static int clientChatImageMaxBytes() { return client.chatImageMaxKb() * 1024; }

    public static void clearServer() { server = DEFAULTS; }
    public static void clearSync() { client = DEFAULTS; }
    public static void applySync(boolean teleport, boolean images, int maxKb) {
        client = new Values(teleport, images, clampKb(maxKb), true);
    }

    public static void load(MinecraftServer instance) {
        load(instance.getWorldPath(LevelResource.ROOT));
    }

    static void load(Path world) {
        server = DEFAULTS;
        var file = world.resolve("serverconfig/mcphone-server.json");
        var gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, gson.toJson(DEFAULTS));
                return;
            }
            var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            server = new Values(
                    json.has("allowFriendTeleport") ? json.get("allowFriendTeleport").getAsBoolean() : DEFAULTS.allowFriendTeleport(),
                    json.has("allowChatImages") ? json.get("allowChatImages").getAsBoolean() : DEFAULTS.allowChatImages(),
                    json.has("chatImageMaxKb") ? clampKb(json.get("chatImageMaxKb").getAsInt()) : DEFAULTS.chatImageMaxKb(),
                    json.has("terminalKeepPowered") ? json.get("terminalKeepPowered").getAsBoolean() : DEFAULTS.terminalKeepPowered());
        } catch (IOException | RuntimeException failure) {
            MCphone.LOGGER.error("Unable to load MCphone server config at {}", file, failure);
        }
    }

    private static int clampKb(int value) {
        return Math.max(64, Math.min(768, value));
    }
}
