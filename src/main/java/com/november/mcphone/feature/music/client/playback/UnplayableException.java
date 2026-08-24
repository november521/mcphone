package com.november.mcphone.feature.music.client.playback;

import net.minecraft.network.chat.Component;

import java.io.IOException;

/**
 * 这个文件放不了，而且原因是【能对玩家说清楚的一句话】。
 *
 * ================================================================
 * 为什么要专门有这么一个异常
 * ================================================================
 *
 * 放不了分两种。一种是"文件是 MPEG-2，这个解码器只认 MPEG-1"——玩家听得懂，
 * 也知道该怎么办（转成 44.1kHz）。另一种是"read 的时候磁盘报错了"——说给
 * 玩家听没有意义。
 *
 * 前一种必须走到界面上去。1.5.5 之前它只到日志为止，于是玩家看到的是
 * "歌在列表里，点了没反应"，而真正的原因躺在一个他不会去翻的文件里。
 *
 * 而通往界面的那条路只能是异常：{@link com.november.mcphone.feature.music.client.source.MusicSource#open}
 * 的签名是 {@code AudioStream open(Track)}，一个返回值的位置已经被音频流
 * 占着了，塞不下第二样东西。
 *
 * ================================================================
 * 两句话，各说各的
 * ================================================================
 *
 * {@link #reason()} 是给玩家的，要短到能塞进手机上一行字（曲库那一行就
 * 那么宽），而且必须是翻译键——这个模组两种语言都有。
 *
 * {@link #getMessage()} 是给日志的，越详细越好：完整的规格、该怎么办。
 * 排查时看的是它。
 */
public final class UnplayableException extends IOException {

    private final Component reason;

    /**
     * @param reason 给玩家看的一句话，短到能塞进一行。用翻译键
     * @param detail 给日志看的，越详细越好
     */
    public UnplayableException(Component reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    /** @param cause 底下那个异常，日志里要连它的栈一起留 */
    public UnplayableException(Component reason, String detail, Throwable cause) {
        super(detail, cause);
        this.reason = reason;
    }

    /** 界面上那一行显示的原因 */
    public Component reason() {
        return reason;
    }
}
