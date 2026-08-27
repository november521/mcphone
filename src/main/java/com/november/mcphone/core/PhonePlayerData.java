package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatReadState;
import com.november.mcphone.feature.music.DiscState;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.settings.WallpaperData;
import com.november.mcphone.feature.store.PurchasedApps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.util.INBTSerializable;

/**
 * 跟着玩家走的手机数据 —— 对应 NeoForge 那一支的 ModAttachments。
 *
 * 为什么是【一个】Capability 而不是五个
 *
 * 那边有五份玩家附着数据（壁纸、聊天已读、唱片仓、笔记、已购 App），每一份
 * 是一个独立的 AttachmentType。直译过来会是五个 Capability，而 Forge 的
 * capability 每个都要一套 token + provider + 附加监听，五份就是五套样板。
 *
 * 所以这边合成一个：capability 是"挂在玩家身上的一格",格子里装什么由我们
 * 自己定。那边五份数据现在都在这里了：壁纸、笔记、聊天已读、唱片仓、已购 App。
 *
 * 代价要说清楚：那边五份数据的【死亡保留策略互不相同】（见下），合成一个
 * 之后不能再靠注册时的一个开关来表达，只能在 ModCapabilities 的 Clone
 * 监听里逐字段写。加字段时【必须】回去补那一处，漏了就是"死一次笔记没了"。
 */
public final class PhonePlayerData implements INBTSerializable<CompoundTag> {

    /** 存档里的键名，与那边 AttachmentType 的注册名对齐，方便两支对照排查 */
    private static final String KEY_WALLPAPER = "wallpaper_data";
    private static final String KEY_NOTES = "personal_notes";
    private static final String KEY_CHAT_READ = "chat_read_state";
    private static final String KEY_DISC = "phone_disc";
    private static final String KEY_PURCHASED = "purchased_apps";

    private WallpaperData wallpaper = WallpaperData.DEFAULT;
    private NoteList notes = NoteList.EMPTY;
    private ChatReadState chatRead = ChatReadState.DEFAULT;
    private DiscState disc = DiscState.EMPTY;
    private PurchasedApps purchasedApps = PurchasedApps.EMPTY;

    public WallpaperData wallpaper() {
        return wallpaper;
    }

    public void setWallpaper(WallpaperData value) {
        this.wallpaper = value;
    }

    public NoteList notes() {
        return notes;
    }

    public void setNotes(NoteList value) {
        this.notes = value;
    }

    public ChatReadState chatRead() {
        return chatRead;
    }

    public void setChatRead(ChatReadState value) {
        this.chatRead = value;
    }

    public DiscState disc() {
        return disc;
    }

    public void setDisc(DiscState value) {
        this.disc = value;
    }

    public PurchasedApps purchasedApps() {
        return purchasedApps;
    }

    public void setPurchasedApps(PurchasedApps value) {
        this.purchasedApps = value;
    }

    /** 把 other 的内容整个拷过来。玩家重生/换维度时由 ModCapabilities 调用 */
    public void copyFrom(PhonePlayerData other) {
        this.wallpaper = other.wallpaper;
        this.notes = other.notes;
        this.chatRead = other.chatRead;
        this.disc = other.disc;
        this.purchasedApps = other.purchasedApps;
    }

