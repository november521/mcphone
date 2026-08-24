package com.november.mcphone.feature.music.client.playback;

import java.io.IOException;
import java.io.InputStream;

/**
 * 自己读一遍 MP3 的帧头 —— 在把文件交给解码库之前，先知道它到底是什么。
 *
 * 为什么非得自己读一遍
 *
 * 打包进来的 JavaMP3 只认 MPEG-1（作者自己在仓库 issue #8 里写着
 * "The project currently supports MPEG 1 files only"，至今开着）。它读了
 * 帧头里的版本位却【没有用】：拿到手就丢掉，然后一律按 MPEG-1 的码率表、
 * 采样率表和 144×码率/采样率 的帧长公式算。
 *
 * 于是喂它一个 MPEG-2 的文件（22050 / 24000 / 16000 Hz —— 低码率 MP3 都是
 * 这一档）会怎样，是实测过的：22050Hz 单声道的文件在构造时就抛
 * ArrayIndexOutOfBoundsException，换 300 个起始偏移试，无一例外。
 * MPEG-2.5（11025 / 12000 / 8000 Hz）同样。
 *
 * 那个异常我们接得住，结果是这首歌被安静地跳过、日志里一行看不懂的
 * "ArrayIndexOutOfBoundsException"。玩家看到的是"我的歌在列表里，点了没反应"，
 * 而真正的原因——文件是 MPEG-2——没有任何地方说出来。
 *
 * 所以先自己读：不认识的当场说清楚是什么、该怎么办；认识的把规格写进日志，
 * 排查时一眼就知道手上是什么文件。
 *
 * 认帧头要认两个，不能只认一个
 *
 * 同步字只有 11 个 1，随便一段二进制里都可能撞上——尤其 ID3 标签里的专辑
 * 封面就是 JPEG，而 JPEG 满篇都是 0xFF。所以这里的判据是：找到一个像样的
 * 帧头之后，按它自己算出的帧长跳过去，那个位置上必须【还有一个】帧头，
 * 而且版本、层、采样率都一样。撞一次是巧合，连撞两次不是。
 *
 * 只看不动
 *
 * {@link #peek} 用 mark/reset 把流还原到原位，解码库拿到的还是完整的一条流。
 */
