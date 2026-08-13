package com.november.mcphone.network.notes;

import com.november.mcphone.notes.Note;
import com.november.mcphone.notes.NoteSummary;

import java.util.List;

/**
 * 客户端本地的笔记缓存 —— 界面每帧从这里读，不发包。
 *
 * 与 ChatClientCache 一样，刻意只放纯数据、不引用任何客户端专有的类：
 * 本类会被服务端侧的网络注册代码触及，若引入 Minecraft 客户端类，
 * 专用服务器会在类加载时直接崩溃。
 *
 * 数据由服务端下发的同步包填充，客户端自己从不构造——所有真值都在
 * 服务端，这里只是一份用于渲染的快照。
 *
 * 只缓存【列表摘要】与【当前打开的那一条】的全文：玩家一次只看一条，
 * 把五十条全文都留在内存里没有意义。
 */
public final class NotesClientCache {

    private NotesClientCache() {}

    private static List<NoteSummary> summaries = List.of();

    /** 当前打开的那条笔记；null 表示没有打开任何一条 */
    private static Note openNote;

    /**
     * 正在等哪一条的全文回来。
     *
     * 与 openNote 分开：请求发出到全文回来之间有一段空窗，界面得知道
     * "在等哪条"才不会把迟到的回包画进另一条笔记里。
     */
    private static int pendingId;

    // ============================================================
    //  列表
    // ============================================================

    public static List<NoteSummary> getSummaries() {
        return summaries;
    }

    static void setSummaries(List<NoteSummary> list) {
        summaries = List.copyOf(list);
    }

    // ============================================================
    //  当前打开的那条
    // ============================================================

    public static Note getOpenNote() {
        return openNote;
    }

    /**
     * 界面点进某条时调用，先把要看的 id 记下来。
     *
     * 必须在发请求【之前】调用，理由与聊天那边一致：回包可能比界面状态
     * 先到，那时若还不知道等的是哪条，这份数据就会被丢掉。
     */
    public static void openNote(int id) {
        pendingId = id;
        openNote = null;
    }

    /** 新建：没有 id 可等，直接给界面一张白纸 */
    public static void openNewNote() {
        pendingId = 0;
        openNote = null;
    }

    public static void closeNote() {
        pendingId = 0;
        openNote = null;
    }

    static void setOpenNote(Note note) {
        // 玩家可能在全文回来之前就退出或切换了，这份数据已经过期，
        // 直接丢弃而不是画到另一条笔记上
        if (note.id() != pendingId) return;
        openNote = note;
    }

    /**
     * 退出世界时清空。
     *
     * 不清的话，换到另一个服务器时会先闪出上一个服务器的笔记——
     * 那是别处的数据，既尴尬又可能泄露信息。聊天缓存同理。
     */
    public static void clear() {
        summaries = List.of();
        closeNote();
    }
}
