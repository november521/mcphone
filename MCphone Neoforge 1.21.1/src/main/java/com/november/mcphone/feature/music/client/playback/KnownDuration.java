package com.november.mcphone.feature.music.client.playback;

/**
 * 一条知道自己有多长的音频流。
 *
 * 为什么时长挂在流上，而不是挂在曲目上
 *
 * 因为它是【打开文件的那一刻】才知道的。要在曲库列表里就填上时长，就得在
 * 扫目录时挨个开文件、跳 ID3 标签（专辑封面动辄几百 KB）、找同步帧 ——
 * 那会把一次目录扫描变成一次全盘 IO，而音源接口的规矩写得很清楚：refresh
 * 之外的一切都要便宜（见 MusicSource 与 MusicProblems 的类注释）。
 *
 * 而进度条只需要【正在放的那一首】的时长。那一首本来就要打开，顺手算出来
 * 就行，不打开的那些一分钱不花。
 *
 * 谁实现它
 *
 * {@link PcmAudioStream} 实现，具体的数由各个解码器算：MP3 读 Xing 头里的
 * 帧数或按固定码率折算，WAV 用 javax.sound 报的帧数。OGG 走的是 Minecraft
 * 自己的 JOrbisAudioStream，我们改不了它，也就没有这个信息 —— 那一档就是
 * 不知道，进度条不画，这比画一个瞎猜的比例诚实。
 */
public interface KnownDuration {

    /** 不知道多长 */
    long UNKNOWN = -1L;

    /** 这条流有多长（毫秒）；不知道则是 {@link #UNKNOWN} */
    long durationMs();
}
