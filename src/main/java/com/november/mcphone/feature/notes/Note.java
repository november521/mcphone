package com.november.mcphone.feature.notes;

import net.minecraft.network.FriendlyByteBuf;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 一条笔记。没有单独的标题字段：正文第一行当标题、第二行起当预览。
 *
 * @param id       创建时生成，此后不变
 * @param modified 最后修改时刻，毫秒
 */
public record Note(int id, String body, long modified) {

    /** 正文长度上限，编解码器层面封死，超长包在解码阶段就拒收 */
    public static final int MAX_BODY_LENGTH = 2000;

    public static final Codec<Note> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.fieldOf("id").forGetter(Note::id),
                    Codec.STRING.fieldOf("body").forGetter(Note::body),
                    Codec.LONG.fieldOf("modified").forGetter(Note::modified)
            ).apply(instance, Note::new)
    );

    public static void encode(Note msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.id());
        buf.writeUtf(msg.body(), MAX_BODY_LENGTH);
        buf.writeVarLong(msg.modified());
    }

    public static Note decode(FriendlyByteBuf buf) {
        return new Note(
                buf.readVarInt(),
                buf.readUtf(MAX_BODY_LENGTH),
                buf.readVarLong());
    }

    /** 正文第一行；空白笔记返回空串，显示什么由界面决定 */
    public String title() {
        return firstLine(body);
    }

    /** 正文第二行起，压成一行 */
    public String preview() {
        int cut = body.indexOf('\n');
        if (cut < 0) return "";
        return body.substring(cut + 1).replace('\n', ' ').trim();
    }

    private static String firstLine(String text) {
        int cut = text.indexOf('\n');
        return (cut < 0 ? text : text.substring(0, cut)).trim();
    }
}
