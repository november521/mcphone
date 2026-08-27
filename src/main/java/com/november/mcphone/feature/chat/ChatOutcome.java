package com.november.mcphone.feature.chat;

import net.minecraft.network.chat.Component;

/**
 * 一次操作的结果，自带要跟玩家说的话；网络层只管送达。
 * 返回 Component 而不是翻译键，是因为有的话带参数，网络层不该知道每种结果要什么参数。
 */
public interface ChatOutcome {

    /** 返回 null 表示不必说：成功（界面看得见）、以及正常客户端走不到的路径（说了等于帮伪造客户端调试） */
    Component message();
}
