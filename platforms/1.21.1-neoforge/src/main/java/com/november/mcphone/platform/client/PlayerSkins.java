package com.november.mcphone.platform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 玩家头像用的皮肤贴图。
 *
 * <h2>为什么要有这个门面</h2>
 *
 * 1.20.2 起原版把皮肤抽成了 {@code PlayerSkin} 记录（贴图 + 披风 + 鞘翅 + 模型类型），
 * 取法也跟着改了名：{@code PlayerInfo.getSkin()} / {@code DefaultPlayerSkin.get(UUID)}。
 * 1.20.1 上那个记录<b>不存在</b>，只有一个裸的 {@link ResourceLocation}，
 * 方法叫 {@code getSkinLocation()} / {@code getDefaultSkin(UUID)}。
 *
 * <h2>为什么返回的是 ResourceLocation 而不是各支自己的类型</h2>
 *
 * 因为消费端两支同形：{@code PlayerFaceRenderer.draw(GuiGraphics, ResourceLocation, ...)}
 * 这个重载<b>两支都有</b>，而 1.21 那个收 {@code PlayerSkin} 的重载自己就是转发到它
 * （{@code draw(g, skin.texture(), ...)}）。所以把返回面收在 ResourceLocation 上不是
 * 「退化」，那本来就是两支共同的底。
 *
 * 头像只用得上皮肤贴图，披风与模型类型用不着 —— 真要用到那两样时这个门面得重新设计，
 * 别硬往里塞。
 */
public final class PlayerSkins {

    private PlayerSkins() {}

    /** 在线的取真皮肤，离线的退回默认皮肤。 */
    public static ResourceLocation faceTexture(UUID player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(player);
            if (info != null) return info.getSkin().texture();
        }
        return DefaultPlayerSkin.get(player).texture();
    }
}
