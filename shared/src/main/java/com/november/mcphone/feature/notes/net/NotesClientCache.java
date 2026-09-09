package com.november.mcphone.feature.notes.net;

import com.november.mcphone.feature.notes.Note;
import com.november.mcphone.feature.notes.NoteSummary;

import java.util.List;

/**
 * 客户端本地的笔记缓存，界面每帧从这里读；真值在服务端，同步包填充。
 * 只放纯数据、不引用客户端专有类，否则专用服务器在类加载时会崩溃。
 */
public final class NotesClientCache {

    private NotesClientCache() {}

    private static List<NoteSummary> summaries = List.of();

    /** 当前打开的那条笔记；null 表示没有打开任何一条 */
    private static Note openNote;

    /** 正在等哪一条的全文；与 openNote 分开，免得迟到的回包画进另一条笔记 */
    private static int pendingId;

    public static List<NoteSummary> getSummaries() {
        return summaries;
    }

    static void setSummaries(List<NoteSummary> list) {
        summaries = List.copyOf(list);
    }

    public static Note getOpenNote() {
        return openNote;
    }

    /** 必须在发请求之前调用：回包可能先到，不知道在等哪条就会被丢掉 */
    public static void openNote(int id) {
        pendingId = id;
        openNote = null;
    }

    public static void openNewNote() {
        pendingId = 0;
        openNote = null;
    }

    public static void closeNote() {
        pendingId = 0;
        openNote = null;
    }

    static void setOpenNote(Note note) {
        // 迟到的回包：玩家已退出或切换，直接丢弃
        if (note.id() != pendingId) return;
        openNote = note;
    }

    /** 退出世界时清空，否则换服会先闪出上一个服务器的笔记 */
    public static void clear() {
        summaries = List.of();
        closeNote();
    }
}
