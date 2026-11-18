package com.november.mcphone.feature.terminal.client;

import com.november.mcphone.core.ModAttachments;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端那份"手机终端卡槽里是什么"。
 *
 * 服务端在写入与登录时都会推 {@code SyncPhoneTerminalPacket}（Fabric 1.21.1
 * 的附件没有自动同步），收到后把值写到本地玩家的附件上。接收器由
 * {@code NetworkHandler.registerToClient} 登记（客户端启动时领走），处理函数体
 * 转调 {@link #apply}。这个类单独存在是因为 {@code apply} 要碰 {@code Minecraft}
 * —— 那是客户端类型，按仓库规矩必须住在路径带 {@code /client/} 的类里。
 */
@Environment(EnvType.CLIENT)
public final class TerminalClientState {

    private TerminalClientState() {}

    /** 把服务端推来的终端写到本地玩家附件上。本地玩家还没进来时静默丢弃 */
    public static void apply(ItemStack terminal) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.setAttached(ModAttachments.PHONE_TERMINAL, terminal);
        }
    }
}
