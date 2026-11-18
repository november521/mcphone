package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatReadState;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.settings.WallpaperData;
import com.november.mcphone.feature.terminal.TerminalSlot;
import com.november.mcphone.core.script.server.store.ScriptGuards;
import com.november.mcphone.core.script.server.store.ScriptKv;
import com.november.mcphone.feature.store.PurchasedApps;
import com.november.mcphone.feature.music.DiscState;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** 玩家附着数据（Attachment）注册 —— 跟着玩家走的数据；跟着物品走的见 {@link ModDataComponents}，两人共有的见 ChatData。
 *  读写别直接走这里，走 {@link PhonePlayerData}。 */
public final class ModAttachments {

    private ModAttachments() {}

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MCphone.MODID);

    /** 玩家选择的壁纸文件名 */
    public static final Supplier<AttachmentType<WallpaperData>> WALLPAPER = ATTACHMENT_TYPES.register(
            "wallpaper_data",
            () -> AttachmentType.builder(() -> WallpaperData.DEFAULT)
                    .serialize(com.mojang.serialization.MapCodec.assumeMapUnsafe(WallpaperData.CODEC))
                    .build()
    );

    /** 聊天的已读进度。copyOnDeath：不然死一次所有会话都变未读 */
    public static final Supplier<AttachmentType<ChatReadState>> CHAT_READ = ATTACHMENT_TYPES.register(
            "chat_read_state",
            () -> AttachmentType.builder(() -> ChatReadState.DEFAULT)
                    .serialize(com.mojang.serialization.MapCodec.assumeMapUnsafe(ChatReadState.CODEC))
                    .copyOnDeath()
                    .build()
    );

    /** 唱片仓里的唱片，以及是否正在外放。copyOnDeath：唱片是可掉落的真物品，不能死一次就没 */
    public static final Supplier<AttachmentType<DiscState>> DISC = ATTACHMENT_TYPES.register(
            "phone_disc",
            () -> AttachmentType.builder(() -> DiscState.EMPTY)
                    .serialize(com.mojang.serialization.MapCodec.assumeMapUnsafe(DiscState.CODEC))
                    .copyOnDeath()
                    .build()
    );

    /** 记事本的全部笔记。copyOnDeath：不然死一次就清空 */
    public static final Supplier<AttachmentType<NoteList>> NOTES = ATTACHMENT_TYPES.register(
            "personal_notes",
            () -> AttachmentType.builder(() -> NoteList.EMPTY)
                    .serialize(com.mojang.serialization.MapCodec.assumeMapUnsafe(NoteList.CODEC))
                    .copyOnDeath()
                    .build()
    );

    /**
     * 手机终端卡槽里那台终端（「终端」App 用）。空的时候是 {@link ItemStack#EMPTY}。
     *
     * 这一条是这里唯一 {@code sync()} 的附件，不能省：AE2 与 RS 的终端菜单在客户端会被
     * 重建，重建时要在【客户端】再问一次"那台终端在哪儿"。客户端拿到空的，菜单当场判失效
     * 关掉——表现是"点了闪一下又回来"。为什么不用 DataComponent 见 {@link TerminalSlot}。
     *
     * copyOnDeath：和唱片仓同一个道理，手机里的东西不该因为死一次就没。
     */
    public static final Supplier<AttachmentType<ItemStack>> PHONE_TERMINAL = ATTACHMENT_TYPES.register(
            "phone_terminal",
            () -> AttachmentType.builder(() -> ItemStack.EMPTY)
                    .serialize(com.mojang.serialization.MapCodec.assumeMapUnsafe(ItemStack.OPTIONAL_CODEC))
                    .sync(ItemStack.OPTIONAL_STREAM_CODEC)
                    .copyOnDeath()
                    .build()
    );

    /** 玩家买过哪些 App。存服务端而非客户端 installed.json：购买要扣物品，客户端文件能被改写 */
    public static final Supplier<AttachmentType<PurchasedApps>> PURCHASED_APPS =
            ATTACHMENT_TYPES.register(
                    "purchased_apps",
                    () -> AttachmentType.builder(() -> PurchasedApps.EMPTY)
                            .serialize(com.mojang.serialization.MapCodec.assumeMapUnsafe(PurchasedApps.CODEC))
                            .copyOnDeath()
                            .build()
            );

    /** 脚本 App 的 shared 档 KV（§17.3）。死亡保留：死一次就丢进度是不能接受的 */
    public static final Supplier<AttachmentType<ScriptKv>> SCRIPT_KV =
            ATTACHMENT_TYPES.register(
                    "script_kv",
                    () -> AttachmentType.builder(() -> ScriptKv.DEFAULT)
                            .serialize(com.mojang.serialization.MapCodec.assumeMapUnsafe(ScriptKv.CODEC))
                            .copyOnDeath()
                            .build()
            );

    /**
     * 脚本 App 的守卫计数（§17.3、§20.2）。<b>与 SCRIPT_KV 分开，脚本写不到</b>——
     * 放一起脚本就能把「只能领一次」清零。
     *
     * <p><b>copyOnDeath 是必须的</b>：不带的话死一次就能重领，而死亡在 Minecraft 里
     * 是随时可以自己安排的事。
     */
    public static final Supplier<AttachmentType<ScriptGuards>> SCRIPT_GUARDS =
            ATTACHMENT_TYPES.register(
                    "script_guards",
                    () -> AttachmentType.builder(() -> ScriptGuards.DEFAULT)
                            .serialize(com.mojang.serialization.MapCodec.assumeMapUnsafe(ScriptGuards.CODEC))
                            .copyOnDeath()
                            .build()
            );
}
