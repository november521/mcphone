package com.november.mcphone.feature.music.net;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.feature.music.DiscService;
import com.november.mcphone.feature.music.DiscState;
import com.november.mcphone.feature.music.client.DiscClientCache;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 音乐相关网络包的注册与处理。
 *
 * 眼下只有唱片仓这一组：本地音乐全在客户端自己那儿，服务端不必知道玩家
 * 在听什么——那也是它比外放简单得多的原因。
 *
 * 本类只做传输层的事，真正的规则在 {@link DiscService}。
 */
public final class MusicNetworking {

    private MusicNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register(PayloadRegistrar registrar) {
        // C2S: 对唱片仓做一件事（放入 / 取出 / 播放停止 / 只是问一下）
        registrar.playToServer(
                DiscActionPacket.TYPE,
                DiscActionPacket.STREAM_CODEC,
                MusicNetworking::handleAction
        );

        // S2C: 下发唱片仓现在是什么样
        registrar.playToClient(
                SyncDiscStatePacket.TYPE,
                SyncDiscStatePacket.STREAM_CODEC,
                MusicNetworking::handleSync
        );
    }

    /**
     * 四个动作走同一个处理函数。
     *
     * 不论成没成，末尾一律回发一份最新状态：失败时回的也是真实状态，
     * 界面不会显示成功的假象——与加好友那边同一条规矩。
     */
    private static void handleAction(DiscActionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            DiscService.Outcome outcome = switch (packet.action()) {
                case INSERT -> DiscService.insert(player);
                case EJECT -> DiscService.eject(player);
                case TOGGLE -> DiscService.toggle(player);
                case QUERY -> DiscService.Outcome.OK;   // 只是来问一下，什么都不改
            };

            tell(player, outcome);
            ctx.reply(stateOf(player));
        });
    }

    /**
     * 把"为什么没成"用动作栏告诉玩家。
     *
     * 与好友那边同一套做法：界面上这几种失败长得一模一样（按钮闪一下，
     * 还是原样），不解释的话玩家只会反复点。
     *
     * OK 与 NOTHING 不说话：前者界面上看得见，后者是正常客户端走不到的
     * 路径（身上没手机、仓里没唱片），说了等于帮伪造客户端调试。
     */
    private static void tell(ServerPlayer player, DiscService.Outcome outcome) {
        String key = switch (outcome) {
            case NOT_A_DISC -> "mcphone.music.disc.not_a_disc";
            case OCCUPIED -> "mcphone.music.disc.occupied";
            case INVENTORY_FULL -> "mcphone.music.disc.inventory_full";
            case OK, NOTHING -> null;
        };
        if (key == null) return;

        player.displayClientMessage(Component.translatable(key), true);
    }

    /**
     * 主动把唱片仓的最新样子推给这名玩家。
     *
     * 平时不需要：客户端每次动作都会收到一份回执（见 handleAction）。但唱片
     * 仓的菜单界面走的是原版容器同步，不经过这里 —— 玩家在那儿放完唱片
     * 关掉界面，手机上那一条读的还是进菜单之前的快照。
     */
    public static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, stateOf(player));
    }

    /** 把服务端的真值打包。"在不在放"由服务端算，客户端不自己推 */
    private static SyncDiscStatePacket stateOf(ServerPlayer player) {
        DiscState state = player.getData(ModAttachments.DISC.get());
        return new SyncDiscStatePacket(state.disc().copy(), DiscService.isPlaying(player));
    }

    /** 收到状态，存进客户端缓存供界面读取 */
    private static void handleSync(SyncDiscStatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DiscClientCache.set(packet.disc(), packet.playing()));
    }
}
