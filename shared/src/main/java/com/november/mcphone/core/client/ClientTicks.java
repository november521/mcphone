package com.november.mcphone.core.client;

/**
 * 共用代码里"每客户端 tick 要做一次"的那些事，<b>汇到这一个入口</b>。
 *
 * <h2>为什么要这一格</h2>
 *
 * tick 事件是加载器的东西，而且形状还随版本变：NeoForge 21 是
 * {@code ClientTickEvent.Post}，20.1.x 那条线与 Forge 都是
 * {@code TickEvent.ClientTickEvent} 再自己判 {@code phase == END}（不判就是一 tick
 * 触发两次）。所以订阅那一句只能写在各目标下。
 *
 * 但订阅的<b>条数</b>不该跟着功能涨。从前是共用侧每多一个要 tick 的功能，就得去每个目标的
 * MCphoneClient 里各加一条订阅——加一个功能改 N 处，而漏掉某一处不报错：那个目标上这个
 * 功能永远不响，编译绿、闸也绿。
 *
 * 现在每个目标只订阅这一个 {@link #tick()}，共用侧要 tick 的功能加在下面那串里。
 * <b>加一个功能＝在这个文件里加一行，平台文件一个都不用动。</b>
 *
 * <h2>顺序</h2>
 *
 * 下面的调用按顺序执行，而各目标把这一个入口挂在 {@code PhoneHud.onClientTick} 之后 ——
 * 这样同一 tick 里刚挂上 HUD 的那台设备，本 tick 就能被 {@link PhoneScreenOnSync} 报上去。
 * 早一 tick 晚一 tick 都不影响正确性，但既然顺序是免费的，就挑对的那个。
 *
 * <h2>不做异常兜底</h2>
 *
 * 这里刻意不 try/catch：共用侧的 tick 逻辑抛异常是真 bug，让它照常崩出崩溃报告，
 * 比吞掉之后留下一个"这个功能今天不工作"的谜要好查得多。
 */
public final class ClientTicks {

    private ClientTicks() {}

    /**
     * 由各目标的 MCphoneClient 每客户端 tick 调一次（NeoForge 挂 {@code ClientTickEvent.Post}，
     * Forge 挂 {@code TickEvent.ClientTickEvent} 并判 {@code phase == END}）。
     */
    public static void tick() {
        // 「这会儿开着的是哪一台设备」每 tick 算一次，变了才发包
        PhoneScreenOnSync.tick();
    }
}
