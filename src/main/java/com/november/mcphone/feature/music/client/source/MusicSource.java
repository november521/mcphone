package com.november.mcphone.feature.music.client.source;

import com.november.mcphone.feature.music.Track;
import net.minecraft.client.sounds.AudioStream;

import java.io.IOException;
import java.util.List;

/**
 * 一个曲目来源 —— 歌是从哪儿来的。
 *
 * ================================================================
 * 这个接口存在的全部理由
 * ================================================================
 *
 * 播放核心与界面都只认 {@link Track}，不知道歌是躺在硬盘上、装在唱片里、
 * 还是从网上流下来的。加一个来源＝写一个实现 + 在 {@link MusicSources}
 * 的名单里加一行，别的三层一个字都不用改。
 *
 * 这正是给 NetMusic 联动留的口子：那时候多一个 NetMusicSource，
 * list() 返回搜索结果，open() 返回它的音频流，别处不动。
 *
 * ================================================================
 * 实现要守的规矩
 * ================================================================
 *
 * 1. {@link #list()} 必须便宜。界面每帧都可能问一次，所以它只能返回
 *    缓存好的东西。真正的扫描/请求放进 {@link #refresh()}。
 *
 * 2. {@link #open} 只对 {@link Track.Kind#LOCAL} 的曲目有意义。
 *    SHARED 的（唱片）不走这条路——它由服务端播，客户端拿不到也不需要
 *    那份音频数据。
 *
 * 3. 出错要返回 null 或抛 IOException，不要自己弹提示。一首歌打不开是
 *    播放器要处理的事（跳过、提示、记日志），不是音源该越权决定的。
 */
public interface MusicSource {

    /** 稳定标识，写进 {@link Track#sourceId()}。别改，它会进播放记录 */
    String id();

    /** 界面上的分组名。用翻译键，别写死字符串 */
    String displayNameKey();

    /** 当前的曲目。必须便宜，理由见接口注释 */
    List<Track> list();

    /**
     * 重新取一次曲目。
     *
     * 本地音源＝重扫目录，网络音源＝重新请求。由界面在打开 App、
     * 或玩家点「刷新」时调用。
     */
    void refresh();

    /**
     * 把一首曲子打开成 PCM 流。
     *
     * @return 一条流，调用方负责关闭；放不了则返回 null
     */
    AudioStream open(Track track) throws IOException;
}
