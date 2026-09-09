package com.november.mcphone.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 玩家头像 —— 取的就是按 Tab 看到的那一份。
 *
 * 皮肤从哪来
 *
 * 在线玩家走 Tab 玩家列表（{@link PlayerInfo}）。那是客户端唯一持有
 * 别人皮肤的地方，不受维度与视距限制——好友在下界、在几千格外都照样
 * 显示，因为玩家列表本来就是全服共享的。绘制用原版
 * {@link PlayerFaceRenderer}，与 Tab 列表同一个类，帽子层一并画上。
 *
 * 离线玩家【没有】真皮肤：他们压根不在玩家列表里，客户端手上只剩一个
 * UUID。这时退回原版按 UUID 算出的默认皮肤，也就是 Steve 或 Alex，
 * 与在别处遇到无皮肤玩家时是同一个效果。
 *
 * 想让离线好友也显示真皮肤，得拿 UUID 异步查会话服务器再缓存一份，
 * 而离线模式的服务器上根本查不到。不值得为此把一个纯渲染的工具类
 * 变成带网络请求和缓存失效的东西。
 *
 * 尺寸只用 8 的整数倍
 *
 * 皮肤的头部区域是 8×8 像素。放大到 16、24 这样的整数倍，每个源像素
 * 恰好对应等大的方块，边缘锐利；放成 12 这种 1.5 倍，采样会让有的
 * 像素占 2 点、有的占 1 点，看上去毛糙。
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
        PlayerFaceRenderer.draw(g, skin(player), x, y, size);
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

        int dx = x + size - DOT_SIZE;
        int dy = y + size - DOT_SIZE;
        g.fill(dx - 1, dy - 1, dx + DOT_SIZE + 1, dy + DOT_SIZE + 1, COLOR_DOT_OUTLINE);
        g.fill(dx, dy, dx + DOT_SIZE, dy + DOT_SIZE, online ? COLOR_ONLINE : COLOR_OFFLINE);
    }

    /**
     * 在线的取真皮肤，离线的退回默认皮肤——理由见类注释。
     *
     * 1.21.1 那边 PlayerInfo.getSkin() 返回 PlayerSkin 记录（含皮肤、披风、
     * 模型类型）。PlayerSkin 是 1.20.2 才有的，1.20.1 上只有一个裸的
     * ResourceLocation，方法名也不同（getSkinLocation / getDefaultSkin）。
     * 头像只需要皮肤贴图，所以这处退化不影响显示。
     */
    private static ResourceLocation skin(UUID player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(player);
            if (info != null) return info.getSkinLocation();
        }
        return DefaultPlayerSkin.getDefaultSkin(player);
    }
}
