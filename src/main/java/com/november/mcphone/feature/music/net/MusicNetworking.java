package com.november.mcphone.feature.music.net;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.core.net.RequestThrottle;
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

/** 音乐相关网络包的注册与处理。只做传输层的事，规则在 {@link DiscService} */
public final class MusicNetworking {

    private MusicNetworking() {}

    /** 由 NetworkHandler.register 调用 */
    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(
                DiscActionPacket.TYPE,
                DiscActionPacket.STREAM_CODEC,
                MusicNetworking::handleAction
        );

        registrar.playToServer(
                OpenDiscBayPacket.TYPE,
                OpenDiscBayPacket.STREAM_CODEC,
                MusicNetworking::handleOpenBay
        );

        registrar.playToClient(
                SyncDiscStatePacket.TYPE,
                SyncDiscStatePacket.STREAM_CODEC,
                MusicNetworking::handleSync
        );

        // 网络歌的开始 / 停止发给听得见的每一个人，不只是放歌的那个
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

    /** 四个动作走同一个处理函数。不论成没成，末尾一律回发一份真实状态 */
    private static void handleAction(DiscActionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            // 查询与按键分两个计时：打开 App 先来一个 QUERY，紧接着的按键不能被它挡掉
            if (!RequestThrottle.allow(player, packet.action() == DiscActionPacket.Action.QUERY
                    ? RequestThrottle.Kind.DISC_STATE
                    : RequestThrottle.Kind.DISC_ACTION)) {
                return;
            }

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

    /** 给玩家打开唱片仓界面。必须校验身上带着手机（包是客户端发的）；判据与 DiscBayMenu.stillValid 同一个方法 */
    private static void handleOpenBay(OpenDiscBayPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!PhoneItem.isCarriedBy(player)) return;
            if (!RequestThrottle.allow(player, RequestThrottle.Kind.DISC_ACTION)) return;

            player.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, p) -> new DiscBayMenu(
                            containerId, inventory, new DiscBayContainer(player)),
                    Component.translatable("mcphone.container.disc_bay")));
        });
    }

    /** 把"为什么没成"用动作栏告诉玩家。OK 与 NOTHING 不说：后者是正常客户端走不到的路径，说了等于帮伪造客户端调试 */
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

    /** 主动推一份最新状态。唱片仓菜单走原版容器同步不经过这里，关掉菜单时靠它刷新手机界面 */
    public static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, stateOf(player));
    }

    /** 打包服务端真值。下发的是外放的终点刻而不是布尔量，见 {@link DiscService#playingUntil} */
    private static SyncDiscStatePacket stateOf(ServerPlayer player) {
        DiscState state = player.getData(ModAttachments.DISC.get());
        return new SyncDiscStatePacket(state.disc().copy(), DiscService.playingUntil(player));
    }

    private static void handlePlayNetSong(PlayNetSongPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NetSongPlayback.start(packet.entityId(), packet.song()));
    }

    private static void handleStopNetSong(StopNetSongPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> NetSongPlayback.stop(packet.entityId()));
    }

    private static void handleSync(SyncDiscStatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DiscClientCache.set(packet.disc(), packet.endsAtTick()));
    }
}
