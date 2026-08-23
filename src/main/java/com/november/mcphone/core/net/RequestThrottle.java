package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端对"客户端主动来要数据"这类包的最小间隔限制。
 *
 * ================================================================
 * 为什么必须有
 * ================================================================
 *
 * 界面自己约好了 3 秒拉一次（ChatList 等三处的 REFRESH_INTERVAL_MS）。
 * 问题是【服务端不认这个约定】——那是客户端代码里的一个常量，改一行
 * 重新编译就没了。改过的客户端可以每 tick 发一个 RequestConversationsPacket，
 * 也就是每秒 20 次，而每一次服务端都要老老实实把这个人的会话列表算一遍：
 * 查好友、逐个查最后一条消息、逐个数未读。
 *
 * 手机从 v1.3.0 起是开放给别人用的。一个玩家就能让服务端主线程忙起来，
 * 这不需要什么高明的手段，改个常量就行。
 *
 * ================================================================
 * 为什么只拦"要数据"，不拦"做事情"
 * ================================================================
 *
 * 发消息、加好友、卸载 App 这些是玩家按一下才有一次的动作，本来就快不
 * 起来，而且它们各自另有约束（消息每对会话上限 100 条、好友上限 100 人、
 * 申请箱上限 50 条）。更要紧的是：把玩家刚打的一条消息静默丢掉，比慢
 * 一点糟得多——他不会知道消息没发出去。
 *
 * 拉取类的包丢掉则没有这个问题：客户端下一轮轮询自己会再要一次，最坏
 * 情况是界面上的数据晚几秒刷新。
 *
 * ================================================================
 * 唯一的例外：好友传送
 * ================================================================
 *
 * 传送是"做事情"，却也在这里过一道。上面那条规矩不适用于它，两点都对得上：
 *
 *   丢了看得见 —— 丢一条消息玩家不会知道；丢一次传送他站在原地，
 *                 一眼就明白没成，再点一下就是了
 *   单次很贵   —— 跨维度传送要加载目标区块，是原版里最贵的单次操作之一。
 *                 一秒二十次的话，代价不在我们这个模组，在整台服务器
 *
 * 它挡的是发包频率，【不是】玩法上的冷却。真要给传送加冷却是另一件事，
 * 该由玩法层面决定，不该藏在一个叫"限流"的类里。
 *
 * ================================================================
 * 间隔为什么是 500 毫秒
 * ================================================================
 *
 * 正常客户端 3 秒一次，留了 6 倍余量。取这么松是因为界面切换会产生
 * 合理的连发：从会话列表点进某人、退回来，列表的 open() 会再要一次，
 * 两次之间可能不到一秒。卡到 1 秒以上的话，正常操作也会被吃掉，
 * 表现是"退回来列表是旧的"。
 *
 * 500 毫秒已经把 20 次/秒压到 2 次/秒。剩下的那点开销由 1.3.23 的
 * 邻接表兜住——限流和降复杂度是两件事，都做了才算数：只限流，单次
 * 仍然贵；只优化，仍然架不住每 tick 一次。
 */
public final class RequestThrottle {

    private RequestThrottle() {}

    /**
     * 分类计时，而不是所有请求共用一个时间戳。
     *
     * 共用的话，玩家点进一个会话（要历史消息）会把同一瞬间的会话列表
     * 刷新一起挡掉——两件事本来互不相干。
     */
    public enum Kind {
        /** 会话列表：查好友 + 逐个查最后一条消息 + 逐个数未读，最贵的一个 */
        CONVERSATIONS,
        /** 某个会话的历史消息 */
        MESSAGES,
        /** 在线玩家列表：遍历全服在线玩家 */
        ONLINE_PLAYERS,
        /** 标记已读：会写玩家附件 */
        MARK_READ,
        /** 笔记列表 */
        NOTE_LIST,
        /** 某条笔记的全文 */
        NOTE,
        /** 购买记录 */
        PURCHASED,
        /** 传送到好友身边。唯一一个"做事情"的包，理由见类注释 */
        TELEPORT
    }

    /** 同一类请求两次之间至少隔这么久 */
    private static final long MIN_INTERVAL_MS = 500L;

    /**
     * 每个玩家上次各类请求的时刻。
     *
     * 包处理都在 enqueueWork 里，也就是服务端主线程，本不必并发容器；
     * 用 ConcurrentHashMap 是为了不必去论证"将来也一定只有主线程碰它"——
     * 这点开销远比日后某次改动引入一个只在高并发下复现的问题便宜。
     */
    private static final Map<UUID, long[]> LAST_REQUEST = new ConcurrentHashMap<>();

    /**
     * 这次请求放不放行。
     *
     * @return true 表示可以处理；false 表示来得太快，本次丢弃
     */
    public static boolean allow(ServerPlayer player, Kind kind) {
        if (player == null) return false;

        long now = System.currentTimeMillis();
        long[] stamps = LAST_REQUEST.computeIfAbsent(
                player.getUUID(), k -> new long[Kind.values().length]);

        long last = stamps[kind.ordinal()];
        if (now - last < MIN_INTERVAL_MS) {
            // debug 而不是 warn：正常客户端在界面切换时也可能偶尔撞上，
            // 刷屏的日志会把真正值得看的东西淹掉
            MCphone.LOGGER.debug("[MCphone] 丢弃 {} 的 {} 请求：距上次仅 {} 毫秒",
                    player.getName().getString(), kind, now - last);
            return false;
        }

        stamps[kind.ordinal()] = now;
        return true;
    }

    /**
     * 玩家下线时把他的计时丢掉。
     *
     * 不丢的话这张表只增不减：一个跑了几个月的服务器，每个来过的玩家都
     * 会在里面留一条，而他们大多再也不会回来。这不是"泄漏得很快"，
     * 是那种半年后才看得出来的慢性增长。
     *
     * 由 MCphone 的构造函数显式挂到游戏总线上，不依赖注解自动路由——
     * 项目里其余几处也是这么做的，省得哪天路由规则变了，事件静悄悄地
     * 不再触发，而这个类的失效【没有任何症状】：限流照常工作，只是表
     * 再也不缩小了。
     *
     * 玩家再上线时 computeIfAbsent 自会重建一条。
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_REQUEST.remove(event.getEntity().getUUID());
    }
}
