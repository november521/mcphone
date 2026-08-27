package com.november.mcphone.feature.music;

import com.november.mcphone.compat.NetMusicCompat;
import com.november.mcphone.core.ModCapabilities;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.feature.music.net.PlayNetSongPacket;
import com.november.mcphone.feature.music.net.StopNetSongPacket;
import net.minecraft.core.RegistryAccess;
import com.november.mcphone.core.net.MCphoneNetwork;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 唱片外放的服务端逻辑：周围人都听得见，声源绑在玩家实体上跟着走。
 * 只有已注册的音效（原版唱片）或网上的歌（NetMusic CD）能外放，本地文件做不到。
 * 本类会被专用服务器加载，不许出现客户端类。
 */
public final class DiscService {

    private DiscService() {}

    /** 外放音量，照抄原版唱片机（JukeboxBlockEntity）；大于 1 时可听范围按倍数放大 */
    private static final float VOLUME = 4.0F;

    /**
     * 谁听见了这一份外放：key 放歌的人，value 收到开始包的人（只记 UUID，发时现查）。
     * 停止包必须发给当初听见开始的人，不能到停止时再按距离挑：放歌的人可能已走远或换了维度；
     * 也不能全服广播：原版停止包按音效 ID 停，会把别处放同一张唱片的人一起停掉。
     */
    private static final Map<UUID, Set<UUID>> LISTENERS = new ConcurrentHashMap<>();

    /** 结果：这次操作成没成，以及为什么没成 */
    public enum Outcome {
        OK,
        /** 什么都没发生，也不必解释（正常客户端走不到：没手机、仓里没唱片） */
        NOTHING,
        NOT_A_DISC,
        OCCUPIED,
        INVENTORY_FULL
    }

    /** 把主手上的唱片放进手机；只认主手 */
    public static Outcome insert(ServerPlayer player) {
        if (!PhoneItem.isCarriedBy(player)) return Outcome.NOTHING;

        DiscState state = ModCapabilities.of(player).disc();
        if (state.hasDisc()) return Outcome.OCCUPIED;

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!isPlayableDisc(player.level().registryAccess(), held)) return Outcome.NOT_A_DISC;

        // 只收一张：唱片本来就不叠，但别的模组的唱片未必守这条规矩
        ItemStack one = held.copyWithCount(1);
        held.shrink(1);

