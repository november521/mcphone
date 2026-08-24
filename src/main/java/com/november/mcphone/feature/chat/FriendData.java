package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import com.november.mcphone.MCphone;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 好友关系与待处理申请 —— 服务端全局，存在世界存档里。
 *
 * 为什么不用玩家附件
 *
 * "A 和 B 是好友"这件事同时属于两个人，存在谁身上都不对。
 *
 * 更要命的是离线场景：A 给不在线的 B 发好友申请，若申请要写进 B 的附件，
 * 服务端就得去加载并改写 B 的存档文件。用 SavedData 则只是往 map 里
 * 加一条——这和消息为什么不用附件是同一个理由，见 {@link ChatData}。
 *
 * 玩家附件里只留"已读进度"，那才是真正的个人私事。
 *
 * 名字为什么也存在这里
 *
 * 好友离线时服务端手上只有 UUID。原先把名字存在各人的联系人条目里，
 * 现在好友关系是共有的，名字也就该有一份共用的缓存：任何玩家上线时
 * 记一次，谁要显示都查这里。
 *
 * 顺带解决了"申请人是谁"的显示问题——申请可能来自一个此刻不在线、
 * 也还不是好友的人，没有这份缓存就只能显示一串 UUID。
 */
public class FriendData extends SavedData {

    private static final String FILE_NAME = MCphone.MODID + "_friends";

    /** 每人好友数上限 */
    public static final int MAX_FRIENDS = 100;

    /** 每人待处理申请数上限，防止被人刷屏 */
    public static final int MAX_PENDING_PER_PLAYER = 50;

    /**
     * 好友关系图。
     *
     * 1.3.22 之前这里是 Set&lt;String&gt;，元素是归一化后的 "a|b"，于是问
     * "某人有哪些好友"要遍历全服每一条关系。理由与代价见 {@link FriendGraph}
     * 的类注释；存档格式没有变，转换由它负责。
     */
    private final FriendGraph friends;

    /** 待处理申请：收件人 → (申请人 → 申请时刻) */
    private final Map<UUID, Map<UUID, Long>> pendingRequests;

    /** UUID → 最近一次见到的玩家名 */
    private final Map<UUID, String> knownNames;

    // ---- 序列化 ----

    /**
     * 存档里仍然是一串归一化后的 "a|b"，与 1.3.22 及更早完全一致。
     *
     * 老存档照读，新存档拿回老版本也照读——不这么做的话，玩家想退版本
     * 就得在"退版本"和"丢好友"之间选一个。
     */
    private static final Codec<List<String>> FRIENDSHIPS_CODEC = Codec.STRING.listOf();

