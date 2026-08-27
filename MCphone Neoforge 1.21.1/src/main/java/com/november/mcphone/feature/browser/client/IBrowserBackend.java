package com.november.mcphone.feature.browser.client;

import net.minecraft.network.chat.Component;

/**
 * 浏览器后端。没装 MCEF 时后端是 {@link BrowserBackends#NONE}，界面照常显示并告诉玩家缺什么。
 * 实现类会引用 MCEF 的类型，别在别处直接 new——统一走 {@link BrowserBackends#installDefault()}，
 * 那里把"装没装"的判断和"真去 new"分在两个方法里；写在同一个方法里，if 还没执行方法本身就先抛 NoClassDefFoundError。
 */
public interface IBrowserBackend {

    /** 此时此刻能不能建浏览器（MCEF 装了也可能还在下载原生库） */
    boolean isAvailable();

    /** 不可用时的原因，界面直接显示 */
    Component unavailableReason();

    /** 宽高是真实像素；后端不可用时返回 null，调用方必须判空 */
    IBrowser create(String url, int width, int height);
}