        ModCapabilities.of(player).setDisc(state.withDisc(one));
        return Outcome.OK;
    }

    /**
     * 把唱片还给玩家。背包塞不下就不取出（INVENTORY_FULL），不丢地上。
     * 与 insert、toggle 一样要求手机在身上：包是公开接口，判据要与界面前提一致。
     */
    public static Outcome eject(ServerPlayer player) {
        if (!PhoneItem.isCarriedBy(player)) return Outcome.NOTHING;

        DiscState state = ModCapabilities.of(player).disc();
        if (!state.hasDisc()) return Outcome.NOTHING;

        ItemStack disc = state.disc().copy();
        if (!player.getInventory().add(disc)) return Outcome.INVENTORY_FULL;

        stopSound(player, state);
        ModCapabilities.of(player).setDisc(DiscState.EMPTY);
        return Outcome.OK;
    }

    /**
     * 外放会放到哪一个游戏刻为止；没在放则是 {@link DiscState#NOT_PLAYING}。
     * 下发终点而不是布尔量：服务端没有 tick 盯着唱片放完，布尔量会无声过期，客户端拿终点自己算。
     */
    public static long playingUntil(ServerPlayer player) {
        return playingUntil(player, ModCapabilities.of(player).disc());
    }

    private static long playingUntil(ServerPlayer player, DiscState state) {
        if (state.startedTick() < 0 || !state.hasDisc()) return DiscState.NOT_PLAYING;

        long length = lengthInTicks(player, state.disc());
        if (length <= 0L) return DiscState.NOT_PLAYING;

        long now = player.level().getGameTime();
        long ends = state.startedTick() + length;

        // now < startedTick 只在游戏刻倒流（存档回滚）时出现，当作没在放，否则那张唱片再也停不下来
        if (now < state.startedTick() || now >= ends) return DiscState.NOT_PLAYING;
        return ends;
    }

    private static boolean isPlaying(ServerPlayer player, DiscState state) {
        return playingUntil(player, state) != DiscState.NOT_PLAYING;
    }

    /** 仓里那张东西有多长（游戏刻）；NetMusic CD 的秒数也换算到刻。不认识返回 -1 */
    private static long lengthInTicks(ServerPlayer player, ItemStack disc) {
        Optional<RecordItem> vanilla = songOf(player, disc);
        if (vanilla.isPresent()) return vanilla.get().getLengthInTicks();

        return NetMusicCompat.songOf(disc).map(NetSong::lengthInTicks).orElse(-1L);
    }

    /** 播放键：没在放就放，在放就停。没有暂停：原版音效系统只有开始和停止 */
    public static Outcome toggle(ServerPlayer player) {
        if (!PhoneItem.isCarriedBy(player)) return Outcome.NOTHING;

        DiscState state = ModCapabilities.of(player).disc();
        if (!state.hasDisc()) return Outcome.NOTHING;

        if (isPlaying(player, state)) {
            stopSound(player, state);
            ModCapabilities.of(player).setDisc(state.stopped());
            return Outcome.OK;
        }

        if (!startSound(player, state.disc())) return Outcome.NOTHING;

        ModCapabilities.of(player).setDisc(state.playingSince(player.level().getGameTime()));
        return Outcome.OK;
    }

    /**
     * 开始外放仓里那张东西：原版唱片由服务端直接放音效，NetMusic CD 只广播地址让各客户端自己拉。
     * 放不了（不是唱片、CD 没刻东西、NetMusic 没装）返回 false。
     */
    private static boolean startSound(ServerPlayer player, ItemStack disc) {
        Optional<RecordItem> vanilla = songOf(player, disc);
        if (vanilla.isPresent()) {
            // 名单要在发声之前记；半径公式与原版 playSound 挑收件人的一致，两边是同一批人
            rememberAudience(player);

            // 绑在玩家实体上声音才跟着走；第一个参数传 null ＝ 他自己也收到
            player.level().playSound(null, player,
                    vanilla.get().getSound(),
                    SoundSource.RECORDS, VOLUME, 1.0F);
            return true;
        }

        return NetMusicCompat.songOf(disc)
                .map(song -> {
                    sendTo(rememberAudience(player),
                            new PlayNetSongPacket(player.getId(), song));
                    return true;
                })
                .orElse(false);
    }

    /** 算出此刻听得见的人，记进名单并返回。每次整份换掉，不累加 */
    private static List<ServerPlayer> rememberAudience(ServerPlayer player) {
        List<ServerPlayer> audience = nearbyPlayers(player);

        Set<UUID> ids = new HashSet<>(audience.size());
        for (ServerPlayer p : audience) ids.add(p.getUUID());
        LISTENERS.put(player.getUUID(), ids);

        return audience;
    }

    /** 当初听见开始且还在线的人。名单不在（服务端重启后从存档读回的"正在外放"）就退回按距离挑 */
    private static List<ServerPlayer> audienceOf(ServerPlayer player) {
        Set<UUID> ids = LISTENERS.get(player.getUUID());
        if (ids == null) return nearbyPlayers(player);

        var server = player.getServer();
        if (server == null) return List.of();

        List<ServerPlayer> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** 这个物品能不能放进唱片仓。唱片格与主手放入两处共用这一个判据，不许各写各的 */
    public static boolean isPlayableDisc(RegistryAccess registries, ItemStack stack) {
        if (stack.isEmpty()) return false;
        // registries 这个参数在 1.20.1 上用不着（判据是物品类型不是注册表），
        // 但签名保持与那一支一致：调用点两边就能长一个样，日后对照少一处噪音
        return stack.getItem() instanceof RecordItem
                || NetMusicCompat.songOf(stack).isPresent();
    }

    /** 此刻听得见的人。只在【开始】那一刻用，停止走的是名单 */
    private static List<ServerPlayer> nearbyPlayers(ServerPlayer player) {
        double radiusSq = audibleRadius() * audibleRadius();

        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer nearby : ((ServerLevel) player.level()).players()) {
            if (nearby.distanceToSqr(player) <= radiusSq) out.add(nearby);
        }
        return out;
    }

    private static void sendTo(List<ServerPlayer> audience, Object packet) {
        for (ServerPlayer p : audience) MCphoneNetwork.sendToPlayer(p, packet);
    }

    /** 玩家下线时丢掉他的听众名单。由 MCphone 构造函数显式挂到游戏总线上；漏挂没有症状，只是这张表再也不缩小 */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LISTENERS.remove(event.getEntity().getUUID());
    }

    /** 外放能传多远：16 是基准距离，音量大于 1 按倍数放大，与 Level.playSound 挑收件人的算法一致 */
    private static double audibleRadius() {
        return 16.0D * VOLUME;
    }

    /**
     * 掐掉正在外放的那一份并记成"没在放"。换碟与取出唱片都要走这里。
     * 必须趁旧唱片还在仓里时调：停止包是按那张唱片的音效 ID 发的。
     */
    public static void stopPlayback(ServerPlayer player) {
        DiscState state = ModCapabilities.of(player).disc();
        if (state.startedTick() < 0) return;

        stopSound(player, state);
        ModCapabilities.of(player).setDisc(state.stopped());
    }

    /** 把已经在放的那一份掐掉。原版停止包按音效 ID 停，旁边放同一张唱片的唱片机也会被停，原版粒度如此 */
    private static void stopSound(ServerPlayer player, DiscState state) {
        if (state.startedTick() < 0) return;

        // 发给当初听见开始的那些人，不是此刻在附近的那些人。理由见 LISTENERS
        List<ServerPlayer> audience = audienceOf(player);
        LISTENERS.remove(player.getUUID());

        Optional<RecordItem> vanilla = songOf(player, state.disc());
        if (vanilla.isPresent()) {
            SoundEvent sound = vanilla.get().getSound();
            ClientboundStopSoundPacket packet =
                    new ClientboundStopSoundPacket(sound.getLocation(), SoundSource.RECORDS);

            for (ServerPlayer p : audience) p.connection.send(packet);
            return;
        }

        // NetMusic 的 CD 停的是我们自己的声源，按实体停，停得准
        if (NetMusicCompat.songOf(state.disc()).isPresent()) {
            sendTo(audience, new StopNetSongPacket(player.getId()));
        }
    }

    /**
     * 这个物品是唱片吗；是的话给出那件唱片物品。
     *
     * 【与 1.21.1 那一支的判据不同，这里要留意】：那边查的是
     * JUKEBOX_PLAYABLE 数据组件（1.21 才有），所以别的模组、甚至数据包只靠
     * json 定义的唱片它都认得。1.20.1 上没有那个组件，曲长与音效直接挂在
     * RecordItem 这个【类】上，只能按类型判 —— 别的模组若没继承 RecordItem
     * 而是自己实现一套，这边就认不出来，那边能。
     *
     * 这是 1.20.1 的天花板，不是偷懒。真要覆盖到那种唱片，得另开一个
     * 按 item tag 判的口子。
     */
    private static Optional<RecordItem> songOf(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        return stack.getItem() instanceof RecordItem record
                ? Optional.of(record) : Optional.empty();
    }
}
