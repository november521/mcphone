package com.november.mcphone.notes;

import com.november.mcphone.ModAttachments;
import com.november.mcphone.PhoneItem;
import com.november.mcphone.util.TextSanitizer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * 记事本的业务逻辑 —— 服务端侧。
 *
 * 与网络包分开：本类只回答"结果是什么"，不关心它怎么被传输。ChatService
 * 那边是同一个分法，加新入口（命令、附属模组）时不必绕道网络层。
 *
 * 笔记全部存在玩家附件里，见 {@link ModAttachments#NOTES}。
 */
public final class NoteService {

    private NoteService() {}

    /**
     * 新建笔记时客户端传的 id。
     *
     * 真正的 id 由服务端分配——让客户端自己挑的话，两个界面同时新建就会
     * 撞号，撞上就是"新建的笔记覆盖了旧的"。
     */
    public static final int NEW_NOTE_ID = 0;

    // ============================================================
    //  读
    // ============================================================

    /**
     * 列表摘要，按最近修改倒序。
     *
     * 读操作不校验身上有没有手机：这只是读玩家自己的数据，没有手机也看不到
     * 界面。多一道校验只是徒增一处可能漏改的地方。聊天那边同理。
     */
    public static List<NoteSummary> buildSummaries(ServerPlayer player) {
        return notes(player).sortedByRecent().stream().map(NoteSummary::of).toList();
    }

    /** 取某一条的全文 */
    public static Optional<Note> getNote(ServerPlayer player, int id) {
        return notes(player).find(id);
    }

    // ============================================================
    //  写
    // ============================================================

    /**
     * 保存一条笔记：id 为 {@link #NEW_NOTE_ID} 即新建，否则是改。
     *
     * 写操作要求身上带着手机——没有这条，改个客户端就能凭空往存档里写
     * 东西，"得有一部手机"这个前提形同虚设。
     *
     * 正文清洗时【保留换行】：笔记本来就是多行的。其余规则与设备名、
     * 聊天消息一致，见 TextSanitizer。
     *
     * 正文洗完为空时：已存在的那条直接删掉，新建则什么都不做。空笔记既
     * 占着条数上限又什么都不显示，留着只会让列表里多出一行空白。
     *
     * @return 是否真的改动了数据
     */
    public static boolean saveNote(ServerPlayer player, int id, String rawBody) {
        if (!PhoneItem.isCarriedBy(player)) return false;

        String body = TextSanitizer.sanitize(rawBody, Note.MAX_BODY_LENGTH, true);
        NoteList current = notes(player);

        if (body.isEmpty()) {
            return id != NEW_NOTE_ID && deleteNote(player, id);
        }

        int targetId = id == NEW_NOTE_ID ? current.nextId() : id;

        // 改的那条必须真的存在：客户端报一个不存在的 id，本该当作新建处理，
        // 否则就会凭空多出一条 id 由客户端指定的笔记
        if (id != NEW_NOTE_ID && current.find(id).isEmpty()) return false;

        NoteList updated = current.save(new Note(targetId, body, System.currentTimeMillis()));
        if (updated == current) return false;   // 条数满了，save 原样返回

        player.setData(ModAttachments.NOTES.get(), updated);
        return true;
    }

    /** 删一条。不存在时返回 false，不报错——重复点删除不该炸 */
    public static boolean deleteNote(ServerPlayer player, int id) {
        if (!PhoneItem.isCarriedBy(player)) return false;

        NoteList current = notes(player);
        NoteList updated = current.delete(id);
        if (updated == current) return false;

        player.setData(ModAttachments.NOTES.get(), updated);
        return true;
    }

    /** 记事本满了吗 —— 界面据此提示玩家，而不是让新建静默失败 */
    public static boolean isFull(ServerPlayer player) {
        return notes(player).notes().size() >= NoteList.MAX_COUNT;
    }

    private static NoteList notes(ServerPlayer player) {
        return player.getData(ModAttachments.NOTES.get());
    }
}
