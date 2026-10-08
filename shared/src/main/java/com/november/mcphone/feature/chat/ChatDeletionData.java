package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.PhoneSavedData;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 主世界持久化个人删除标记；读取损坏时明确失败，不把标记清空成“消息恢复”。 */
public final class ChatDeletionData extends PhoneSavedData {
    private static final Codec<Map<String, List<UUID>>> CODEC = Codec.unboundedMap(Codec.STRING, UUIDUtil.CODEC.listOf());
    private final ChatMessageVisibility visibility;
    private final CompoundTag unreadable;
    public ChatDeletionData() { this(new ChatMessageVisibility()); }
    private ChatDeletionData(ChatMessageVisibility visibility) { this.visibility = visibility; this.unreadable = null; }
    private ChatDeletionData(CompoundTag unreadable) { this.visibility = new ChatMessageVisibility(); this.unreadable = unreadable.copy(); }
    public boolean isAvailable() { return unreadable == null; }
    public static ChatDeletionData get(MinecraftServer server) {
        return getOrCreate(server, MCphone.MODID + "_chat_deletions", ChatDeletionData::new, ChatDeletionData::load);
    }
    public boolean hide(UUID owner, UUID peer, UUID message) {
        if (!isAvailable()) return false;
        boolean changed = visibility.hide(owner, peer, message);
        if (changed) setDirty();
        return changed;
    }
    public List<ChatMessage> visible(UUID owner, UUID peer, List<ChatMessage> messages) {
        return isAvailable() ? visibility.filter(owner, peer, messages) : List.of();
    }
    public void retain(UUID a, UUID b, Set<UUID> existing) {
        if (isAvailable() && visibility.retain(a, b, existing)) setDirty();
    }
    @Override protected CompoundTag write(CompoundTag tag) {
        if (!isAvailable()) return tag.merge(unreadable.copy());
        var encoded = CODEC.encodeStart(NbtOps.INSTANCE, new TreeMap<>(visibility.snapshot()));
        tag.put("hidden", encoded.result().orElseThrow(() -> new IllegalStateException("个人删除标记写入失败: " + encoded.error())));
        return tag;
    }
    static ChatDeletionData load(CompoundTag tag) {
        try {
            var decoded = CODEC.parse(NbtOps.INSTANCE, tag.get("hidden"));
            var value = decoded.result().orElseThrow(() -> new IllegalStateException("个人删除标记读取失败: " + decoded.error()));
            return new ChatDeletionData(ChatMessageVisibility.restore(value));
        } catch (RuntimeException error) {
            // SavedData 会捕获加载器抛出的异常并重建空实例。返回保留原文的锁定实例，避免覆盖原文件或恢复已删除消息。
            MCphone.LOGGER.error("个人删除标记损坏，暂时禁用聊天历史与删除；请服主恢复 mcphone_chat_deletions.dat 备份", error);
            return new ChatDeletionData(tag);
        }
    }
}
