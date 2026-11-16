package com.november.mcphone.core;

import java.nio.file.Files;

public final class ServerConfigLifecycleTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("mcphone-fabric-config");
        var a = directory.resolve("a/serverconfig/mcphone-server.json");
        var b = directory.resolve("b/serverconfig/mcphone-server.json");
        try {
            Files.createDirectories(a.getParent());
            Files.writeString(a, "{\"allowChatImages\":false,\"chatImageMaxKb\":128}");
            ServerConfig.load(directory.resolve("a"));
            if (ServerConfig.allowChatImages() || ServerConfig.chatImageMaxBytes() != 128 * 1024) {
                throw new AssertionError("world A configuration was ignored");
            }
            ServerConfig.applySync(true, true, 256);
            if (!ServerConfig.clientAllowChatImages() || ServerConfig.allowChatImages()
                    || ServerConfig.clientChatImageMaxBytes() != 256 * 1024) {
                throw new AssertionError("client sync changed server policy");
            }
            ServerConfig.clearSync();
            ServerConfig.load(directory.resolve("b"));
            if (!ServerConfig.allowChatImages() || ServerConfig.chatImageMaxBytes() != 512 * 1024
                    || !Files.readString(b).contains("512")) {
                throw new AssertionError("world B inherited world A configuration");
            }
        } finally {
            ServerConfig.clearSync();
            ServerConfig.clearServer();
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
            Files.deleteIfExists(a.getParent());
            Files.deleteIfExists(b.getParent());
            Files.deleteIfExists(a.getParent().getParent());
            Files.deleteIfExists(b.getParent().getParent());
            Files.deleteIfExists(directory);
        }
    }
}
