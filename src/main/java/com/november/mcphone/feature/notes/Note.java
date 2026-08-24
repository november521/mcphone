package com.november.mcphone.feature.notes;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一条笔记。
 *
 * 为什么没有单独的标题字段
 *
 * 手机屏幕只有 120 像素宽，标题与正文分成两个输入框，等于让玩家为一条
 * 便签切两次焦点、填两个空。列表里显示正文的第一行当标题、第二行当预览
 * 就够用了——常见的手机备忘录都是这么干的。
 *
 * 少一个字段也少一处不一致：不会出现"标题写着甲、内容讲的乙"。
 *
 * @param id       创建时生成，此后不变。列表靠它认人：正文会被改得面目
 *                 全非，用序号则一删就全乱套
 * @param body     正文，长度上限见 {@link #MAX_BODY_LENGTH}
 * @param modified 最后修改时刻，用于列表排序与显示
 */
public record Note(int id, String body, long modified) {

    /**
     * 正文长度上限，编解码器层面就封死。
     *
     * 写在编解码器上而不是只在业务层检查：伪造客户端可以绕过界面直接
     * 发包，让解码阶段就拒收超长文本，才不会白白吃下几十 KB。
     * 设备名与聊天消息都是同一个路数。
     */
    public static final int MAX_BODY_LENGTH = 2000;

    public static final Codec<Note> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.fieldOf("id").forGetter(Note::id),
                    Codec.STRING.fieldOf("body").forGetter(Note::body),
                    Codec.LONG.fieldOf("modified").forGetter(Note::modified)
            ).apply(instance, Note::new)
    );

    public static final StreamCodec<ByteBuf, Note> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Note::id,
            ByteBufCodecs.stringUtf8(MAX_BODY_LENGTH), Note::body,
            ByteBufCodecs.VAR_LONG, Note::modified,
            Note::new
    );

    /**
     * 列表里显示的标题 —— 正文第一行。
     *
     * 空白笔记返回空串，由界面决定显示成什么（"无标题"之类的文案属于
     * 界面的事，不该由数据层来定，那样连翻译都做不了）。
     */
    public String title() {
        return firstLine(body);
    }

    /** 列表里显示的预览 —— 正文第二行起的内容，压成一行 */
    public String preview() {
        int cut = body.indexOf('\n');
        if (cut < 0) return "";
        // 剩下的部分可能还有很多换行，全压成空格：列表一行放得下才是重点
        return body.substring(cut + 1).replace('\n', ' ').trim();
    }

    private static String firstLine(String text) {
        int cut = text.indexOf('\n');
        return (cut < 0 ? text : text.substring(0, cut)).trim();
    }
}
