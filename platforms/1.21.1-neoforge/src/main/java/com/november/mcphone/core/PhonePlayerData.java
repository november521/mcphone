package com.november.mcphone.core;

import com.november.mcphone.feature.chat.ChatReadState;
import com.november.mcphone.feature.music.DiscState;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.settings.WallpaperData;
import com.november.mcphone.feature.store.PurchasedApps;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 跟着玩家走的手机数据 —— 六样东西的读写口；跟着物品走的见 {@link PhoneItemData}。
 *
 * <h2>为什么要这么一层，明明只是转调</h2>
 *
 * 因为<b>存在哪儿</b>这件事两个加载器上不一样，而<b>怎么用</b>是一样的。
 *
 * 这边（NeoForge 1.21.1）用的是 Data Attachment，见 {@link ModAttachments}；
 * 1.20.1 那一支上这套不存在，对应物是 Forge 的 Capability。原先二十多处调用点
 * 各写各的 {@code player.getData(ModAttachments.XXX.get())}，那二十多处在两支之间
 * 必然分叉；收进来之后，分叉只剩这个文件的方法体。
 *
 * <b>这一层不改存储。</b>这边仍然是 Attachment，存档格式一个字节没动。
 *
 * <h2>它长成这个形状，是为了和另一支对齐</h2>
 *
 * 那边是 {@code ModCapabilities.of(player).setNotes(...)} —— 一个 capability 对象
 * 挂在玩家身上，读写都从它走。所以这边也做成"取一个视图、再读写"，方法名逐字照抄，
 * 调用点因此能两边一样。
 *
 * 这边的 {@code of} 返回的是个<b>临时视图</b>（只裹着 player 一个字段），不持有状态，
 * 拿了就用、用完就丢，别存起来。那边返回的才是真的存储对象。
 *
 * <h2>死亡与换维度：六样里有一样不一样，这是最容易漏的地方</h2>
 *
 * <table border="1">
 *   <caption>各字段的保留策略</caption>
 *   <tr><th>字段</th><th>{@code copyOnDeath}</th><th>为什么</th></tr>
 *   <tr><td>壁纸 wallpaper</td><td><b>没标</b></td><td>死了重来一次也无所谓，它只是个偏好</td></tr>
 *   <tr><td>已读进度 chatRead</td><td>标了</td><td>不然死一次所有会话都变未读</td></tr>
 *   <tr><td>唱片仓 disc</td><td>标了</td><td>唱片是可掉落的真物品，不能死一次就没</td></tr>
 *   <tr><td>笔记 notes</td><td>标了</td><td>不然死一次就清空</td></tr>
 *   <tr><td>终端卡槽 terminal</td><td>标了</td><td>同唱片仓，手机里的东西不该因为死一次就没</td></tr>
 *   <tr><td>已购 App purchasedApps</td><td>标了</td><td>花过的代价不能白花</td></tr>
 * </table>
 *
 * ⚠ <b>这张表在 1.20.1 那一支上不能照抄</b>，两边的默认行为不对称：
 *
 * <ul>
 *   <li>这边 {@code AttachmentType} 的 {@code copyOnDeath()} <b>只管死亡</b>，
 *       从末地返回时<b>一律保留</b>，标没标都一样（NeoForge 的类注释写着
 *       "not copied on death by default, but they are copied when returning from the end"）。
 *   <li>那边 Forge 的 {@code PlayerEvent.Clone} <b>两种情况都会触发</b>。
 * </ul>
 *
 * 于是"没标 copyOnDeath"在那边<b>不等于"什么都不用做"</b>：壁纸仍然要在
 * {@code !isWasDeath()} 时拷过去，只在真死亡时才不拷。把"没标"读成"不用监听 Clone"，
 * 玩家打完末影龙走传送门回主世界就会发现壁纸没了 —— 而这边不会。
 * 这是一处<b>只在特定路径上才发作</b>的分叉，编译器与测试都够不到。
 *
 * <h2>那边照抄这个形状就行</h2>
 *
 * 两支用的其实是<b>同一个事件</b>，NeoForge 只是把它藏起来了 ——
 * {@code net.neoforged.neoforge.attachment.AttachmentInternals} 里：
 *
 * <pre>{@code
 * @SubscribeEvent
 * public static void onPlayerClone(PlayerEvent.Clone event) {
 *     event.getEntity().copyAttachmentsFrom(event.getOriginal(), event.isWasDeath());
 * }
 * // copyEntityAttachments 里挑字段的那一句：
 * isDeath ? type -> type.copyOnDeath : type -> true
 * }</pre>
 *
 * 也就是说差别不在"用不用 Clone 事件"，只在<b>默认策略</b>：这边死亡时按
 * {@code copyOnDeath} 过滤、非死亡时全拷；那边没有这层默认，得自己写出来。
 * 照上面那两句的形状实现，六个字段的行为就和这边一致。
 *
 * <b>往这里加字段时，两件事一起做</b>：这张表补一行，那一支的 clone 处理补一段。
 * 漏了后者的症状是"死一次某样东西没了"。
 */
public final class PhonePlayerData {

    private final Player player;

    private PhonePlayerData(Player player) {
        this.player = player;
    }

    /** 取这个玩家的手机数据视图。拿了就用，别存起来 —— 它不持有状态，只裹着 player */
    public static PhonePlayerData of(Player player) {
        return new PhonePlayerData(player);
    }

    // ---- 壁纸 ----

    public WallpaperData wallpaper() {
        return player.getData(ModAttachments.WALLPAPER.get());
    }

    public void setWallpaper(WallpaperData value) {
        player.setData(ModAttachments.WALLPAPER.get(), value);
    }

    // ---- 聊天已读进度 ----

    public ChatReadState chatRead() {
        return player.getData(ModAttachments.CHAT_READ.get());
    }

    public void setChatRead(ChatReadState value) {
        player.setData(ModAttachments.CHAT_READ.get(), value);
    }

    // ---- 唱片仓 ----

    public DiscState disc() {
        return player.getData(ModAttachments.DISC.get());
    }

    public void setDisc(DiscState value) {
        player.setData(ModAttachments.DISC.get(), value);
    }

    // ---- 笔记 ----

    public NoteList notes() {
        return player.getData(ModAttachments.NOTES.get());
    }

    public void setNotes(NoteList value) {
        player.setData(ModAttachments.NOTES.get(), value);
    }

    // ---- 已购 App ----

    public PurchasedApps purchasedApps() {
        return player.getData(ModAttachments.PURCHASED_APPS.get());
    }

    public void setPurchasedApps(PurchasedApps value) {
        player.setData(ModAttachments.PURCHASED_APPS.get(), value);
    }

    // ---- 终端卡槽 ----

    // ⚠ 下面这两个是给 {@code feature.terminal.TerminalSlot} 用的，别处请走它。
    //
    // 那个类不只是转调：它还管同步、以及"内容原地改过了推一次同步"这件事，而终端那份
    // ItemStack 有一条别处没有的约束（AE2 与 RS 会往它上面【写】耗电与界面设置，所以
    // 拿到的必须是活的那一个、不能 copy()）。那条约束只写在 TerminalSlot 上，
    // 这里不抄第二份 —— 它属于那种写错了不报任何错的东西，两份注释迟早对不上。

    public ItemStack terminal() {
        return player.getData(ModAttachments.PHONE_TERMINAL.get());
    }

    public void setTerminal(ItemStack value) {
        player.setData(ModAttachments.PHONE_TERMINAL.get(), value);
    }
}
