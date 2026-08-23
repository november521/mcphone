package com.november.mcphone.feature.music;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.PhoneItem;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;

import java.util.Optional;

/**
 * 唱片外放 —— 「外放」那一半：周围人都听得见，声音跟着你走。
 *
 * ================================================================
 * 为什么这一半非得在服务端
 * ================================================================
 *
 * 要让【别人】听见，只有服务端说得动别人的客户端。而它能说的只有
 * "放某个已注册的音效"——把你硬盘上那个 mp3 的字节发过去是不现实的，
 * 别人电脑上也没有那个文件。唱片正好满足这个条件：它就是一个注册过的
 * 音效，服务端一句 minecraft:music_disc.cat，全场都听得见。
 *
 * 这也是为什么本地文件只能"耳机"、唱片才能"外放"。那是物理限制，
 * 不是设计选择。
 *
 * ================================================================
 * 声音跟着人走，不是钉在地上
 * ================================================================
 *
 * 用 Level.playSound(玩家, 实体, ...) 这个重载：原版会发一个绑在实体上的
 * 音效包，客户端那头的声源就跟着这个实体移动。效果正是你说的"扛着一台
 * 音符盒到处走"。
 *
 * 钉在坐标上的话，玩家走两步就把自己的音乐甩在身后了。
 *
 * ================================================================
 * 没有 tick，"在不在放"是算出来的
 * ================================================================
 *
 * 见 {@link DiscState} 的类注释：只存开始时刻，到没到点现算。原版音效
 * 放完自己就没了，服务端不必去停它——只有玩家主动停或者取出唱片时，
 * 才需要发一个停止包把已经在放的掐掉。
 *
 * ================================================================
 * 本类会被专用服务器加载，一个客户端类都不许出现
 * ================================================================
 */
public final class DiscService {

    private DiscService() {}

    /**
     * 外放音量。
     *
     * 4.0 是原版唱片机的音量（见 JukeboxBlockEntity），照抄它是为了"手机
     * 外放"和"旁边摆一台唱片机"听起来一样响——玩家对后者的音量早有预期。
     * 顺带决定了能传多远：原版音量大于 1 时会按倍数放大可听范围。
     */
    private static final float VOLUME = 4.0F;

    /** 结果：这次操作成没成，以及为什么没成 */
    public enum Outcome {
        /** 成了 */
        OK,
        /** 什么都没发生，也不必解释（正常客户端走不到：没手机、仓里没唱片） */
        NOTHING,
        /** 手上拿的不是唱片 */
        NOT_A_DISC,
        /** 仓里已经有一张了，得先取出来 */
        OCCUPIED,
        /** 背包满了，唱片还不回去 */
        INVENTORY_FULL
    }

    // ============================================================
    //  放入 / 取出
    // ============================================================

    /**
     * 把主手上的唱片放进手机。
     *
     * 只认主手：副手同时拿着别的东西时，"到底放的是哪只手"必须有个确定
     * 答案，而玩家点界面时看着的就是主手那张。
     */
    public static Outcome insert(ServerPlayer player) {
        if (!PhoneItem.isCarriedBy(player)) return Outcome.NOTHING;

        DiscState state = player.getData(ModAttachments.DISC.get());
        if (state.hasDisc()) return Outcome.OCCUPIED;

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (songOf(player, held).isEmpty()) return Outcome.NOT_A_DISC;

        // 只收一张：唱片本来就不叠，但别的模组的唱片未必守这条规矩
        ItemStack one = held.copyWithCount(1);
        held.shrink(1);

        player.setData(ModAttachments.DISC.get(), state.withDisc(one));
        return Outcome.OK;
    }

    /**
     * 把唱片还给玩家。
     *
     * 还不进背包就【不取出】，而不是丢在地上：玩家在整理界面，脚下突然
     * 掉个东西他多半不会注意，走两步就没了。让操作失败并说明原因，
     * 比悄悄把他的唱片扔了好。
     */
    public static Outcome eject(ServerPlayer player) {
        DiscState state = player.getData(ModAttachments.DISC.get());
        if (!state.hasDisc()) return Outcome.NOTHING;

        ItemStack disc = state.disc().copy();
        if (!player.getInventory().add(disc)) return Outcome.INVENTORY_FULL;

        stopSound(player, state);
        player.setData(ModAttachments.DISC.get(), DiscState.EMPTY);
        return Outcome.OK;
    }

