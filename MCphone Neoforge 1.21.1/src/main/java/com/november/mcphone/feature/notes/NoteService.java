package com.november.mcphone.feature.notes;

import com.november.mcphone.core.ModAttachments;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.util.TextSanitizer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/** 记事本的服务端业务逻辑，与网络包分开；数据存在玩家附件 {@link ModAttachments#NOTES} */
public final class NoteService {

    private NoteService() {}

    /** 新建笔记时客户端传的 id；真正的 id 由服务端分配 */
    public static final int NEW_NOTE_ID = 0;

    /** 列表摘要，按最近修改倒序；读操作不校验身上有没有手机 */
    public static List<NoteSummary> buildSummaries(ServerPlayer player) {
        return notes(player).sortedByRecent().stream().map(NoteSummary::of).toList();
    }

    public static Optional<Note> getNote(ServerPlayer player, int id) {
        return notes(player).find(id);
    }

    /**
     * 保存一条：id 为 {@link #NEW_NOTE_ID} 即新建。写操作要求身上带着手机；
     * 正文清洗保留换行；洗完为空时已有的直接删掉、新建则不做。返回是否真的改动了数据。
     */
    public static boolean saveNote(ServerPlayer player, int id, String rawBody) {
        if (!PhoneItem.isCarriedBy(player)) return false;

        String body = TextSanitizer.sanitize(rawBody, Note.MAX_BODY_LENGTH, true);
        NoteList current = notes(player);

        if (body.isEmpty()) {
            return id != NEW_NOTE_ID && deleteNote(player, id);
        }

        int targetId = id == NEW_NOTE_ID ? current.nextId() : id;

        // 改一个不存在的 id 直接拒绝，否则等于让客户端指定 id 新建
        if (id != NEW_NOTE_ID && current.find(id).isEmpty()) return false;

        NoteList updated = current.save(new Note(targetId, body, System.currentTimeMillis()));
        if (updated == current) return false;   // 条数满了，save 原样返回

        player.setData(ModAttachments.NOTES.get(), updated);
        return true;
    }

    /** 不存在时返回 false，不报错 */
    public static boolean deleteNote(ServerPlayer player, int id) {
        if (!PhoneItem.isCarriedBy(player)) return false;

        NoteList current = notes(player);
        NoteList updated = current.delete(id);
        if (updated == current) return false;

        player.setData(ModAttachments.NOTES.get(), updated);
        return true;
    }

    public static boolean isFull(ServerPlayer player) {
        return notes(player).notes().size() >= NoteList.MAX_COUNT;
    }

    private static NoteList notes(ServerPlayer player) {
        return player.getData(ModAttachments.NOTES.get());
    }
}
