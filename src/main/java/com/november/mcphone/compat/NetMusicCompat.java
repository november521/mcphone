package com.november.mcphone.compat;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.november.mcphone.MCphone;
import com.november.mcphone.feature.music.NetSong;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.Optional;

/**
 * NetMusic（网络音乐机）兼容层 —— 让它刻出来的 CD 能塞进手机的唱片仓。
 *
 * 我们只做一件事：把 CD 上那点信息读出来
 *
 * 搜歌、登录、VIP、刻盘、歌词，全是 NetMusic 的，我们一样都不碰。玩家用
 * 它的电脑方块搜到歌、刻成 CD，然后把 CD 放进手机 —— 从那一刻起我们接手的
 * 也只有"放"这一件事。
 *
 * 这样分工不是客气。搜歌那套要处理登录态、加密、多个音源的接口差异，
 * 每一样都会随对方的服务端变；抄一份过来等于替别人维护。
 *
 * 它没有对外的 API 包，所以接触面必须小
 *
 * Waystones 有 {@code net.blay09.mods.waystones.api}，MCEF 有它的公开门面；
 * NetMusic 没有。我们用到的 {@link ItemMusicCD#getSongInfo} 在它的
 * {@code item} 包里，属于内部实现 —— 它改个方法名，我们就断。
 *
 * 所以这里只做两件事：认一认这是不是它的 CD、读出 url/歌名/时长。别的一律
 * 不碰，读出来立刻翻译成我们自己的 {@link NetSong}（理由见那个记录的类注释）。
 *
 * 整个模组里只有【两个】文件允许出现 NetMusic 的类型：本类，以及客户端那
 * 一半 {@code compat.client.NetMusicPlayback}（拉流与起播）。分成两个是因为
 * 它那些播放相关的类在自己的 client 包里，专用服务器上一碰就崩，而读 CD
 * 这件事两端都要做。
 *
 * "装没装"和"真去调"必须分在两个方法里
 *
 * 与 {@link WaystonesCompat} 逐字相同的规矩。光用 if 挡住不够 —— JVM 校验
 * 一个方法时就要解析它里面出现的类型，方法体压根没执行也会触发加载。所以
 * 碰对方类型的那几行必须单独成方法，只在确认装了之后才可能被调到。
 *
 * 兜的是 Throwable 而不是 Exception：对方改类名或方法签名时抛出来的是
 * NoClassDefFoundError / NoSuchMethodError，都属于 Error，Exception 接不住。
 * 兜住的代价是那张 CD 放不了；不兜的代价是网络包处理函数抛异常，而那条
 * 路径上的异常会被 NeoForge 当成协议错误直接把玩家踢下线。
 */
public final class NetMusicCompat {

    private NetMusicCompat() {}

    /** NetMusic 的 modid，见它 jar 里的 mods.toml */
    public static final String NETMUSIC_MODID = "netmusic";

    public static boolean isLoaded() {
        return ModList.get().isLoaded(NETMUSIC_MODID);
    }

    /**
     * 把 CD 上那首歌读出来。
     *
     * @return 不是 NetMusic 的 CD、CD 上没刻东西、或者没装 NetMusic，
     *         都返回空
     */
    public static Optional<NetSong> songOf(ItemStack stack) {
        if (stack.isEmpty() || !isLoaded()) return Optional.empty();

        try {
            return songOfInternal(stack);
        } catch (Throwable t) {
            // 理由见类注释：这里最可能的翻车方式是对方改了字段或签名，
            // 那抛出来的是 Error，不是 Exception
            MCphone.LOGGER.error("[MCphone] 读 NetMusic 的 CD 失败（版本可能不兼容）", t);
            return Optional.empty();
        }
    }

    /**
     * 真正碰 NetMusic 的地方。
     *
     * 单独一个方法，只在上面确认装了之后才会被调到 —— 理由见类注释，
     * 别把它并回去。
     */
    private static Optional<NetSong> songOfInternal(ItemStack stack) {
        // getSongInfo 自己就先比了物品类型，不是它那张 CD 直接返回 null，
        // 所以这里不必再判一次 stack.getItem()
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(stack);
        if (info == null) return Optional.empty();

        String url = info.songUrl;
        if (url == null || url.isBlank()) return Optional.empty();

        String title = info.songName;
        if (title == null || title.isBlank()) title = url;

        return Optional.of(new NetSong(
                clamp(url, NetSong.MAX_URL),
                clamp(title, NetSong.MAX_TITLE),
                Math.max(0, info.songTime)));
    }

    /**
     * 掐到长度上限。
     *
     * 这两个字符串会进网络包，而包体长度是有上限的（见 NetSong 的常量）。
     * 对方哪天允许了更长的歌名，我们这边不该因此发不出包。
     */
    private static String clamp(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
