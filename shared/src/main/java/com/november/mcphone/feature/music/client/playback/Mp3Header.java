package com.november.mcphone.feature.music.client.playback;

import java.io.IOException;
import java.io.InputStream;

/**
 * 自己读一遍 MP3 的帧头 —— 打包的 JavaMP3 只认 MPEG-1，喂 MPEG-2/2.5 当场就炸，先认清文件才能把原因说给玩家。
 * 判据是连着两个规格一致的帧头：同步字只有 11 个 1，ID3 里的 JPEG 封面满篇 0xFF，撞一次是巧合。
 * {@link #peek} 只看不动，靠 mark/reset 还原流。
 */
public record Mp3Header(Version version, int layer, int sampleRate, int channels,
                        int bitrate, boolean vbr, HeaderFrame headerFrame) {

    /**
     * 头帧（Xing/Info）里那两个数。没有头帧时整个是 null。
     * @param frames 总帧数，0 表示头帧里没写这一项
     * @param bytes  头帧声称一共有多少字节，0 表示没写
     */
    public record HeaderFrame(int frames, int bytes) {}

    public enum Version {
        MPEG1("MPEG-1"),
        MPEG2("MPEG-2"),
        MPEG25("MPEG-2.5");

        private final String label;

        Version(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 往前找多远。ID3v2 已在外面跳掉，留 64KB 给开头挂着垃圾的文件 */
    private static final int SCAN_WINDOW = 64 * 1024;

    private static final int HEADER_LEN = 4;

    /** 边信息的长度，按 [是不是 MPEG-1][是不是单声道]；只用来找贴在它后面的 Xing/Info 标记 */
    private static final int SIDE_INFO_V1_MONO = 17;
    private static final int SIDE_INFO_V1_STEREO = 32;
    private static final int SIDE_INFO_V2_MONO = 9;
    private static final int SIDE_INFO_V2_STEREO = 17;

    // 采样率表，按 [版本][索引]。MPEG-2 是 MPEG-1 的一半，2.5 又是一半
    private static final int[][] SAMPLE_RATES = {
            {44100, 48000, 32000},      // MPEG-1
            {22050, 24000, 16000},      // MPEG-2
            {11025, 12000, 8000}        // MPEG-2.5
    };

    // 码率表（kbps）。索引 0 是"自由格式"、15 是非法，两者都当作不认识
    private static final int[] BR_V1_L1 =
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448};
    private static final int[] BR_V1_L2 =
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
    private static final int[] BR_V1_L3 =
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BR_V2_L1 =
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256};
    /** MPEG-2 与 2.5 的 Layer II、Layer III 共用这一张 */
    private static final int[] BR_V2_L23 =
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    /**
     * 从流的当前位置往后找第一个 MPEG 帧头。不消耗流：靠 mark/reset 还原。
     * @return 找不到返回 null
     */
    public static Mp3Header peek(InputStream in) throws IOException {
        if (!in.markSupported()) {
            // 不自己包一层：包了之后 reset 还原的是我们这一层的位置，外面那条流早读过头了
            throw new IOException("内部错误：读帧头需要一条支持 mark 的流");
        }

        in.mark(SCAN_WINDOW);
        try {
            byte[] window = in.readNBytes(SCAN_WINDOW);
            return findFrame(window);
        } finally {
            in.reset();
        }
    }

    private static Mp3Header findFrame(byte[] d) {
        for (int i = 0; i + HEADER_LEN <= d.length; i++) {
            if ((d[i] & 0xFF) != 0xFF || (d[i + 1] & 0xE0) != 0xE0) continue;

            Mp3Header first = parse(d, i);
            if (first == null) continue;

            int len = first.frameLength(d, i);
            if (len <= 0) continue;

            // 文件正好在这儿结束也算过：那是最后一帧
            int next = i + len;
            if (next + HEADER_LEN > d.length) return first;

            Mp3Header second = parse(d, next);
            if (second == null
                    || second.version != first.version
                    || second.layer != first.layer
                    || second.sampleRate != first.sampleRate) {
                continue;
            }
            return resolveBitrate(d, i, first, second);
        }
        return null;
    }

    /** 第一帧要是 Xing/Info 头帧，码率得看第二帧：头帧写的码率不能信 */
    private static Mp3Header resolveBitrate(byte[] d, int at,
                                            Mp3Header first, Mp3Header second) {
        int tagAt = headerFrameTagOffset(d, at, first);
        if (tagAt < 0) return first;        // 没有头帧，第一帧就是音频

        boolean vbr = d[tagAt] == 'X';      // Xing ＝ 变码率，Info ＝ 固定码率
        return new Mp3Header(first.version, first.layer, first.sampleRate,
                first.channels, second.bitrate, vbr, readHeaderFrame(d, tagAt));
    }

    /** 解一个 4 字节帧头；任何一个字段非法就返回 null */
    private static Mp3Header parse(byte[] d, int at) {
        if ((d[at] & 0xFF) != 0xFF || (d[at + 1] & 0xE0) != 0xE0) return null;

        int b1 = d[at + 1] & 0xFF;
        int b2 = d[at + 2] & 0xFF;
        int b3 = d[at + 3] & 0xFF;

        int versionId = (b1 >> 3) & 0x03;   // 0=2.5, 1=保留, 2=MPEG2, 3=MPEG1
        int layerId = (b1 >> 1) & 0x03;     // 0=保留, 1=III, 2=II, 3=I
        if (versionId == 1 || layerId == 0) return null;

        int bitrateIndex = (b2 >> 4) & 0x0F;
        int rateIndex = (b2 >> 2) & 0x03;
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null;

        Version version = switch (versionId) {
            case 3 -> Version.MPEG1;
            case 2 -> Version.MPEG2;
            default -> Version.MPEG25;
        };
        int layer = 4 - layerId;            // layerId 3→Layer I，1→Layer III

        int sampleRate = SAMPLE_RATES[version.ordinal()][rateIndex];
        int bitrate = bitrateTable(version, layer)[bitrateIndex] * 1000;

        // mode 3 ＝ single_channel，其余三种都是两个声道
        int channels = ((b3 >> 6) & 0x03) == 3 ? 1 : 2;

        return new Mp3Header(version, layer, sampleRate, channels, bitrate, false, null);
    }

    /**
     * 头帧是合法 MPEG 帧但装的是索引表，标记贴在边信息后面；Xing ＝ 变码率，Info ＝ 固定码率，别把 Info 也当成 VBR。
     * @return 标记本身的偏移；这一帧就是音频则返回 -1
     */
    private static int headerFrameTagOffset(byte[] d, int at, Mp3Header h) {
        int sideInfo = h.version == Version.MPEG1
                ? (h.channels == 1 ? SIDE_INFO_V1_MONO : SIDE_INFO_V1_STEREO)
                : (h.channels == 1 ? SIDE_INFO_V2_MONO : SIDE_INFO_V2_STEREO);

        int tag = at + HEADER_LEN + sideInfo;
        if (tag + 4 > d.length) return -1;

        String s = new String(d, tag, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return "Xing".equals(s) || "Info".equals(s) ? tag : -1;
    }

    /**
     * 读头帧里的总帧数与总字节数。标记后 4 字节是标志位：最低位＝带帧数、
     * 次低位＝带字节数，各占 4 字节大端，【只有置位的才占位置】。
     */
    private static HeaderFrame readHeaderFrame(byte[] d, int tagAt) {
        int flagsAt = tagAt + 4;
        if (flagsAt + 4 > d.length) return null;

        int flags = readInt(d, flagsAt);
        int at = flagsAt + 4;

        int frames = 0;
        if ((flags & 0x01) != 0) {
            if (at + 4 > d.length) return new HeaderFrame(0, 0);
            frames = Math.max(0, readInt(d, at));
            at += 4;
        }

        int bytes = 0;
        if ((flags & 0x02) != 0 && at + 4 <= d.length) {
            bytes = Math.max(0, readInt(d, at));
        }
        return new HeaderFrame(frames, bytes);
    }

    /** 4 字节大端整数 */
    private static int readInt(byte[] d, int at) {
        return ((d[at] & 0xFF) << 24) | ((d[at + 1] & 0xFF) << 16)
                | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
    }

    private static int[] bitrateTable(Version version, int layer) {
        if (version == Version.MPEG1) {
            return switch (layer) {
                case 1 -> BR_V1_L1;
                case 2 -> BR_V1_L2;
                default -> BR_V1_L3;
            };
        }
        return layer == 1 ? BR_V2_L1 : BR_V2_L23;
    }

    /** 这一帧有多少字节。Layer III 在 MPEG-2/2.5 下一帧只有 576 个样本，所以系数是 72 而不是 144 */
    private int frameLength(byte[] d, int at) {
        int padding = (d[at + 2] >> 1) & 0x01;

        if (layer == 1) {
            return (12 * bitrate / sampleRate + padding) * 4;
        }
        int coefficient = (layer == 3 && version != Version.MPEG1) ? 72 : 144;
        return coefficient * bitrate / sampleRate + padding;
    }

    /** 一帧解出多少个采样。Layer III 在 MPEG-2/2.5 下只有 576 */
    private int samplesPerFrame() {
        if (layer == 1) return 384;
        if (layer == 2) return 1152;
        return version == Version.MPEG1 ? 1152 : 576;
    }

    /**
     * 全曲毫秒数，算不出返回 {@link KnownDuration#UNKNOWN}。有帧数按帧数算，固定码率按字节÷码率；
     * 变码率又没写帧数时【不算】—— 瞎猜的进度条比没有更糟。
     * @param audioBytes 音频数据的字节数（文件大小减去开头的标签），只有固定码率那条用得上
     */
    public long durationMs(long audioBytes) {
        if (headerFrame != null && headerFrame.frames() > 0 && !truncated(audioBytes)) {
            return (long) headerFrame.frames() * samplesPerFrame() * 1000L / sampleRate;
        }
        if (vbr || bitrate <= 0 || audioBytes <= 0) return KnownDuration.UNKNOWN;

        return audioBytes * 8000L / bitrate;
    }

    /** 文件比头帧声称的短一大截（下载了一半那种），头描述的就不是它。阈值取松：声称的字节数各家口径不齐 */
    private boolean truncated(long audioBytes) {
        int declared = headerFrame == null ? 0 : headerFrame.bytes();
        return declared > 0 && audioBytes * 4 < (long) declared * 3;
    }

    /** 打包的 JavaMP3 只认 MPEG-1 */
    public boolean isSupported() {
        return version == Version.MPEG1;
    }

    /** 写进日志的一行，如 "MPEG-1 Layer III, 44100Hz, 立体声, 320kbps" */
    public String describe() {
        return version + " Layer " + "I".repeat(layer)
                + ", " + sampleRate + "Hz"
                + ", " + (channels == 1 ? "单声道" : "立体声")
                + ", " + (vbr ? "VBR" : (bitrate / 1000) + "kbps");
    }
}
