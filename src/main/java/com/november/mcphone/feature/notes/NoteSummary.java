package com.november.mcphone.feature.notes;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import com.november.mcphone.feature.chat.net.ConversationSummary;

/**
 * 笔记列表里的一行 —— 打开记事本时看到的摘要。
 *
 * ============================================================
 * 为什么列表不直接发全文
 * ============================================================
 *
 * 一个写满的记事本是 50 条 × 2000 字。列表若带全文，光是打开一次记事本
 * 就要传十万字，而列表上根本显示不下——每行只看得到一个标题加一行预览。
 *
 * 所以列表只发摘要，点进某一条才单独拉那条的全文。聊天那边的
 * ConversationSummary 是同一个路数，理由也一样。
 *
 * @param id       对应 {@link Note#id()}
 * @param title    正文第一行，已截断
 * @param preview  正文第二行起的内容，已压成一行并截断
 * @param modified 最后修改时刻，用于排序与显示
 */
public record NoteSummary(int id, String title, String preview, long modified) {

    /**
     * 标题与预览的截断长度。
     *
     * 手机屏幕一行放不下几个字，界面还会按像素再截一次。这里先按字符截，
     * 是为了别让一条 2000 字的笔记把"摘要"撑成全文——那样摘要就白设了。
     */
    public static final int MAX_TITLE_LENGTH = 40;
    public static final int MAX_PREVIEW_LENGTH = 60;

    public static final StreamCodec<ByteBuf, NoteSummary> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NoteSummary::id,
            ByteBufCodecs.stringUtf8(MAX_TITLE_LENGTH), NoteSummary::title,
            ByteBufCodecs.stringUtf8(MAX_PREVIEW_LENGTH), NoteSummary::preview,
            ByteBufCodecs.VAR_LONG, NoteSummary::modified,
            NoteSummary::new
    );

    /** 由服务端从整条笔记里摘出来 */
    public static NoteSummary of(Note note) {
        return new NoteSummary(
                note.id(),
                cut(note.title(), MAX_TITLE_LENGTH),
                cut(note.preview(), MAX_PREVIEW_LENGTH),
                note.modified());
    }

    /** 截断时不切开代理对，否则表情会被劈成半个，渲染出来是个方框 */
    private static String cut(String text, int max) {
        if (text.length() <= max) return text;
        String out = text.substring(0, max);
        if (Character.isHighSurrogate(out.charAt(out.length() - 1))) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
