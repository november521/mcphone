package com.november.mcphone.feature.chat.group;

import com.november.mcphone.core.PhonePaths;
import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatImage;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** 群像素仓独立命名空间；后台 IO 与回收共用锁，正在提交的上传不能被当成孤儿删掉。 */
public final class GroupImageFiles {
    private GroupImageFiles() {}
    private static final Object LOCK = new Object();
    private static final WeakHashMap<MinecraftServer, Map<UUID, Integer>> ACTIVE = new WeakHashMap<>();
    private static final WeakHashMap<MinecraftServer, Boolean> SWEPT = new WeakHashMap<>();
    private static Path folder(MinecraftServer server) { return PhonePaths.WORLD_GROUP_IMAGES.in(server.getWorldPath(LevelResource.ROOT)); }
    private static Path file(MinecraftServer server, UUID id) { return folder(server).resolve(id + ".png"); }
    public static UUID identity(UUID group, byte[] data) {
        try {
            var hash = MessageDigest.getInstance("SHA-256"); hash.update(("group:" + group).getBytes(StandardCharsets.UTF_8));
            var bytes = ByteBuffer.wrap(hash.digest(data)); return new UUID(bytes.getLong(), bytes.getLong());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static UUID write(MinecraftServer server, UUID group, byte[] data) {
        UUID id = identity(group, data);
        synchronized (LOCK) {
            ACTIVE.computeIfAbsent(server, ignored -> new HashMap<>()).merge(id, 1, Integer::sum);
            try {
                Path path = file(server, id);
                GroupImageFileIO.write(path, data);
                Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
                return id;
            } catch (IOException error) {
                release(server, id); MCphone.LOGGER.warn("群图片写入失败", error); return null;
            }
        }
    }
    public static void release(MinecraftServer server, UUID id) {
        synchronized (LOCK) {
            var active = ACTIVE.get(server);
            if (active != null) active.computeIfPresent(id, (key, count) -> count > 1 ? count - 1 : null);
        }
    }
    public static byte[] read(MinecraftServer server, UUID id) {
        synchronized (LOCK) {
            try { var path = file(server, id); return Files.isRegularFile(path) && Files.size(path) <= ChatImage.MAX_BYTES_CEILING ? Files.readAllBytes(path) : null; }
            catch (IOException error) { MCphone.LOGGER.warn("群图片读取失败", error); return null; }
        }
    }
    public static void clean(MinecraftServer server, Set<UUID> candidates, Set<UUID> referenced) {
        long before = System.currentTimeMillis();
        Util.backgroundExecutor().execute(() -> {
            for (UUID id : candidates) if (!referenced.contains(id)) remove(server, id, before);
        });
    }
    private static void remove(MinecraftServer server, UUID id, long before) {
        synchronized (LOCK) {
            if (ACTIVE.getOrDefault(server, Map.of()).containsKey(id)) return;
            try {
                Path path = file(server, id);
                // 新一次上传会更新修改时间；回收任务不能按旧引用快照删掉后来被复用的文件。
                if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() < before) Files.deleteIfExists(path);
            } catch (IOException error) { MCphone.LOGGER.warn("群图片回收失败", error); }
        }
    }
    public static void sweepOnce(MinecraftServer server) {
        synchronized (LOCK) { if (SWEPT.putIfAbsent(server, true) != null) return; }
        Set<UUID> referenced = GroupService.imageReferences(server); long before = System.currentTimeMillis();
        Util.backgroundExecutor().execute(() -> {
            Path folder = folder(server); if (!Files.isDirectory(folder)) return;
            try (var paths = Files.list(folder)) {
                for (var path : paths.toList()) {
                    String name = path.getFileName().toString(); if (!name.endsWith(".png")) continue;
                    try { UUID id = UUID.fromString(name.substring(0, name.length() - 4)); if (!referenced.contains(id)) remove(server, id, before); }
                    catch (IllegalArgumentException ignored) { }
                }
            } catch (IOException error) { MCphone.LOGGER.warn("群图片孤儿扫描失败", error); }
        });
    }
}
