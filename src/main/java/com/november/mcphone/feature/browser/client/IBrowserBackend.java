package com.november.mcphone.feature.browser.client;

import net.minecraft.network.chat.Component;

/**
 * 浏览器后端 —— 谁来真正提供一个浏览器。
 *
 * 目前只有 MCEF 一种实现（见 {@link McefBackend}）。做成接口不是为了将来可能有
 * 第二种，而是为了让"没有后端"成为一种正常状态：没装 MCEF 时后端是
 * {@link BrowserBackends#NONE}，界面照常显示、并明确告诉玩家缺什么，而不是崩。
 *
 * ============================================================
 * 实现类里的注意事项
 * ============================================================
 *
 * 实现类会引用具体后端（MCEF）的类型，而那些类在对方没装时根本不存在。所以
 * 【不要】在别处直接 new 一个实现类——统一走 {@link BrowserBackends#installDefault()}，
 * 那里把"装没装"的判断和"真去 new 它"分在了两个方法里。理由与 CuriosCompat
 * 一样：JVM 准备执行一个方法时会解析它引用到的类型，写在同一个方法里的话，
 * 那句 if 还没轮到执行，方法本身就先抛 NoClassDefFoundError 了。
 */
public interface IBrowserBackend {

    /**
     * 这个后端现在能用吗。
     *
     * 装了 MCEF 不等于能用：它要先下载约 200 MB 的 java-cef 原生库并初始化完成，
     * 这在玩家第一次进游戏时可能要等好几分钟。所以这个方法问的是"此时此刻能不能
     * 建浏览器"，不是"模组装没装"。
     */
    boolean isAvailable();

    /** 不可用时告诉玩家为什么。界面直接显示这句话 */
    Component unavailableReason();

    /**
     * 建一个浏览器。
     *
     * @param url    起始地址
     * @param width  宽，真实像素
     * @param height 高，真实像素
     * @return 浏览器；后端不可用时返回 null，调用方必须判空
     */
    IBrowser create(String url, int width, int height);
}
