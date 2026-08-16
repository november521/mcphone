package com.november.mcphone.feature.clock.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stats;

/**
 * 两个游玩时长：本次玩了多久，这个存档一共陪了你多久。
 *
 * ================================================================
 * 两个数，两个来路，都是有意的
 * ================================================================
 *
 * 【本次 —— 墙上时钟】
 *
 * 进世界时记一个时刻，差值就是。它包括你挂在暂停菜单里的那几分钟——
 * 这是对的：没人会说"我玩了两小时，但有十分钟在暂停，所以算一小时五十"。
 * 玩家说的"本次玩了多久"就是从坐下到现在。
 *
 * 【存档总计 —— 原版统计】
 *
 * 用 Stats.PLAY_TIME，也就是原版「统计」界面里那个「游戏时长」。这样玩家
 * 按 Esc 看到的数和手机里的数【是同一个】，他能对上。
 *
 * 另一条路是我们自己从装上这个模组起算。那个方案听着更简单，实际更麻烦
 * （要自己存盘、按存档分文件、处理换世界），而且数字会撒谎：一个玩了 300
 * 小时的老存档，装上手机第二天显示"12 小时"——那比不显示更糟。
 *
 * ================================================================
 * 客户端手上默认没有统计数据
 * ================================================================
 *
 * LocalPlayer 是有 StatsCounter，但它是每次连接【新建的空表】
 * （ClientPacketListener 里 new StatsCounter() 传给 createPlayer），服务端
 * 不会主动推。原版「统计」界面能显示，是因为它打开时发了一个
 * ServerboundClientCommandPacket(REQUEST_STATS)。
 *
 * 我们走同一条路。这是【原版包】，每个服务端都认，不需要我们自己定义协议，
 * 服务端也不需要装任何东西。
 *
 * 【为什么要节流】
 *
 * 服务端收到这个请求会把该玩家的【全部】统计序列化发回来——一个老玩家几百
 * 条统计，几 KB。每帧问一次的话，就成了 1.3.24 刚修掉的那种事，只不过这回
 * 加害者是我们自己。
 *
 * 所以：进世界时问一次，打开时钟那一页时问一次，之后最快 10 秒一次。存档
 * 总时长是按"小时几分"显示的，10 秒的陈旧在界面上根本看不出来。
 */
public final class PlayTime {

    private PlayTime() {}

    /** 两次请求之间至少隔这么久 */
    private static final long REQUEST_INTERVAL_MS = 10_000L;

    /** 本次进世界的时刻。0 表示还没进 */
    private static long sessionStartMs;

    /** 上次向服务端要统计的时刻 */
    private static long lastRequestMs;

    // ============================================================
    //  世界进出
    // ============================================================

    /** 进世界时调用：本次计时从这一刻起，并立刻要一份统计 */
    public static void onWorldJoin() {
        sessionStartMs = System.currentTimeMillis();
        lastRequestMs = 0;
        requestStats();
    }

    /**
     * 离开世界时调用。
     *
     * 必须清零：不清的话，回到主菜单再进另一个存档，"本次"会从上一个世界
     * 就开始算——而那两段时间根本不是同一次游玩。
     *
     * 统计数据不必清，客户端换连接时会新建一张空表。
     */
    public static void onWorldLeave() {
        sessionStartMs = 0;
        lastRequestMs = 0;
    }

    // ============================================================
    //  取值
    // ============================================================

    /** 本次玩了多少毫秒。还没进世界时返回 0 */
    public static long sessionMillis() {
        if (sessionStartMs <= 0) return 0;
        return Math.max(0, System.currentTimeMillis() - sessionStartMs);
    }

    /**
     * 这个存档一共玩了多少【现实 tick】。
     *
     * @return -1 表示还不知道——统计还没从服务端回来。调用方必须区分它和 0，
     *         把"还不知道"显示成"0 小时"是撒谎，而玩家分不出这是没数据还是
     *         真的刚开始玩
     */
    public static long worldPlayTicks() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return -1;

        int ticks = player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);

        // 0 一律当成"还没同步"。真的是 0 的窗口只有进世界后的第一 tick，
        // 而那会儿玩家还没来得及掏出手机——这个近似值得，省掉一套
        // "有没有收到过同步"的状态跟踪
        return ticks > 0 ? ticks : -1;
    }

    // ============================================================
    //  请求
    // ============================================================

    /**
     * 向服务端要一份统计。节流，来得太快直接跳过。
     *
     * 界面可以每帧无脑调它，多余的调用在这里被挡掉——把节流放在调用方，
     * 迟早有一个新调用点忘了加。
     */
    public static void requestStats() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REQUEST_INTERVAL_MS) return;

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;

        lastRequestMs = now;
        connection.send(new ServerboundClientCommandPacket(
                ServerboundClientCommandPacket.Action.REQUEST_STATS));
    }
}
