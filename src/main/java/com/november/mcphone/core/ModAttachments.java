package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatReadState;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.settings.WallpaperData;
import com.november.mcphone.feature.store.PurchasedApps;
import com.november.mcphone.feature.music.DiscState;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;

/**
 * 玩家附着数据（Attachment）注册 —— 跟着玩家走的数据；跟着物品走的见
 * {@link ModDataComponents}，两人共有的见 ChatData。
 *
 * Fabric 侧用 fabric-data-attachment-api-v1 的 {@link AttachmentRegistry}，
 * 语义与 NeoForge 的 AttachmentType 对齐：persistent(codec) 让它随玩家存档
 * 落盘，copyOnDeath() 保持死一次数据不清空。
 */
public final class ModAttachments {

    private ModAttachments() {}

    /** 玩家选择的壁纸文件名 */
    public static final AttachmentType<WallpaperData> WALLPAPER =
            AttachmentRegistry.<WallpaperData>builder()
                    .initializer(() -> WallpaperData.DEFAULT)
                    .persistent(WallpaperData.CODEC)
                    .buildAndRegister(id("wallpaper_data"));

    /** 聊天的已读进度。copyOnDeath：不然死一次所有会话都变未读 */
    public static final AttachmentType<ChatReadState> CHAT_READ =
            AttachmentRegistry.<ChatReadState>builder()
                    .initializer(() -> ChatReadState.DEFAULT)
                    .persistent(ChatReadState.CODEC)
                    .copyOnDeath()
                    .buildAndRegister(id("chat_read_state"));

    /** 唱片仓里的唱片，以及是否正在外放。copyOnDeath：唱片是可掉落的真物品，不能死一次就没 */
    public static final AttachmentType<DiscState> DISC =
            AttachmentRegistry.<DiscState>builder()
                    .initializer(() -> DiscState.EMPTY)
                    .persistent(DiscState.CODEC)
                    .copyOnDeath()
                    .buildAndRegister(id("phone_disc"));

    /** 记事本的全部笔记。copyOnDeath：不然死一次就清空 */
    public static final AttachmentType<NoteList> NOTES =
            AttachmentRegistry.<NoteList>builder()
                    .initializer(() -> NoteList.EMPTY)
                    .persistent(NoteList.CODEC)
                    .copyOnDeath()
                    .buildAndRegister(id("personal_notes"));

    /** 玩家买过哪些 App。存服务端而非客户端 installed.json：购买要扣物品，客户端文件能被改写 */
    public static final AttachmentType<PurchasedApps> PURCHASED_APPS =
            AttachmentRegistry.<PurchasedApps>builder()
                    .initializer(() -> PurchasedApps.EMPTY)
                    .persistent(PurchasedApps.CODEC)
                    .copyOnDeath()
                    .buildAndRegister(id("purchased_apps"));

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MCphone.MODID, path);
    }

    /**
     * 确保附件类型已注册。AttachmentRegistry.buildAndRegister 发生在类加载时，
     * 而本类的类加载是惰性的（第一次有人读附件时才发生）。由 MCphone.onInitialize
     * 显式调一次，把注册时间钉死在模组初始化阶段。
     */
    public static void ensureLoaded() {
        // 触碰任意一个字段即可触发静态初始化
        if (WALLPAPER == null) throw new IllegalStateException("WALLPAPER 附件未初始化");
    }
}
