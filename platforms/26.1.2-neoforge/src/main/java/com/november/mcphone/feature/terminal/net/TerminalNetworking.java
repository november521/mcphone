package com.november.mcphone.feature.terminal.net;

import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.terminal.TerminalOpener;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 「终端」App 的网络包 —— 一个包，客户端 → 服务端，动作写在枚举里。
 *
 * 注册与处理都在自己的类里，由 {@code NetworkHandler.register} 叫一次：那边只保留"注册总
 * 入口"这一个职责，和聊天、记事本、商店、音乐几支一样。
 *
 * 无条件注册，不看服务端装没装哪家存储模组：包类型的注册两端必须对称，服务端少注册一个，
 * 客户端发来时它会因为不认识而把玩家踢下线。装没装的判断放在处理函数走到的
 * {@link TerminalOpener} 里。
 */
public final class TerminalNetworking {

    private TerminalNetworking() {}

    public static void register(PayloadRegistrar registrar) {
        // C2S: 玩家在手机里点了「终端」，或者点了卡槽界面上的「打开终端」
        MCphoneNetwork.registerToServer(
                registrar,
                TerminalActionPacket.TYPE,
                TerminalActionPacket.STREAM_CODEC,
                TerminalNetworking::handle
        );
    }

    /**
     * enqueueWork 不能省：处理函数在网络线程上被调到，而开菜单、动背包都要碰玩家、碰世界，
     * 那些必须回到主线程。
     *
     * 每个分支自己去查前提，查不到就什么都不做。客户端那边可能已经判断过一次（决定按钮亮
     * 不亮），但界面挡不住伪造的包。
     */
    private static void handle(TerminalActionPacket packet, ServerPlayer player) {
        switch (packet.action()) {
            case OPEN_TERMINAL -> TerminalOpener.open(player);
            case OPEN_SLOT_MENU -> TerminalOpener.openSlotMenu(player);
        }
    }
}
