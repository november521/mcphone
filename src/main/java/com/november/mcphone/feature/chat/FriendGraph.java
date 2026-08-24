package com.november.mcphone.feature.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 好友关系图 —— 谁和谁是好友，以及它怎么写进存档。
 *
 * 为什么单独一个类，而且【不 import 任何 Minecraft 类型】
 *
 * 这里全是纯集合运算：加一条边、删一条边、问某人有哪些好友、把整张图
 * 转成存档里那串字符串再转回来。而最后一件事碰的是【别人的存档】——
 * 转换写错一个方向，服务器上所有人的好友关系当场清零，而且是不可逆的：
 * 存档一存，原来的数据就没了。
 *
 * 单独摆出来、不碰 Minecraft，就能用 javac 直接编出来跑断言，不必开服务器。
 * 这是它存在的唯一理由，所以别往里加需要 MinecraftServer 或 Codec 的东西。
 * 与 {@link com.november.mcphone.core.client.HomeLayout} 是同一个考虑。
 *
 * 为什么是邻接表，而不是一个 "a|b" 的集合
 *
 * 1.3.22 之前，好友关系存成 Set&lt;String&gt;，元素是归一化后的 "a|b"。
 * 问"某人有哪些好友"就只能遍历【全服】的每一条关系，逐条 substring、
 * 逐条 UUID.fromString：
 *
 *     for (String key : friendships) {          // 全服所有人的所有好友
 *         int sep = key.indexOf('|');
 *         ...
 *         out.add(UUID.fromString(other));      // 最贵的一步
 *     }
 *
 * 而 countFriends 直接调它拿个 size，buildConversations 每次拉会话列表都要
 * 走一遍——那是客户端每 3 秒一次的轮询。100 个玩家各 50 个好友，就是每人
 * 每次轮询扫 2500 条、解析 2500 次 UUID。
 *
 * 换成 Map&lt;UUID, Set&lt;UUID&gt;&gt; 之后这两件事都是一次哈希查找。
 *
 * 【为什么不是"再加一份索引"】
 * 那样就有两份数据表达同一件事，而增删时忘了同步另一份，表现出来是
 * "明明解除了好友，会话列表里还有他"。这里不是加索引，是把唯一那份数据
 * 换个长法：邻接表【就是】权威，"a|b" 那串字符串退化成纯粹的存档格式，
 * 只在读写存档的一瞬间存在。
 *
 * 存档格式一个字节都没变
 *
 * {@link #toPairKeys()} 与 {@link #fromPairKeys} 负责在两种表示之间转换。
 * 老存档照读，新存档拿回老版本也照读——不这么做的话，玩家想退版本就得
 * 在"退版本"和"丢好友"之间选一个。
 *
 * 一条边在邻接表里存两遍（a→b 和 b→a），写出去时归一化成一个键自动去重。
 */
public final class FriendGraph {

    /** 某人 → 他的全部好友。双向都存，所以每条边在这里出现两次 */
    private final Map<UUID, Set<UUID>> adjacency = new HashMap<>();

    //  会话键

    /**
     * 把两个 UUID 归一化成同一个键。
     *
     * 排序是关键：不排的话 A→B 与 B→A 会落进两个不同的键，同一对人
     * 各自只看得到一半。{@link ChatData#conversationKey} 就是转调这里，
     * 好友关系与聊天记录用同一套规则。
     */
    public static String pairKey(UUID a, UUID b) {
        String sa = a.toString();
        String sb = b.toString();
        return sa.compareTo(sb) <= 0 ? sa + "|" + sb : sb + "|" + sa;
    }

    //  读写

    public boolean areFriends(UUID a, UUID b) {
        Set<UUID> of = adjacency.get(a);
        return of != null && of.contains(b);
    }

    /**
     * 建立好友关系。已经是好友则返回 false。
     *
     * 自己不能是自己的好友：正常路径上层已经拦了，这里再拦一次是因为
     * 存档可能被手改过，而一条 a|a 会让"好友数"平白多一个数不出来的人。
     */
    public boolean add(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        if (areFriends(a, b)) return false;

        adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        return true;
    }

    /**
     * 解除好友关系，双向。不是好友则返回 false。
     *
     * 删空之后把整项摘掉：不摘的话，一个加过又删光好友的玩家会在表里
     * 永远留一个空集合，而这种垃圾只增不减。
     */
    public boolean remove(UUID a, UUID b) {
        if (!areFriends(a, b)) return false;

        removeOneWay(a, b);
        removeOneWay(b, a);
        return true;
    }

    private void removeOneWay(UUID from, UUID to) {
        Set<UUID> of = adjacency.get(from);
        if (of == null) return;
        of.remove(to);
        if (of.isEmpty()) adjacency.remove(from);
    }

    /** 某人的全部好友。没有好友时返回空集合，不是 null */
    public Set<UUID> friendsOf(UUID player) {
        Set<UUID> of = adjacency.get(player);
        return of == null ? Set.of() : Collections.unmodifiableSet(of);
    }

    /** 某人有几个好友。一次哈希查找，不必把好友列出来再数 */
    public int countFriends(UUID player) {
        Set<UUID> of = adjacency.get(player);
        return of == null ? 0 : of.size();
    }

    /** 全服一共几条好友关系。每条只算一次 */
    public int edgeCount() {
        int halves = 0;
        for (Set<UUID> of : adjacency.values()) halves += of.size();
        return halves / 2;
    }

    //  存档格式转换

    /**
     * 转成存档里那串 "a|b"。
     *
     * 用 TreeSet 而不是 HashSet：一条边在邻接表里存了两遍，归一化后天然
     * 去重；排序则让同样的关系每次写出来的顺序都一样。顺序不稳的话，
     * 明明什么都没改，存档文件也会每次都不同——排查问题时那是纯噪音。
     */
    public List<String> toPairKeys() {
        Set<String> keys = new TreeSet<>();
        adjacency.forEach((self, friends) ->
                friends.forEach(other -> keys.add(pairKey(self, other))));
        return new ArrayList<>(keys);
    }

    /**
     * 从存档里那串 "a|b" 读回来。
     *
     * 脏数据一律跳过而不是抛异常：这份存档玩家可以手改，也可能被别的
     * 工具动过。为一条读不懂的记录让整个服务端起不来，代价完全不成比例
     * ——尤其是它导致的是"所有人进不来"，而不是"一个人少一个好友"。
     */
    public static FriendGraph fromPairKeys(Collection<String> keys) {
        FriendGraph graph = new FriendGraph();
        if (keys == null) return graph;

        for (String key : keys) {
            if (key == null) continue;
            int sep = key.indexOf('|');
            if (sep < 0) continue;

            try {
                graph.add(UUID.fromString(key.substring(0, sep)),
                          UUID.fromString(key.substring(sep + 1)));
            } catch (IllegalArgumentException ignored) {
                // 不是合法 UUID，跳过这一条
            }
        }
        return graph;
    }
}
