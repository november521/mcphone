package com.november.mcphone;

import com.november.mcphone.chat.ContactsData;
import com.november.mcphone.network.WallpaperData;
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
     * 联系人列表与已读进度。
     *
     * copyOnDeath：玩家死亡重生时附件默认会丢，联系人显然不该因为死一次
     * 就没了——那不是惩罚，是数据损坏。
     */
    public static final Supplier<AttachmentType<ContactsData>> CONTACTS = ATTACHMENT_TYPES.register(
            "contacts_data",
            () -> AttachmentType.builder(() -> ContactsData.DEFAULT)
                    .serialize(ContactsData.CODEC)
                    .copyOnDeath()
                    .build()
    );
}
