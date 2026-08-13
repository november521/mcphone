package com.november.mcphone;

import com.november.mcphone.chat.ChatReadState;
import com.november.mcphone.network.WallpaperData;
import com.november.mcphone.notes.NoteList;
import com.november.mcphone.notes.NoteScope;
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
 * 存在世界存档里，见 {@link com.november.mcphone.chat.ChatData}。
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
     * 私人笔记 —— 跟着玩家走的那一半记事本。
     *
     * 另一半（本机笔记）跟着手机走，存在物品的数据组件里，
     * 见 {@link ModDataComponents#NOTES}。两者的分别见 {@link NoteScope}。
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
}