    /**
     * 只拷那边标了 copyOnDeath 的字段。死亡重生时由 ModCapabilities 调用。
     *
     * 【加字段时必须回来看这里】：那边每份数据的死亡保留策略是各自注册时定的，
     * 合成一个 Capability 之后只能在这儿逐字段表达。漏一个的症状是
     * "死一次笔记就没了"，而且只在玩家真死过一次之后才显形。
     *
     *   wallpaper       那边【没标】copyOnDeath  → 不拷（死亡即重置，原样复现）
     *   notes           标了                     → 拷
     *   chatRead        标了                     → 拷（不然死一次所有会话都变未读）
     *   disc            标了                     → 拷（唱片是可掉落的真物品，不能死一次就没）
     *   purchasedApps   标了                     → 拷（买过的东西不能因为死一次就没了）
     */
    public void copyDeathPersistentFrom(PhonePlayerData other) {
        this.notes = other.notes;
        this.chatRead = other.chatRead;
        this.disc = other.disc;
        this.purchasedApps = other.purchasedApps;
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        WallpaperData.CODEC.encodeStart(NbtOps.INSTANCE, wallpaper)
                .resultOrPartial(err -> MCphone.LOGGER.error("壁纸数据写入存档失败: {}", err))
                .ifPresent(encoded -> tag.put(KEY_WALLPAPER, encoded));
        NoteList.CODEC.encodeStart(NbtOps.INSTANCE, notes)
                .resultOrPartial(err -> MCphone.LOGGER.error("笔记写入存档失败: {}", err))
                .ifPresent(encoded -> tag.put(KEY_NOTES, encoded));
        ChatReadState.CODEC.encodeStart(NbtOps.INSTANCE, chatRead)
                .resultOrPartial(err -> MCphone.LOGGER.error("聊天已读进度写入存档失败: {}", err))
                .ifPresent(encoded -> tag.put(KEY_CHAT_READ, encoded));
        DiscState.CODEC.encodeStart(NbtOps.INSTANCE, disc)
                .resultOrPartial(err -> MCphone.LOGGER.error("唱片仓写入存档失败: {}", err))
                .ifPresent(encoded -> tag.put(KEY_DISC, encoded));
        PurchasedApps.CODEC.encodeStart(NbtOps.INSTANCE, purchasedApps)
                .resultOrPartial(err -> MCphone.LOGGER.error("已购 App 写入存档失败: {}", err))
                .ifPresent(encoded -> tag.put(KEY_PURCHASED, encoded));
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        // 读存档一律先退回默认值再尝试覆盖：存档可能是旧版本写的、可能被手改过，
        // 也可能这一格根本还没写过（第一次装上这个 mod）。解析不出来就用默认，
        // 而不是让一个坏字段把整个玩家数据带崩
        wallpaper = WallpaperData.DEFAULT;
        notes = NoteList.EMPTY;
        chatRead = ChatReadState.DEFAULT;
        disc = DiscState.EMPTY;
        purchasedApps = PurchasedApps.EMPTY;

        Tag wp = tag.get(KEY_WALLPAPER);
        if (wp != null) {
            WallpaperData.CODEC.parse(NbtOps.INSTANCE, wp)
                    .resultOrPartial(err -> MCphone.LOGGER.warn("壁纸数据读取失败，已退回默认: {}", err))
                    .ifPresent(value -> wallpaper = value);
        }

        Tag nt = tag.get(KEY_NOTES);
        if (nt != null) {
            NoteList.CODEC.parse(NbtOps.INSTANCE, nt)
                    .resultOrPartial(err -> MCphone.LOGGER.warn("笔记读取失败，已退回空列表: {}", err))
                    .ifPresent(value -> notes = value);
        }

        Tag cr = tag.get(KEY_CHAT_READ);
        if (cr != null) {
            ChatReadState.CODEC.parse(NbtOps.INSTANCE, cr)
                    .resultOrPartial(err -> MCphone.LOGGER.warn("聊天已读进度读取失败，已退回默认: {}", err))
                    .ifPresent(value -> chatRead = value);
        }

        Tag dc = tag.get(KEY_DISC);
        if (dc != null) {
            DiscState.CODEC.parse(NbtOps.INSTANCE, dc)
                    .resultOrPartial(err -> MCphone.LOGGER.warn("唱片仓读取失败，已退回空仓: {}", err))
                    .ifPresent(value -> disc = value);
        }

        Tag pa = tag.get(KEY_PURCHASED);
        if (pa != null) {
            PurchasedApps.CODEC.parse(NbtOps.INSTANCE, pa)
                    .resultOrPartial(err -> MCphone.LOGGER.warn("已购 App 读取失败，已退回空: {}", err))
                    .ifPresent(value -> purchasedApps = value);
        }
    }
}