    // ============================================================
    //  播放 / 停止
    // ============================================================

    /** 正在外放吗 —— 算出来的，见 DiscState 类注释 */
    public static boolean isPlaying(ServerPlayer player) {
        DiscState state = player.getData(ModAttachments.DISC.get());
        return isPlaying(player, state);
    }

    private static boolean isPlaying(ServerPlayer player, DiscState state) {
        if (state.startedTick() < 0 || !state.hasDisc()) return false;

        Optional<Holder<JukeboxSong>> song = songOf(player, state.disc());
        if (song.isEmpty()) return false;

        long elapsed = player.level().getGameTime() - state.startedTick();
        return elapsed >= 0 && elapsed < song.get().value().lengthInTicks();
    }

    /**
     * 播放键：没在放就放，在放就停。
     *
     * 没有"暂停继续"：原版音效系统只有开始和停止，没有从中间接着放这回事。
     * 界面上因此也只画播放和停止两态——给一个按下去会从头开始的"继续"，
     * 比不给更糟。
     */
    public static Outcome toggle(ServerPlayer player) {
        if (!PhoneItem.isCarriedBy(player)) return Outcome.NOTHING;

        DiscState state = player.getData(ModAttachments.DISC.get());
        if (!state.hasDisc()) return Outcome.NOTHING;

        if (isPlaying(player, state)) {
            stopSound(player, state);
            player.setData(ModAttachments.DISC.get(), state.stopped());
            return Outcome.OK;
        }

        Optional<Holder<JukeboxSong>> song = songOf(player, state.disc());
        if (song.isEmpty()) return Outcome.NOTHING;

        // 绑在玩家身上，声音就跟着他走。第一个参数传 null ＝ 包括他自己在内
        // 所有听得见的人都收到
        player.level().playSound(null, player,
                song.get().value().soundEvent().value(), SoundSource.RECORDS, VOLUME, 1.0F);

        player.setData(ModAttachments.DISC.get(),
                state.playingSince(player.level().getGameTime()));
        return Outcome.OK;
    }

    /**
     * 把已经在放的那一份掐掉。
     *
     * 停止包是按音效 ID 停的，也就是说：如果旁边正好有台唱片机在放【同一张】
     * 唱片，那台也会被这个包停掉——对收到包的那些客户端而言。这是原版
     * ClientboundStopSoundPacket 的粒度，没有"只停这一个声源"的选项。
     *
     * 只发给听得见的人：全服广播的话，一千格外的人也要处理一个与他无关的包。
     */
    private static void stopSound(ServerPlayer player, DiscState state) {
        if (state.startedTick() < 0) return;

        Optional<Holder<JukeboxSong>> song = songOf(player, state.disc());
        if (song.isEmpty()) return;

        SoundEvent sound = song.get().value().soundEvent().value();
        ClientboundStopSoundPacket packet =
                new ClientboundStopSoundPacket(sound.getLocation(), SoundSource.RECORDS);

        // 半径按外放音量算：原版音量大于 1 时可听范围按倍数放大，
        // 16 是基准距离
        double radius = 16.0D * VOLUME;
        double radiusSq = radius * radius;

        for (ServerPlayer nearby : ((ServerLevel) player.level()).players()) {
            if (nearby.distanceToSqr(player) <= radiusSq) {
                nearby.connection.send(packet);
            }
        }
    }

    // ============================================================
    //  内部
    // ============================================================

    /**
     * 这个物品是唱片吗；是的话给出它的曲子定义。
     *
     * 不按物品类型判断，而是查 JUKEBOX_PLAYABLE 组件——1.21 起"能不能塞进
     * 唱片机"是数据驱动的，别的模组的唱片、数据包自定义的唱片都认得。
     */
    private static Optional<Holder<JukeboxSong>> songOf(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        return JukeboxSong.fromStack(player.level().registryAccess(), stack);
    }
}
