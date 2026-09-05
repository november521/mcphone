package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import com.november.mcphone.MCphone;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 聊天记录存储：服务端全局 SavedData，存在世界存档里。
 * 不用玩家附件：消息属于两个玩家之间，离线投递也不该去加载改写收件人的存档。每对会话只留最近 {@link #MAX_MESSAGES_PER_CONVERSATION} 条。
 */
public class ChatData extends SavedData {

    private static final String FILE_NAME = MCphone.MODID + "_chat";

    /** 每对会话保留的消息条数上限 */
    public static final int MAX_MESSAGES_PER_CONVERSATION = 100;

    /** 归一化后的会话键 → 按时间升序的消息列表 */
    private final Map<ConversationKey, List<ChatMessage>> conversations;

    /** 存档格式是 "a|b" → 消息列表，与老版本兼容 */
    private static final Codec<Map<String, List<ChatMessage>>> CONVERSATIONS_CODEC =
            Codec.unboundedMap(Codec.STRING, ChatMessage.CODEC.listOf());

    public ChatData() {
        this.conversations = new HashMap<>();
    }

    private ChatData(Map<ConversationKey, List<ChatMessage>> conversations) {
        this.conversations = conversations;
    }

    /** 必须挂在主世界的 DataStorage：它按维度分，挂错了玩家去下界就看不到消息 */
    public static ChatData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ChatData::new, ChatData::load, null),
                FILE_NAME);
    }

    /** 归一化：A→B 与 B→A 必须落进同一个会话 */
    public static ConversationKey conversationKey(UUID a, UUID b) {
        return ConversationKey.of(a, b);
    }

    /**
     * 按时间升序，没有会话时返回空列表而不是 null。
     * 必须返回拷贝而不是视图：结果装进 SyncMessagesPacket 后在 netty 线程上编码，主线程同时 addMessage 会抛 CME；
     * 单机/局域网更是不序列化直接共享对象。
     */
    public List<ChatMessage> getMessages(UUID a, UUID b) {
        List<ChatMessage> list = conversations.get(conversationKey(a, b));
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * 超出上限时丢弃最旧的，被丢掉的那几条一并返回。
     *
     * 返回而不是自己处理：被挤出去的如果是图片消息，它那张图也该从图片仓里删掉，
     * 而"什么时候删图"是业务上的事（{@link ChatService}），不该由存储层替它决定。
     * 绝大多数情况返回的是空列表。
     */
    public List<ChatMessage> addMessage(UUID from, UUID to, ChatMessage message) {
        List<ChatMessage> list =
                conversations.computeIfAbsent(conversationKey(from, to), k -> new ArrayList<>());
        list.add(message);

        List<ChatMessage> evicted = List.of();
        while (list.size() > MAX_MESSAGES_PER_CONVERSATION) {
            if (evicted.isEmpty()) evicted = new ArrayList<>(1);
            evicted.add(list.remove(0));
        }
        setDirty();
        return evicted;
    }

    /**
     * 全服所有消息引用到的图片 id。只有服务器启动时清理孤儿文件用得上，见
     * {@link ChatImageStore#sweepOrphans}。
     *
     * 走一遍全部会话，在启动时做一次是划算的：另存一份"图片 → 会话"的索引，就要保证它
     * 与消息列表永远一致，而那正是最容易被下一次改动悄悄破坏的东西。
     */
    public Set<UUID> referencedImages() {
        Set<UUID> out = new HashSet<>();
        for (List<ChatMessage> list : conversations.values()) {
            for (ChatMessage m : list) {
                if (m.body() instanceof ImageBody image) out.add(image.image());
            }
        }
        return out;
    }

    /** 会话列表那一行要的两样东西；last 在还没聊过时为 null */
    public record Tail(ChatMessage last, int unread) {
        static final Tail EMPTY = new Tail(null, 0);
    }

    /**
     * 一次查表同时算出最后一条与未读数（每人每 3 秒、每个好友都走这条路）。
     * 未读只数对方发的，否则自己发一条就给自己涨一个红点。since 是本人对这个会话的已读时刻。
     */
    public Tail tail(UUID self, UUID peer, long since) {
        List<ChatMessage> list = conversations.get(conversationKey(self, peer));
        if (list == null || list.isEmpty()) return Tail.EMPTY;

        int unread = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            ChatMessage m = list.get(i);
            if (m.time() <= since) break;
            if (m.sender().equals(peer)) unread++;
        }
        return new Tail(list.get(list.size() - 1), unread);
    }

    /** 用 TreeMap 让输出顺序稳定，存档不会无故变化 */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Map<String, List<ChatMessage>> encodable = new TreeMap<>();
        conversations.forEach((key, list) -> encodable.put(key.toStorageKey(), list));

        CONVERSATIONS_CODEC.encodeStart(NbtOps.INSTANCE, encodable)
                .resultOrPartial(err -> MCphone.LOGGER.error("聊天记录写入失败: {}", err))
                .ifPresent(encoded -> tag.put("conversations", encoded));
        return tag;
    }

    private static ChatData load(CompoundTag tag, HolderLookup.Provider registries) {
        Map<String, List<ChatMessage>> loaded = CONVERSATIONS_CODEC
                .parse(NbtOps.INSTANCE, tag.get("conversations"))
                .resultOrPartial(err -> MCphone.LOGGER.error("聊天记录读取失败: {}", err))
                .orElse(Map.of());

        // Codec 解出来的列表不可变，必须复制成可变的；读不懂的键跳过而不是抛，别为一条坏记录让全服起不来
        Map<ConversationKey, List<ChatMessage>> mutable = new HashMap<>();
        loaded.forEach((k, v) -> {
            ConversationKey key = ConversationKey.parse(k);
            if (key == null) {
                MCphone.LOGGER.warn("[MCphone] 跳过一条读不懂的会话键: {}", k);
                return;
            }
            mutable.put(key, new ArrayList<>(v));
        });
        return new ChatData(mutable);
    }
}
