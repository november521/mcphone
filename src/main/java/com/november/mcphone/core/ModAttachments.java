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

/** 玩家附着数据（Attachment）注册 —— 跟着玩家走的数据；跟着物品走的见 {@link ModDataComponents}，两人共有的见 ChatData。 */
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

    /** 聊天的已读进度。copyOnDeath：不然死一次所有会话都变未读 */
    public static final Supplier<AttachmentType<ChatReadState>> CHAT_READ = ATTACHMENT_TYPES.register(
            "chat_read_state",
            () -> AttachmentType.builder(() -> ChatReadState.DEFAULT)
                    .serialize(ChatReadState.CODEC)
                    .copyOnDeath()
                    .build()
    );

    /** 唱片仓里的唱片，以及是否正在外放。copyOnDeath：唱片是可掉落的真物品，不能死一次就没 */
    public static final Supplier<AttachmentType<DiscState>> DISC = ATTACHMENT_TYPES.register(
            "phone_disc",
            () -> AttachmentType.builder(() -> DiscState.EMPTY)
                    .serialize(DiscState.CODEC)
                    .copyOnDeath()
                    .build()
    );

    /** 记事本的全部笔记。copyOnDeath：不然死一次就清空 */
    public static final Supplier<AttachmentType<NoteList>> NOTES = ATTACHMENT_TYPES.register(
            "personal_notes",
            () -> AttachmentType.builder(() -> NoteList.EMPTY)
                    .serialize(NoteList.CODEC)
                    .copyOnDeath()
                    .build()
    );

    /** 玩家买过哪些 App。存服务端而非客户端 installed.json：购买要扣物品，客户端文件能被改写 */
    public static final Supplier<AttachmentType<PurchasedApps>> PURCHASED_APPS =
            ATTACHMENT_TYPES.register(
                    "purchased_apps",
                    () -> AttachmentType.builder(() -> PurchasedApps.EMPTY)
                            .serialize(PurchasedApps.CODEC)
                            .copyOnDeath()
                            .build()
            );
}
