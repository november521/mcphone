package com.november.mcphone.platform.client;

import net.minecraft.client.Minecraft;
import com.november.mcphone.core.client.RememberedPlayerSkins;
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

    private static final RememberedPlayerSkins<ResourceLocation> MEMORY = new RememberedPlayerSkins<>(512);
    private static int ticksUntilRefresh;

    /** 原版皮肤读取的版本差异只留在这一处。 */
    private static ResourceLocation textureOf(PlayerInfo info) {
        return info.getSkin().body().texturePath();
    }

    private static void bind(Minecraft mc) {
        if (MEMORY.bindConnection(mc.getConnection())) ticksUntilRefresh = 0;
    }

    /** 每秒记住在线玩家资料，未打开聊天 App 时也可记住稍后下线的好友。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        bind(mc);
        if (mc.getConnection() == null || ticksUntilRefresh-- > 0) return;
        ticksUntilRefresh = 19;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            MEMORY.remember(info.getProfile().id(), () -> textureOf(info));
        }
    }

    /** 在线优先更新，好友下线后继续使用最近加载成功的皮肤。 */
    public static ResourceLocation faceTexture(UUID player) {
        Minecraft mc = Minecraft.getInstance();
        bind(mc);
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(player);
            if (info != null) MEMORY.remember(player, () -> textureOf(info));
        }
        return MEMORY.resolve(player, DefaultPlayerSkin.get(player).body().texturePath());
    }
}