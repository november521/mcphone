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
import java.util.TreeMap;
import java.util.UUID;

/**
 * 聊天记录存储 —— 服务端全局，存在世界存档里。
 *
 * 为什么不用玩家附件
 *
 * 壁纸用玩家附件是对的，那是"这个玩家的偏好"。消息不同：它属于
 * 【两个玩家之间】，存在谁身上都不对。更要命的是离线投递——给一个
 * 不在线的人发消息，若消息存在收件人身上，服务端就得去加载并改写
 * 他的存档文件；用 SavedData 则只是往 map 里加一条。
 *
 * 两个必须注意的点
 *
 * 1. 会话键要归一化。A→B 与 B→A 必须落到同一个会话，否则同一对人
 *    会各自看到半截记录。这件事交给 {@link ConversationKey}——运行时它是
 *    一个记录（哈希不分配任何东西），只在读写存档时才变回 "a|b" 那串字符。
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
    private final Map<ConversationKey, List<ChatMessage>> conversations;

    /** 存档格式仍是 "a|b" → 消息列表，与 1.4.16 及更早一个字节不差 */
    private static final Codec<Map<String, List<ChatMessage>>> CONVERSATIONS_CODEC =
            Codec.unboundedMap(Codec.STRING, ChatMessage.CODEC.listOf());

    public ChatData() {
        this.conversations = new HashMap<>();
    }

    private ChatData(Map<ConversationKey, List<ChatMessage>> conversations) {
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
     * 双方各自只看得到自己发的那一半。规则与代价见 {@link ConversationKey}。
     */
    public static ConversationKey conversationKey(UUID a, UUID b) {
        return ConversationKey.of(a, b);
    }

    // ============================================================
    //  读写
    // ============================================================

    /**
     * 取某对玩家之间的消息，按时间升序。没有会话时返回空列表而不是 null。
     *
     * 必须是拷贝，不能是 unmodifiableList 那种【视图】
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

    /**
     * 会话列表那一行要的两样东西：最后一条消息、未读条数。
     *
     * @param last   最后一条消息，还没聊过则为 null
     * @param unread 未读条数
     */
    public record Tail(ChatMessage last, int unread) {
        static final Tail EMPTY = new Tail(null, 0);
    }

    /**
     * 一次查表算出这两样。
     *
     * 原先是 getLastMessage 与 countAfter 两个方法，各查一次【同一个】会话
     * ——键构造与哈希查找都白做了一遍，而这是每人每 3 秒、每个好友都要走的
     * 那条路。合成一次之后开销减半。
     *
     * 未读必须只数对方发的：把自己发的也算进去的话，自己发一条消息就会给
     * 自己涨一个未读，而"已读时刻"要等下次打开会话才会推进，红点就一直
     * 挂在那儿。
     *
     * @param self  本人
     * @param peer  对端
     * @param since 本人与他的会话已读到哪个时刻
     */
    public Tail tail(UUID self, UUID peer, long since) {
        List<ChatMessage> list = conversations.get(conversationKey(self, peer));
        if (list == null || list.isEmpty()) return Tail.EMPTY;

        int unread = 0;
        // 从尾部往前数，未读通常很少，不必遍历整个列表
        for (int i = list.size() - 1; i >= 0; i--) {
            ChatMessage m = list.get(i);
            if (m.time() <= since) break;
            if (m.sender().equals(peer)) unread++;
        }
        return new Tail(list.get(list.size() - 1), unread);
    }

    // ============================================================
    //  序列化
    // ============================================================

    /**
     * 写出去时把记录键变回 "a|b"。
     *
     * 用 TreeMap 而不是 HashMap：顺序不稳的话，明明什么都没改，存档文件
     * 也会每次都不同——排查问题时那是纯噪音。与 FriendGraph.toPairKeys
     * 用 TreeSet 是同一个理由。
     */
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

        // 两件事一起做：把键解析成记录，把不可变列表复制成可变的。
        // 后者不做的话，读过档的世界里第一条新消息就会抛
        // UnsupportedOperationException——只在"读过档"时复现，最容易漏测。
        //
        // 读不懂的键跳过而不是抛：这份存档玩家可以手改，为一条坏记录让
        // 整个服务端起不来，代价完全不成比例
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
