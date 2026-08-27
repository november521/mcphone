package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import com.november.mcphone.MCphone;
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
 * 好友关系、待处理申请、玩家名缓存：服务端全局 SavedData，存在世界存档里。
 * 不用玩家附件：好友关系属于两个人，而且给离线玩家发申请不该去加载改写他的存档。
 */
public class FriendData extends SavedData {

    private static final String FILE_NAME = MCphone.MODID + "_friends";

    /** 每人好友数上限 */
    public static final int MAX_FRIENDS = 100;

    /** 每人待处理申请数上限，防止被人刷屏 */
    public static final int MAX_PENDING_PER_PLAYER = 50;

    private final FriendGraph friends;

    /** 收件人 → (申请人 → 申请时刻) */
    private final Map<UUID, Map<UUID, Long>> pendingRequests;

    /** UUID → 最近一次见到的玩家名 */
    private final Map<UUID, String> knownNames;

    /** 存档格式是归一化后的 "a|b" 列表，与老版本兼容，退版本不丢好友 */
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
     * 【1.20.1 的 SavedData API 与 1.21.1 不同，三处都要改】：
     *
     *   取用   computeIfAbsent(new SavedData.Factory<>(ctor, loader, null), name)
     *          → computeIfAbsent(loader, ctor, name)  —— 没有 Factory 这个类，
     *            而且【参数顺序是 loader 在前】，写反了编译不过但很容易看花眼
     *   存     save(CompoundTag, HolderLookup.Provider) → save(CompoundTag)
     *   读     load(CompoundTag, HolderLookup.Provider) → load(CompoundTag)
     *
     * 那个 HolderLookup.Provider 是 1.20.5 给存档编解码加的注册表访问，
     * 本类的存档格式只有字符串与数字，不需要它，去掉不丢任何东西。
     */
    /** 必须挂在主世界的 DataStorage：它按维度分，挂错了玩家去下界好友就"消失"且不报错 */
    public static FriendData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                FriendData::load, FriendData::new, FILE_NAME);
    }

    public boolean areFriends(UUID a, UUID b) {
        return friends.areFriends(a, b);
    }

    /** 已经是好友则返回 false */
    public boolean addFriendship(UUID a, UUID b) {
        if (!friends.add(a, b)) return false;
        setDirty();
        return true;
    }

    /** 双向解除；聊天记录不动，那是双方共有的 */
    public boolean removeFriendship(UUID a, UUID b) {
        if (!friends.remove(a, b)) return false;
        setDirty();
        return true;
    }

    public List<UUID> getFriends(UUID player) {
        return new ArrayList<>(friends.friendsOf(player));
    }

    public int countFriends(UUID player) {
        return friends.countFriends(player);
    }

    /** to 是否收到过 from 的申请 */
    public boolean hasRequest(UUID from, UUID to) {
        Map<UUID, Long> incoming = pendingRequests.get(to);
        return incoming != null && incoming.containsKey(from);
    }

    /** 已存在、或收件人的待处理数已满时返回 false */
    public boolean addRequest(UUID from, UUID to, long time) {
        Map<UUID, Long> incoming = pendingRequests.computeIfAbsent(to, k -> new HashMap<>());
        if (incoming.containsKey(from)) return false;
        if (incoming.size() >= MAX_PENDING_PER_PLAYER) return false;

        incoming.put(from, time);
        setDirty();
        return true;
    }

    /** 同意、拒绝、撤回都走这里 */
    public boolean removeRequest(UUID from, UUID to) {
        Map<UUID, Long> incoming = pendingRequests.get(to);
        if (incoming == null || incoming.remove(from) == null) return false;

        if (incoming.isEmpty()) pendingRequests.remove(to);
        setDirty();
        return true;
    }

    /** 名字没变就不标脏，免得每次有人上线都触发一次存档写入 */
    public void rememberName(UUID id, String name) {
        if (name == null || name.isEmpty()) return;
        if (name.equals(knownNames.get(id))) return;

        knownNames.put(id, name);
        setDirty();
    }

    /** 没记过则返回 null */
    public String getName(UUID id) {
        return knownNames.get(id);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
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

    private static FriendData load(CompoundTag tag) {
        FriendGraph friends = FriendGraph.fromPairKeys(
                decode(FRIENDSHIPS_CODEC, tag, "friendships", List.of()));

        // Codec 解出来的是不可变集合，必须复制成可变的，否则读档后第一次加申请就抛 UnsupportedOperationException
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
