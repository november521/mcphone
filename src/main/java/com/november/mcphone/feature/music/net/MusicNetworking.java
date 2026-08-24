package com.november.mcphone.feature.music.net;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.feature.music.DiscService;
import com.november.mcphone.feature.music.DiscState;
import com.november.mcphone.feature.music.client.DiscClientCache;
import com.november.mcphone.feature.music.client.NetSongPlayback;
import com.november.mcphone.feature.music.menu.DiscBayContainer;
import com.november.mcphone.feature.music.menu.DiscBayMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
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

        // C2S: 打开唱片仓那个带背包的界面
        registrar.playToServer(
                OpenDiscBayPacket.TYPE,
                OpenDiscBayPacket.STREAM_CODEC,
                MusicNetworking::handleOpenBay
        );

        // S2C: 下发唱片仓现在是什么样
        registrar.playToClient(
                SyncDiscStatePacket.TYPE,
                SyncDiscStatePacket.STREAM_CODEC,
                MusicNetworking::handleSync
        );

        // S2C: 某个人开始 / 停止外放一首网络歌。
        // 与原版唱片那一支不同，这两个包是发给【听得见的每一个人】的，
        // 不只是放歌的那个 —— 外放的意义就在于周围人也听得见
        registrar.playToClient(
                PlayNetSongPacket.TYPE,
                PlayNetSongPacket.STREAM_CODEC,
                MusicNetworking::handlePlayNetSong
        );
        registrar.playToClient(
                StopNetSongPacket.TYPE,
                StopNetSongPacket.STREAM_CODEC,
                MusicNetworking::handleStopNetSong
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
     * 服务端收到：给玩家打开唱片仓的界面（唱片格 ＋ 他自己的背包）。
     *
     * 校验玩家身上确实带着手机 —— 包是客户端发的，不能信。没有这道检查，
     * 任何人改个客户端就能凭空开出一个能动自己背包的界面，"手机"这个前提
     * 形同虚设。判据与 DiscService 里那几处是同一个方法，见 DiscBayMenu
     * 的 stillValid：两处不一致会出现"服务端放你开、菜单自检又把你踢出去"。
     *
     * 唱片仓不是付费 App，所以不像末影箱那样还要查购买记录。
     */
    private static void handleOpenBay(OpenDiscBayPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!PhoneItem.isCarriedBy(player)) return;

            player.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, p) -> new DiscBayMenu(
                            containerId, inventory, new DiscBayContainer(player)),
                    Component.translatable("mcphone.container.disc_bay")));
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

    /**
     * 把服务端的真值打包。
     *
     * 下发的是外放的【终点刻】而不是"在不在放"，理由见 SyncDiscStatePacket
     * 那个字段的注释：布尔量会过期，而唱片放完时没有任何人会来通知客户端。
     */
    private static SyncDiscStatePacket stateOf(ServerPlayer player) {
        DiscState state = player.getData(ModAttachments.DISC.get());
        return new SyncDiscStatePacket(state.disc().copy(), DiscService.playingUntil(player));
    }

    /** 有人开始外放一首网络歌 —— 在自己这边把它放出来 */
    private static void handlePlayNetSong(PlayNetSongPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NetSongPlayback.start(packet.entityId(), packet.song()));
    }

    /** 有人停了 */
    private static void handleStopNetSong(StopNetSongPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NetSongPlayback.stop(packet.entityId()));
    }

    /** 收到状态，存进客户端缓存供界面读取 */
    private static void handleSync(SyncDiscStatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DiscClientCache.set(packet.disc(), packet.endsAtTick()));
    }
}