    private static final Codec<Map<UUID, Map<UUID, Long>>> REQUESTS_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC,
                    Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG));

    private static final Codec<Map<UUID, String>> NAMES_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING);

    public FriendData() {
        this.friends = new FriendGraph();
        this.pendingRequests = new HashMap<>();
        this.knownNames = new HashMap<>();
    }

    private FriendData(FriendGraph friends,
                       Map<UUID, Map<UUID, Long>> pendingRequests,
                       Map<UUID, String> knownNames) {
        this.friends = friends;
        this.pendingRequests = pendingRequests;
        this.knownNames = knownNames;
    }

    /**
     * 取得全服唯一的好友数据。
     *
     * 与 ChatData 一样挂在主世界的 DataStorage：getDataStorage 是按维度
     * 分的，挂错的话玩家去了下界好友就"消失"了，而且不报错。
     */
    public static FriendData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FriendData::new, FriendData::load, null),
                FILE_NAME);
    }

    //  好友关系

    public boolean areFriends(UUID a, UUID b) {
        return friends.areFriends(a, b);
    }

    /** 成为好友。已经是好友则返回 false */
    public boolean addFriendship(UUID a, UUID b) {
        if (!friends.add(a, b)) return false;
        setDirty();
        return true;
    }

    /**
     * 解除好友关系。
     *
     * 双向解除：好友关系是共有的一条记录，没有"我删了你但你还留着我"
     * 这种半吊子状态。聊天记录不动——那是双方共有的，单方面抹掉等于
     * 替对方做决定。
     */
    public boolean removeFriendship(UUID a, UUID b) {
        if (!friends.remove(a, b)) return false;
        setDirty();
        return true;
    }

    /** 某人的全部好友。一次哈希查找，不再扫全服 */
    public List<UUID> getFriends(UUID player) {
        return new ArrayList<>(friends.friendsOf(player));
    }

    /**
     * 某人有几个好友。
     *
     * 不再走 getFriends().size()：那样为了一个数字要先把好友列出来。
     * 这个方法在每次发好友申请时被调两次（查双方的上限）。
     */
    public int countFriends(UUID player) {
        return friends.countFriends(player);
    }

    //  好友申请

    /** to 是否收到过 from 的申请 */
    public boolean hasRequest(UUID from, UUID to) {
        Map<UUID, Long> incoming = pendingRequests.get(to);
        return incoming != null && incoming.containsKey(from);
    }

    /**
     * 记一条申请。已存在、或收件人的待处理数已满时返回 false。
     *
     * 上限是防刷屏：没有它的话，一个人可以给同一个受害者制造无数条申请，
     * 把对方的申请列表和存档一起撑爆。
     */
    public boolean addRequest(UUID from, UUID to, long time) {
        Map<UUID, Long> incoming = pendingRequests.computeIfAbsent(to, k -> new HashMap<>());
        if (incoming.containsKey(from)) return false;
        if (incoming.size() >= MAX_PENDING_PER_PLAYER) return false;

        incoming.put(from, time);
        setDirty();
        return true;
    }

    /** 撤掉一条申请（同意、拒绝、撤回都走这里） */
    public boolean removeRequest(UUID from, UUID to) {
        Map<UUID, Long> incoming = pendingRequests.get(to);
        if (incoming == null || incoming.remove(from) == null) return false;

        // 空了就把整项摘掉，免得存档里留一堆空 map
        if (incoming.isEmpty()) pendingRequests.remove(to);
        setDirty();
        return true;
    }



    //  玩家名缓存

    /**
     * 记下某人当前的名字。名字没变就不标脏，避免每次有人上线都触发
     * 一次存档写入。
     */
    public void rememberName(UUID id, String name) {
        if (name == null || name.isEmpty()) return;
        if (name.equals(knownNames.get(id))) return;

        knownNames.put(id, name);
        setDirty();
    }

    /** 查名字，没记过则返回 null */
    public String getName(UUID id) {
        return knownNames.get(id);
    }

    //  序列化

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        encode(FRIENDSHIPS_CODEC, friends.toPairKeys(), tag, "friendships");
        encode(REQUESTS_CODEC, pendingRequests, tag, "requests");
        encode(NAMES_CODEC, knownNames, tag, "names");
        return tag;
    }

    private static <T> void encode(Codec<T> codec, T value, CompoundTag tag, String key) {
        codec.encodeStart(NbtOps.INSTANCE, value)
                .resultOrPartial(err -> MCphone.LOGGER.error("好友数据写入失败 [{}]: {}", key, err))
                .ifPresent(encoded -> tag.put(key, encoded));
    }

    private static FriendData load(CompoundTag tag, HolderLookup.Provider registries) {
        FriendGraph friends = FriendGraph.fromPairKeys(
                decode(FRIENDSHIPS_CODEC, tag, "friendships", List.of()));

        // Codec 解出来的是不可变集合，而运行时要往里增删。不复制的话，
        // 读过档的世界里第一次加好友就会抛 UnsupportedOperationException——
        // 只在"读过档"时复现，最容易漏测
        Map<UUID, Map<UUID, Long>> requests = new HashMap<>();
        decode(REQUESTS_CODEC, tag, "requests", Map.of())
                .forEach((to, incoming) -> requests.put(to, new HashMap<>(incoming)));

        Map<UUID, String> names = new HashMap<>(decode(NAMES_CODEC, tag, "names", Map.of()));

        return new FriendData(friends, requests, names);
    }

    private static <T> T decode(Codec<T> codec, CompoundTag tag, String key, T fallback) {
        return codec.parse(NbtOps.INSTANCE, tag.get(key))
                .resultOrPartial(err -> MCphone.LOGGER.error("好友数据读取失败 [{}]: {}", key, err))
                .orElse(fallback);
    }
}
