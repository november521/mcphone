package com.november.mcphone.feature.notes;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 笔记列表里的一行摘要：列表只发摘要，点进某一条才单独拉全文。
 * title 与 preview 已按 MAX_*_LENGTH 截断。
 */
public record NoteSummary(int id, String title, String preview, long modified) {

    /** 先按字符截一次，界面再按像素截 */
    public static final int MAX_TITLE_LENGTH = 40;
    public static final int MAX_PREVIEW_LENGTH = 60;

    public static void encode(NoteSummary msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.id());
        buf.writeUtf(msg.title(), MAX_TITLE_LENGTH);
        buf.writeUtf(msg.preview(), MAX_PREVIEW_LENGTH);
        buf.writeVarLong(msg.modified());
    }

    public static NoteSummary decode(FriendlyByteBuf buf) {
        return new NoteSummary(
                buf.readVarInt(),
                buf.readUtf(MAX_TITLE_LENGTH),
                buf.readUtf(MAX_PREVIEW_LENGTH),
                buf.readVarLong());
    }

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