public record Mp3Header(Version version, int layer, int sampleRate, int channels,
                        int bitrate, boolean vbr, HeaderFrame headerFrame) {

    /**
     * 头帧（Xing/Info）里那两个数。没有头帧时整个是 null。
     *
     * 两个数必须一起看：帧数给出精确时长，而字节数用来判断这个头描述的
     * 是不是手上这个文件 —— 见 {@link Mp3Header#durationMs}。
     *
     * @param frames 总帧数，0 表示头帧里没写这一项
     * @param bytes  头帧声称一共有多少字节，0 表示没写
     */
    public record HeaderFrame(int frames, int bytes) {}

    /** MPEG 音频的三个版本。名字按规范写，日志里要给人看 */
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

    /**
     * 往前找多远。
     *
     * ID3v2 已经在外面跳掉了，正常情况下第一帧就在眼前。留 64KB 是给那些
     * 开头挂着别的垃圾（未知标签、下载器加的壳）的文件，多到这个程度就
     * 不必再迁就了。
     */
    private static final int SCAN_WINDOW = 64 * 1024;

    /** 帧头固定 4 字节 */
    private static final int HEADER_LEN = 4;

    /**
     * 边信息的长度，按 [是不是 MPEG-1][是不是单声道]。
     *
     * 只用来找 Xing/Info 标记 —— 它就贴在边信息后面。
     */
    private static final int SIDE_INFO_V1_MONO = 17;
    private static final int SIDE_INFO_V1_STEREO = 32;
    private static final int SIDE_INFO_V2_MONO = 9;
    private static final int SIDE_INFO_V2_STEREO = 17;

    // 采样率表，按 [版本][索引]。MPEG-2 是 MPEG-1 的一半，2.5 又是一半 ——
    // JavaMP3 只有第一行，这正是它把 16000Hz 报成 32000Hz 的原因
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
     * 从流的当前位置往后找第一个 MPEG 帧头，找到就返回它的规格。
     *
     * **不消耗流**：靠 mark/reset 还原，调用方接着交给解码库即可。
     *
     * @return 找不到（不是 MP3、或者开头垃圾太多）返回 null
     * @throws IOException 读文件本身出错
     */
    public static Mp3Header peek(InputStream in) throws IOException {
        if (!in.markSupported()) {
            // 调用方给的必须是带缓冲的流。这里不自己包一层：包了之后
            // reset 还原的是【我们这一层】的位置，外面那条流早读过头了
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

    /** 在一段字节里找第一个"后面还跟着同类帧头"的帧头，理由见类注释 */
    private static Mp3Header findFrame(byte[] d) {
        for (int i = 0; i + HEADER_LEN <= d.length; i++) {
            if ((d[i] & 0xFF) != 0xFF || (d[i + 1] & 0xE0) != 0xE0) continue;

            Mp3Header first = parse(d, i);
            if (first == null) continue;

            int len = first.frameLength(d, i);
            if (len <= 0) continue;

            // 帧长跳过去，那儿必须还有一个同规格的帧头。文件正好在这儿
            // 结束也算过——那是最后一帧，不该因此判定整个文件不是 MP3
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

    /**
     * 第一帧要是 Xing/Info 那个头帧，码率得看第二帧。
     *
     * 实测：有的文件头帧写着 64kbps，而真正的音频是 128kbps 的。规格
     * （版本、层、采样率、声道）两帧一致，只有码率这一项不能信头帧。
     */
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

        // mode 为 3 ＝ single_channel，其余三种（立体声、联合立体声、双声道）
        // 都是两个声道
        int channels = ((b3 >> 6) & 0x03) == 3 ? 1 : 2;

        return new Mp3Header(version, layer, sampleRate, channels, bitrate, false, null);
    }

    /**
     * 第一帧是不是 Xing/Info 那个头帧，是的话返回是哪一个。
     *
     * 多数 MP3 开头都有这么一帧：它是一个合法的 MPEG 帧，但装的不是音频而是
     * 整首歌的索引表。标记贴在边信息后面，4 个 ASCII 字节 ——
     * **Xing ＝ 变码率，Info ＝ 固定码率**，两个都是 LAME 写的，别把 Info
     * 也当成 VBR（实测手上五个样本带的全是 Info，其中三个是标准 CBR）。
     *
     * 返回的是标记本身的偏移，不是标记的内容：帧数就跟在它后面，拿到位置
     * 才读得到（见 {@link #xingFrameCount}）。
     *
     * @return 标记的偏移；这一帧就是音频则返回 -1
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
     * 读头帧里的总帧数与总字节数。
     *
     * 标记之后 4 个字节是标志位，然后按位依次排列：最低位表示"带帧数"，
     * 次低位表示"带字节数"，各占 4 字节大端整数，【只有置位的才占位置】
     * ——所以字节数在哪儿取决于帧数在不在。
     *
     * 帧数是变码率文件唯一可靠的时长来源：VBR 的意思就是没有"一个码率"，
     * 拿任何一帧的码率去乘时长都是错的。
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

    /**
     * 这一帧有多少字节。
     *
     * 三条公式，差别正是 JavaMP3 漏掉的那一处：Layer III 在 MPEG-2/2.5 下
     * 一帧只有 576 个样本（MPEG-1 是 1152），所以系数是 72 而不是 144。
     */
    private int frameLength(byte[] d, int at) {
        int padding = (d[at + 2] >> 1) & 0x01;

        if (layer == 1) {
            return (12 * bitrate / sampleRate + padding) * 4;
        }
        int coefficient = (layer == 3 && version != Version.MPEG1) ? 72 : 144;
        return coefficient * bitrate / sampleRate + padding;
    }

    /**
     * 一帧解出多少个采样。
     *
     * Layer III 在 MPEG-2/2.5 下只有 576，这正是 JavaMP3 漏掉的那一处
     * （见 {@link #frameLength}，那里表现为系数 72 与 144 的区别）。
     */
    private int samplesPerFrame() {
        if (layer == 1) return 384;
        if (layer == 2) return 1152;
        return version == Version.MPEG1 ? 1152 : 576;
    }

    /**
     * 这首歌有多长（毫秒），算不出返回 {@link KnownDuration#UNKNOWN}。
     *
     * 两条路，走哪一条取决于文件是不是变码率：
     *
     *   有 Xing 帧数   帧数 × 每帧采样数 ÷ 采样率。对两种文件都准
     *   固定码率       音频字节数 × 8 ÷ 码率
     *
     * 变码率而又没写帧数时【不算】。硬套第二条会拿某一帧的码率当成全曲的，
     * 而"没有一个码率"正是变码率的定义 —— 算出来的数能差出好几倍，那样的
     * 进度条比没有进度条更糟。
     *
     * @param audioBytes 音频数据有多少字节（文件大小减去开头的标签）。只有
     *                   固定码率那一条用得上。误差来自文件末尾那 128 字节的
     *                   ID3v1、以及第一帧之前可能残留的少量垃圾 —— 几 MB 的
     *                   文件里这点误差在进度条上看不出来
     */
    public long durationMs(long audioBytes) {
        if (headerFrame != null && headerFrame.frames() > 0 && !truncated(audioBytes)) {
            return (long) headerFrame.frames() * samplesPerFrame() * 1000L / sampleRate;
        }
        if (vbr || bitrate <= 0 || audioBytes <= 0) return KnownDuration.UNKNOWN;

        return audioBytes * 8000L / bitrate;
    }

    /**
     * 手上这个文件比头帧声称的短一大截 —— 那这个头描述的不是它。
     *
     * 下载到一半的 MP3 就是这样：头帧原封不动地留着，音频只有一小截。实测
     * 一个截到 19.7% 的文件，头里写着 19403 帧，实际只有 3829 帧 —— 照头
     * 算出来是 506 秒，真实长度 100 秒，进度条会一直卡在五分之一。
     *
     * 阈值取得松（3/4），是因为两种误判的后果完全不对等：
     *
     *   误判成截断  退回按码率折算。固定码率的文件照样准（那个截断样本
     *               这么一算是 100.0 秒，误差 0.02%），变码率的则不给数、
     *               不画进度条
     *   漏判        进度条给出一个差好几倍的比例
     *
     * 而"声称的字节数"各家编码器的口径本来就不齐（有的算整个文件、有的
     * 只算 MPEG 数据），带一张大封面的文件就可能差出一两成。宁可松一点。
     */
    private boolean truncated(long audioBytes) {
        int declared = headerFrame == null ? 0 : headerFrame.bytes();
        return declared > 0 && audioBytes * 4 < (long) declared * 3;
    }

    /** 这个解码器放不放得了。见类注释：打包的 JavaMP3 只认 MPEG-1 */
    public boolean isSupported() {
        return version == Version.MPEG1;
    }

    /**
     * 写进日志的一行，如 "MPEG-1 Layer III, 44100Hz, 立体声, 320kbps"。
     *
     * 变码率的文件写 VBR 而不是一个数字 —— 那种文件没有"一个码率"可报。
     */
    public String describe() {
        return version + " Layer " + "I".repeat(layer)
                + ", " + sampleRate + "Hz"
                + ", " + (channels == 1 ? "单声道" : "立体声")
                + ", " + (vbr ? "VBR" : (bitrate / 1000) + "kbps");
    }
}
