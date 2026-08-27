package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.settings.WallpaperData;
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
 * 自己定。现在只装壁纸，另外四样随各自的功能移植过来时往这里加字段。
 *
 * 代价要说清楚：那边五份数据的【死亡保留策略互不相同】（见下），合成一个
 * 之后不能再靠注册时的一个开关来表达，只能在 ModCapabilities 的 Clone
 * 监听里逐字段写。加字段时【必须】回去补那一处，漏了就是"死一次笔记没了"。
 */
public final class PhonePlayerData implements INBTSerializable<CompoundTag> {

    /** 存档里的键名，与那边 AttachmentType 的注册名对齐，方便两支对照排查 */
    private static final String KEY_WALLPAPER = "wallpaper_data";
    private static final String KEY_NOTES = "personal_notes";

    private WallpaperData wallpaper = WallpaperData.DEFAULT;
    private NoteList notes = NoteList.EMPTY;

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

    /** 把 other 的内容整个拷过来。玩家重生/换维度时由 ModCapabilities 调用 */
    public void copyFrom(PhonePlayerData other) {
        this.wallpaper = other.wallpaper;
        this.notes = other.notes;
    }

    /**
     * 只拷那边标了 copyOnDeath 的字段。死亡重生时由 ModCapabilities 调用。
     *
     * 【加字段时必须回来看这里】：那边每份数据的死亡保留策略是各自注册时定的，
     * 合成一个 Capability 之后只能在这儿逐字段表达。漏一个的症状是
     * "死一次笔记就没了"，而且只在玩家真死过一次之后才显形。
     *
     *   wallpaper  那边没标 copyOnDeath  → 不拷（死亡即重置，原样复现）
     *   notes      那边标了 copyOnDeath  → 拷
     */
    public void copyDeathPersistentFrom(PhonePlayerData other) {
        this.notes = other.notes;
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
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        // 读存档一律先退回默认值再尝试覆盖：存档可能是旧版本写的、可能被手改过，
        // 也可能这一格根本还没写过（第一次装上这个 mod）。解析不出来就用默认，
        // 而不是让一个坏字段把整个玩家数据带崩
        wallpaper = WallpaperData.DEFAULT;
        notes = NoteList.EMPTY;

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
    }
}
