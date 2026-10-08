package com.november.mcphone.core.client;

import net.minecraft.client.gui.GuiGraphics;
import com.november.mcphone.platform.client.PlayerSkins;
import net.minecraft.client.gui.components.PlayerFaceRenderer;

import java.util.UUID;

/**
 * 玩家头像 —— 取的就是按 Tab 看到的那一份。
 *
 * 皮肤从哪来
 *
 * 在线玩家走 Tab 玩家列表。那是客户端持有
 * 别人皮肤的地方，不受维度与视距限制——好友在下界、在几千格外都照样
 * 显示，因为玩家列表本来就是全服共享的。绘制用原版
 * {@link PlayerFaceRenderer}，与 Tab 列表同一个类，帽子层一并画上。
 *
 * 玩家下线后由 PlayerSkins 保留最近见过的资料与已加载皮肤，不再直接退回默认头像。
 * 尚未见过资料的离线 UUID 才使用原版默认皮肤。下载和贴图注册仍由原版皮肤管理器负责。
 *
 * 尺寸优先用 8 的整数倍
 *
 * 皮肤的头部区域是 8×8 像素。放大到 16、24 这样的整数倍，每个源像素
 * 恰好对应等大的方块，边缘锐利；放成 12 这种 1.5 倍，采样会让有的
 * 像素占 2 点、有的占 1 点。聊天页为了给文字让出空间使用 12；仍按最近邻显示，不引入平滑模糊。
 */
public final class PlayerAvatar {

    private PlayerAvatar() {}

    /** 在线状态点的边长 */
    private static final int DOT_SIZE = 4;

    /** 状态点周围那圈描边的颜色，与手机屏幕底色一致 */
    private static final int COLOR_DOT_OUTLINE = PhoneTheme.COLOR_SCREEN_BG;

    public static final int COLOR_ONLINE = PhoneTheme.COLOR_ONLINE;
    public static final int COLOR_OFFLINE = PhoneTheme.COLOR_OFFLINE;

    /** 画头像 */
    public static void draw(GuiGraphics g, UUID player, int x, int y, int size) {
        PlayerFaceRenderer.draw(g, PlayerSkins.faceTexture(player), x, y, size);
    }

    /**
     * 画头像，并在右下角盖一个在线状态点。
     *
     * 状态点先铺一圈深色描边再上色：浅色皮肤上直接画绿点会和头像糊在
     * 一起，看不出那是个状态指示。
     */
    public static void drawWithStatus(GuiGraphics g, UUID player,
                                      int x, int y, int size, boolean online) {
        draw(g, player, x, y, size);

        // 小头像的状态点也随之收紧；原有 16 像素头像仍是 4 像素状态点。
        int dot = Math.min(DOT_SIZE, Math.max(2, size / 4));
        int dx = x + size - dot;
        int dy = y + size - dot;
        g.fill(dx - 1, dy - 1, dx + dot + 1, dy + dot + 1, COLOR_DOT_OUTLINE);
        g.fill(dx, dy, dx + dot, dy + dot, online ? COLOR_ONLINE : COLOR_OFFLINE);
    }
}
