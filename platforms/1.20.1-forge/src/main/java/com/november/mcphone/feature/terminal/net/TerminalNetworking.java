package com.november.mcphone.feature.terminal.net;

import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.terminal.TerminalOpener;
import com.november.mcphone.feature.terminal.client.TerminalSlotClient;
import net.minecraft.server.level.ServerPlayer;

/**
 * 「终端」App 的网络包 —— 一来一回两个。
 *
 * 注册与处理都在自己的类里，由 {@code NetworkHandler.register} 叫一次：那边只保留"注册总
 * 入口"这一个职责，和聊天、记事本、商店、音乐几支一样。
 *
 * 无条件注册，不看服务端装没装哪家存储模组：包的序号由注册顺序发放，两端必须对称，
 * 服务端少注册一个，后面所有包的序号就全平移了。装没装的判断放在处理函数走到的
 * {@link TerminalOpener} 里。
 */
public final class TerminalNetworking {

    private TerminalNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register() {
        // C2S: 玩家在手机里点了「终端」，或者点了卡槽界面上的「打开终端」
        MCphoneNetwork.registerToServer(
                TerminalActionPacket.class,
                TerminalActionPacket::encode,
                TerminalActionPacket::decode,
                TerminalNetworking::handleAction
        );

        // S2C: 卡槽里那台终端换了，或者玩家刚上线/重生/换维度
        MCphoneNetwork.registerToClient(
                SyncTerminalSlotPacket.class,
                SyncTerminalSlotPacket::encode,
                SyncTerminalSlotPacket::decode,
                TerminalNetworking::handleSync
        );
    }

    /**
     * 每个分支自己去查前提，查不到就什么都不做。客户端那边可能已经判断过一次（决定按钮亮
     * 不亮），但界面挡不住伪造的包。
     *
     * 线程与非空玩家由 {@code MCphoneNetwork.registerToServer} 保证：它用的是
     * {@code consumerMainThread}，走到这里已经在主线程上、player 也非空。
     */
    private static void handleAction(TerminalActionPacket packet, ServerPlayer player) {
        switch (packet.action()) {
            case OPEN_TERMINAL -> TerminalOpener.open(player);
            case OPEN_SLOT_MENU -> TerminalOpener.openSlotMenu(player);
        }
    }

    /**
     * 写进客户端玩家的那份数据。
     *
     * 转一道 {@link TerminalSlotClient} 而不是在这里直接 {@code Minecraft.getInstance()}：
     * 这个类专用服务端也会加载（注册包要它），碰客户端类型当场就崩。这条规矩由
     * build.gradle 的 verifyDistIsolation 把关，音乐那几个 S2C 包也是这么转的。
     */
    private static void handleSync(SyncTerminalSlotPacket packet) {
        TerminalSlotClient.apply(packet.terminal());
    }
}
