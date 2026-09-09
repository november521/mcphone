package com.november.mcphone.feature.reader.client;

import com.november.mcphone.core.client.PhoneHud;
import com.november.mcphone.core.client.PhoneKeys;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 挂在 HUD 上看书时用两个键翻页 —— 边走边看的唯一一条路。
 *
 * <h2>为什么要绕开界面</h2>
 *
 * 手机一旦成了 {@code mc.screen}，原版就不吃移动键了，人站在原地。而"看小说"最想要的
 * 场景恰恰是边走边看、挂机时看：那时候手机挂在副手 HUD 上，而 HUD 上那副面孔
 * <b>收不到任何输入</b>——它不是 mc.screen，鼠标、键盘全都不经过它（见 {@link PhoneHud}
 * 的类注释）。
 *
 * 所以翻页这一件事单开一条不经过界面的路。只开这一件：翻页是看书时唯一的高频操作，
 * 目录、切章、换书都可以停下来做。
 *
 * <h2>为什么在 tick 里读键，而不是听按键事件</h2>
 *
 * 与 {@code PhoneKeyHandler} 同一个理由：{@code consumeClick} 会把积压的按下逐个取走，
 * 同一 tick 内连按两下不会丢，也不必自己处理"界面开着时不该响应"。
 */
public final class ReaderKeyHandler {

    private ReaderKeyHandler() {}

    /** 由 MCphoneClient 构造函数挂到游戏总线 */
    public static void onClientTick(ClientTickEvent.Post event) {
        // 无论如何都要把积压的点击取空，否则关掉界面后会补翻好几页
        boolean prev = false;
        while (PhoneKeys.READER_PREV.consumeClick()) prev = true;

        boolean next = false;
        while (PhoneKeys.READER_NEXT.consumeClick()) next = true;

        if (!prev && !next) return;

        Minecraft mc = Minecraft.getInstance();

        // 有界面开着就不管：手机界面自己有方向键与点击翻页，别的界面更轮不到我们插手
        if (mc.screen != null) return;

        PhoneScreen phone = PhoneHud.hudPhone();
        if (phone == null) return;

        // 同一 tick 里两个键都按了就当没按：这时候玩家的意图无从判断，
        // 随便挑一个的结果是"按了一下翻了一页又翻回来"
        if (prev == next) return;

        phone.turnReadingPage(next ? 1 : -1);
    }
}
