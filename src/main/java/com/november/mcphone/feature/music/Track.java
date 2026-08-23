package com.november.mcphone.feature.music;

/**
 * 一首曲子 —— 播放器认识的最小单位。
 *
 * ================================================================
 * 为什么不直接存文件路径
 * ================================================================
 *
 * 存了路径，这个记录就只能表示"硬盘上的一个文件"，网络音源、原版唱片
 * 一个都塞不进来。所以这里只存【谁家的、编号多少】，怎么把它变成声音
 * 是音源自己的事（见 MusicSource.open）。
 *
 * 加网络音源时，id 换成歌曲 ID 就行，这个记录一个字段都不用动。
 *
 * @param sourceId   来自哪个音源，如 "local"
 * @param id         音源自己的编号。本地文件是文件名，网络歌是歌曲 ID
 * @param title      界面上显示的名字
 * @param durationMs 时长，毫秒。**-1 表示不知道**——OGG 不读完整个文件
 *                   就拿不到时长，而为了显示一个数字去读完整首歌不值得。
 *                   界面遇到 -1 只显示已播时间，不画总长
 * @param kind       怎么放，见 {@link Kind}
 */
public record Track(String sourceId, String id, String title, long durationMs, Kind kind) {

    /**
     * 放法。它决定的是【谁听得见】，而这是个物理限制，不是设计选择。
     */
    public enum Kind {
        /**
         * 本地解码，只有自己听得见 —— "耳机"。
         *
         * 能暂停、能继续、能看进度。因为音频数据就在这台机器上，
         * 我们自己解码、自己控制通道。
         */
        LOCAL,

        /**
         * 交给服务端播原版音效，周围人都听得见 —— "外放"。
         *
         * 只能播和停：原版音效系统没有暂停，也没有跳转。而它必须是
         * 【已注册的音效】——服务端只能告诉客户端"放 minecraft:music_disc.cat"，
         * 没法把你硬盘上那个 mp3 的字节发过去，别人电脑上也没有那个文件。
         */
        SHARED
    }

    /** 全局唯一的键：音源之间的 id 可能撞车，加上音源名才不会 */
    public String key() {
        return sourceId + ":" + id;
    }

    /** 时长未知 */
    public static final long UNKNOWN_DURATION = -1L;

    public boolean hasDuration() {
        return durationMs > 0;
    }
}
