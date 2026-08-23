package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatReadState;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.settings.WallpaperData;
import com.november.mcphone.feature.store.PurchasedApps;
import com.november.mcphone.feature.music.DiscState;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * 玩家附着数据（Attachment）注册。
 *
 * 附件存"跟着玩家走"的东西：壁纸偏好、联系人列表这类。
 * 与之相对，{@link ModDataComponents} 存"跟着物品走"的东西（如设备名），
 * 而两个玩家之间的数据（聊天记录）既不属于人也不属于物，
 * 存在世界存档里，见 {@link com.november.mcphone.feature.chat.ChatData}。
 *
 * 注册入口在 MCphone 构造函数中挂到模组总线。
 */
public final class ModAttachments {

    private ModAttachments() {}

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MCphone.MODID);

    /** 玩家选择的壁纸文件名 */
    public static final Supplier<AttachmentType<WallpaperData>> WALLPAPER = ATTACHMENT_TYPES.register(
            "wallpaper_data",
            () -> AttachmentType.builder(() -> WallpaperData.DEFAULT)
                    .serialize(WallpaperData.CODEC)
                    .build()
    );

    /**
     * 聊天的已读进度。
     *
     * 好友关系不在这里——它是双向的、同时属于两个人，见 FriendData。
     * 这里只留纯属个人私事的已读进度。
     *
     * copyOnDeath：玩家死亡重生时附件默认会丢。已读进度丢了的话，
     * 死一次所有会话都变成未读，红点糊一屏——那不是惩罚，是数据损坏。
     */
    public static final Supplier<AttachmentType<ChatReadState>> CHAT_READ = ATTACHMENT_TYPES.register(
            "chat_read_state",
            () -> AttachmentType.builder(() -> ChatReadState.DEFAULT)
                    .serialize(ChatReadState.CODEC)
                    .copyOnDeath()
                    .build()
    );

    /**
     * 手机唱片仓里的唱片，以及它是不是正在外放。
     *
     * 存在玩家身上而不是手机物品上：与笔记同一个理由——物品组件要参与
     * 容器每 tick 的快照比对，还会随任何一次容器同步整份下发，而唱片仓
     * 绝大多数时候没人在看。
     *
     * copyOnDeath：死一次唱片就没了，那不是惩罚，是把玩家的东西吞掉。
     * 唱片是可以掉落的物品，凭空消失比掉在地上更糟——后者他还能捡回来。
     */
    public static final Supplier<AttachmentType<DiscState>> DISC = ATTACHMENT_TYPES.register(
            "phone_disc",
            () -> AttachmentType.builder(() -> DiscState.EMPTY)
                    .serialize(DiscState.CODEC)
                    .copyOnDeath()
                    .build()
    );

    /**
     * 记事本的全部笔记 —— 跟着玩家走。
     *
     * 笔记只存在这里，不挂在手机物品上：物品组件要参与容器每 tick 的
     * 快照比对，还会随任何一次容器同步整份下发。手机揣在兜里的时候，
     * 玩家绝大多数时候并没有在看笔记，没理由让它一直占着同步链路。
     *
     * copyOnDeath：死一次日记本就空了，那不是惩罚，是数据损坏。
     * 已读进度那边是同一个理由。
     */
    public static final Supplier<AttachmentType<NoteList>> NOTES = ATTACHMENT_TYPES.register(
            "personal_notes",
            () -> AttachmentType.builder(() -> NoteList.EMPTY)
                    .serialize(NoteList.CODEC)
                    .copyOnDeath()
                    .build()
    );

    /**
     * 玩家在应用商店买过哪些 App。
     *
     * 存在服务端而不是客户端的 installed.json 里：购买要扣真物品，而那个
     * 文件就在玩家自己的电脑上，能被改写的话价格就形同虚设。安装状态
     * （主屏摆哪几个图标）仍然是客户端偏好，两者是不同的东西。
     *
     * copyOnDeath：买 App 花掉的末影箱是真的，死一次就要重买属于数据损坏，
     * 与笔记、已读进度同一个理由。
     */
    public static final Supplier<AttachmentType<PurchasedApps>> PURCHASED_APPS =
            ATTACHMENT_TYPES.register(
                    "purchased_apps",
                    () -> AttachmentType.builder(() -> PurchasedApps.EMPTY)
                            .serialize(PurchasedApps.CODEC)
                            .copyOnDeath()
                            .build()
            );
}
