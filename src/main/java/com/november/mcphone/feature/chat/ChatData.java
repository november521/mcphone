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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天记录存储 —— 服务端全局，存在世界存档里。
 *
 * ============================================================
 * 为什么不用玩家附件
 * ============================================================
 *
 * 壁纸用玩家附件是对的，那是"这个玩家的偏好"。消息不同：它属于
 * 【两个玩家之间】，存在谁身上都不对。更要命的是离线投递——给一个
 * 不在线的人发消息，若消息存在收件人身上，服务端就得去加载并改写
 * 他的存档文件；用 SavedData 则只是往 map 里加一条。
 *
 * ============================================================
 * 两个必须注意的点
 * ============================================================
 *
 * 1. 会话键要归一化。A→B 与 B→A 必须落到同一个会话，否则同一对人
 *    会各自看到半截记录。做法是把两个 UUID 按字典序排序后拼接。
 *
 * 2. 必须有容量上限。这是"离线可存"的代价：一个跑了几个月的服务器，
 *    不设上限的话存档会被聊天记录撑爆。每对会话只保留最近
 *    {@link #MAX_MESSAGES_PER_CONVERSATION} 条，超出丢最旧的。
 */
public class ChatData extends SavedData {

    /** 存档中的文件名，位于 world/data/ 下 */
    private static final String FILE_NAME = MCphone.MODID + "_chat";

    /** 每对会话保留的消息条数上限 */
    public static final int MAX_MESSAGES_PER_CONVERSATION = 100;

    /** 会话表：归一化后的会话键 → 按时间升序的消息列表 */
    private final Map<String, List<ChatMessage>> conversations;

    private static final Codec<Map<String, List<ChatMessage>>> CONVERSATIONS_CODEC =
            Codec.unboundedMap(Codec.STRING, ChatMessage.CODEC.listOf());

    public ChatData() {
        this.conversations = new HashMap<>();
    }

    private ChatData(Map<String, List<ChatMessage>> conversations) {
        this.conversations = conversations;
    }

    // ============================================================
    //  存取入口
    // ============================================================

    /**
     * 取得全服唯一的聊天存储。
     *
     * 刻意挂在主世界的 DataStorage：getDataStorage() 是按维度分的，
     * 挂错维度的话玩家去了下界就看不到自己的消息。
     */
    public static ChatData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ChatData::new, ChatData::load, null),
                FILE_NAME);
    }

    // ============================================================
    //  会话键
    // ============================================================

    /**
     * 把两个玩家 UUID 归一化成同一个会话键。
     *
     * 排序是关键：不排的话 A 发给 B 与 B 发给 A 会落进两个不同的会话，
     * 双方各自只看得到自己发的那一半。
     */
    public static String conversationKey(UUID a, UUID b) {
        return FriendGraph.pairKey(a, b);
    }

    // ============================================================
    //  读写
    // ============================================================

    /**
     * 取某对玩家之间的消息，按时间升序。没有会话时返回空列表而不是 null。
     *
     * ============================================================
     * 必须是拷贝，不能是 unmodifiableList 那种【视图】
     * ============================================================
     *
     * 这份列表的唯一去处是 SyncMessagesPacket，而网络包不是当场编码的：
     * 处理函数跑在服务端主线程，编码发生在稍后的 netty 线程上。中间只要
     * 主线程往这个会话里 addMessage 一条，netty 那边正在遍历的迭代器就会
     * 抛 ConcurrentModificationException——表现是那名玩家莫名掉线，
     * 而栈里一个字都不会提到聊天。
     *
     * 触发条件很具体：玩家点进某人会话的同一瞬间，那个人发来一条消息。
     * 窗口很窄，但服务器跑久了总会撞上一次。
     *
     * 单机与开局域网更直接：本地连接根本不做序列化，包里装的就是这个
     * 对象本身，客户端渲染线程读的是服务端正在改的那份列表。
     *
     * 拷贝的代价：每次点进会话复制最多 100 个引用，而这条路径本就有
     * 500 毫秒限流。FriendData 那边所有 getter 早就是这么做的，
     * 这里是最后一个漏网的。
     */
    public List<ChatMessage> getMessages(UUID a, UUID b) {
        List<ChatMessage> list = conversations.get(conversationKey(a, b));
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * 追加一条消息。超出上限时丢弃最旧的。
     *
     * @param from 发送者
     * @param to   接收者
     */
    public void addMessage(UUID from, UUID to, ChatMessage message) {
        List<ChatMessage> list =
                conversations.computeIfAbsent(conversationKey(from, to), k -> new ArrayList<>());
        list.add(message);

        // 只丢最旧的，不做整体截断：正常情况每次只超出一条
        while (list.size() > MAX_MESSAGES_PER_CONVERSATION) {
            list.remove(0);
        }
        setDirty();
    }

    /** 最后一条消息，用于会话列表的摘要行。没有则返回 null */
    public ChatMessage getLastMessage(UUID a, UUID b) {
        List<ChatMessage> list = conversations.get(conversationKey(a, b));
        return (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);
    }

    /**
     * 未读条数 —— 会话中由 {@code peer} 发出、且晚于 since 的消息条数。
     *
     * 必须只数对方发的：把自己发的也算进去的话，自己发一条消息就会给
     * 自己涨一个未读，而"已读时刻"要等下次打开会话才会推进，红点就一直
     * 挂在那儿。
     *
     * @param self 本人
     * @param peer 对端
     */
    public int countAfter(UUID self, UUID peer, long since) {
        List<ChatMessage> list = conversations.get(conversationKey(self, peer));
        if (list == null) return 0;

        int n = 0;
        // 从尾部往前数，未读通常很少，不必遍历整个列表
        for (int i = list.size() - 1; i >= 0; i--) {
            ChatMessage m = list.get(i);
            if (m.time() <= since) break;
            if (m.sender().equals(peer)) n++;
        }
        return n;
    }

    // ============================================================
    //  序列化
    // ============================================================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CONVERSATIONS_CODEC.encodeStart(NbtOps.INSTANCE, conversations)
                .resultOrPartial(err -> MCphone.LOGGER.error("聊天记录写入失败: {}", err))
                .ifPresent(encoded -> tag.put("conversations", encoded));
        return tag;
    }

    private static ChatData load(CompoundTag tag, HolderLookup.Provider registries) {
        Map<String, List<ChatMessage>> loaded = CONVERSATIONS_CODEC
                .parse(NbtOps.INSTANCE, tag.get("conversations"))
                .resultOrPartial(err -> MCphone.LOGGER.error("聊天记录读取失败: {}", err))
                .orElse(Map.of());

        // Codec 解出来的是不可变集合，而运行时要往里加消息，
        // 必须复制成可变的，否则第一条新消息就会抛 UnsupportedOperationException
        Map<String, List<ChatMessage>> mutable = new HashMap<>();
        loaded.forEach((k, v) -> mutable.put(k, new ArrayList<>(v)));
        return new ChatData(mutable);
    }
}
