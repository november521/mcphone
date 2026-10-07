package com.november.mcphone.feature.music.client.playback;

import com.mojang.logging.LogUtils;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 独立设备中的耳机通道。不能复用原版 Channel：其他模组会给那个类的 play 全局注入
 * 世界声学处理，使用原版设备上的效果槽；我们的设备与那些句柄不属于同一个上下文。
 * 只保留本地播放器需要的流式播放能力，所有调用仍须位于 LocalPlayback.Scope 内。
 */
final class LocalStreamChannel implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BUFFER_COUNT = 4;
    private final Driver driver;
    private final AudioStream stream;
    private final Set<Integer> buffers = new LinkedHashSet<>();
    private final int source;
    private final int chunkBytes;
    private boolean exhausted;
    private boolean closed;

    /** 驱动边界只隔离原生操作，测试用它验证队列所有权与故障回收，不模拟播放器本身。 */
    interface Driver {
        int createSource();
        void configure(int source);
        int state(int source);
        void play(int source);
        void pause(int source);
        void stop(int source);
        void volume(int source, float volume);
        int createBuffer(ByteBuffer data, AudioFormat format);
        void queue(int source, int buffer);
        int processed(int source);
        int unqueue(int source);
        void deleteSource(int source);
        void deleteBuffer(int buffer);
    }

    /** 流所有权在调用时移交；分配或预填失败也会关闭流、回收已分配的声源和缓冲。 */
    static LocalStreamChannel open(AudioStream stream) throws IOException {
        return open(stream, new OpenAlDriver());
    }

    static LocalStreamChannel open(AudioStream stream, Driver driver) throws IOException {
        LocalStreamChannel channel = null;
        int source = 0;
        try {
            AudioFormat format = stream.getFormat();
            long chunk = (long) format.getChannels() * format.getSampleSizeInBits() / 8
                    * (long) format.getSampleRate();
            if (chunk <= 0 || chunk > Integer.MAX_VALUE) {
                throw new IOException("无效的音频缓冲大小: " + format);
            }
            source = driver.createSource();
            if (source == 0) throw new IOException("没有可用的本地音频声源");
            channel = new LocalStreamChannel(stream, driver, source, (int) chunk);
            driver.configure(source);
            channel.fill(BUFFER_COUNT);
            return channel;
        } catch (IOException | RuntimeException | Error failure) {
            // Error 只做资源回收后原样抛出，不能把虚拟机错误当作坏音频吞掉。
            if (channel != null) {
                channel.close();
            } else {
                if (source != 0) {
                    int allocated = source;
                    cleanup(() -> driver.deleteSource(allocated));
                }
                closeStream(stream);
            }
            throw failure;
        }
    }

    private LocalStreamChannel(AudioStream stream, Driver driver, int source, int chunkBytes) {
        this.stream = stream;
        this.driver = driver;
        this.source = source;
        this.chunkBytes = chunkBytes;
    }

    void play() { if (!closed) driver.play(source); }
    void pause() {
        if (!closed && driver.state(source) == AL10.AL_PLAYING) driver.pause(source);
    }
    void unpause() {
        if (!closed && driver.state(source) == AL10.AL_PAUSED) driver.play(source);
    }
    void stop() { if (!closed) driver.stop(source); }
    boolean stopped() { return closed || driver.state(source) == AL10.AL_STOPPED; }
    void setVolume(float volume) { if (!closed) driver.volume(source, volume); }

    void updateStream() {
        if (closed) return;
        int count = driver.processed(source);
        for (int i = 0; i < count; i++) {
            int buffer = driver.unqueue(source);
            // 删除成功后才摘掉，删除异常时 close 仍能回收它。
            driver.deleteBuffer(buffer);
            buffers.remove(buffer);
        }
        try {
            fill(count);
        } catch (IOException failure) {
            // 读流失败按现有停法收尾：不继续读坏流，已排队的声音仍可播完。
            exhausted = true;
            LOGGER.warn("[MCphone] 本地音频流读取失败", failure);
        }
    }

    private void fill(int count) throws IOException {
        for (int i = 0; i < count && !exhausted; i++) {
            ByteBuffer data = stream.read(chunkBytes);
            if (data == null || !data.hasRemaining()) {
                exhausted = true;
                return;
            }
            int buffer = driver.createBuffer(data, stream.getFormat());
            buffers.add(buffer); // queue 失败时也归我们释放。
            driver.queue(source, buffer);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cleanup(() -> driver.stop(source));
        // 删除声源先解除所有缓冲绑定，包括暂停时尚未播放的那几块。
        cleanup(() -> driver.deleteSource(source));
        for (int buffer : buffers) cleanup(() -> driver.deleteBuffer(buffer));
        buffers.clear();
        closeStream(stream);
    }

    private static void cleanup(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("[MCphone] 回收本地音频资源失败", failure);
        }
    }

    private static void closeStream(AudioStream stream) {
        try {
            stream.close();
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[MCphone] 关闭本地音频流失败", failure);
        }
    }

    /** 只操作自己分配的句柄；不引用 SPR、不改原版通道，也不借用游戏的效果槽。 */
    private static final class OpenAlDriver implements Driver {
        @Override public int createSource() {
            int source = AL10.alGenSources();
            try {
                check("分配声源");
                return source;
            } catch (RuntimeException failure) {
                if (source != 0) AL10.alDeleteSources(source);
                throw failure;
            }
        }
        @Override public void configure(int source) {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0F, 0F, 0F);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0F);
            check("配置耳机声源");
        }
        @Override public int state(int source) {
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            check("查询播放状态");
            return state;
        }
        @Override public void play(int source) { AL10.alSourcePlay(source); check("播放"); }
        @Override public void pause(int source) { AL10.alSourcePause(source); check("暂停"); }
        @Override public void stop(int source) { AL10.alSourceStop(source); check("停止"); }
        @Override public void volume(int source, float volume) {
            AL10.alSourcef(source, AL10.AL_GAIN, volume); check("设置音量");
        }
        @Override public int createBuffer(ByteBuffer data, AudioFormat format) {
            int alFormat = audioFormat(format);
            int buffer = AL10.alGenBuffers();
            try {
                check("分配缓冲");
                AL10.alBufferData(buffer, alFormat, data, (int) format.getSampleRate());
                check("写入缓冲");
                return buffer;
            } catch (RuntimeException failure) {
                if (buffer != 0) AL10.alDeleteBuffers(buffer);
                throw failure;
            }
        }
        @Override public void queue(int source, int buffer) {
            AL10.alSourceQueueBuffers(source, buffer); check("排队缓冲");
        }
        @Override public int processed(int source) {
            int count = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
            check("查询已播放缓冲");
            return count;
        }
        @Override public int unqueue(int source) {
            int buffer = AL10.alSourceUnqueueBuffers(source); check("取回缓冲"); return buffer;
        }
        @Override public void deleteSource(int source) { AL10.alDeleteSources(source); check("删除声源"); }
        @Override public void deleteBuffer(int buffer) { AL10.alDeleteBuffers(buffer); check("删除缓冲"); }

        private static int audioFormat(AudioFormat format) {
            boolean pcm = AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    || AudioFormat.Encoding.PCM_UNSIGNED.equals(format.getEncoding());
            int bits = format.getSampleSizeInBits();
            int channels = format.getChannels();
            if (pcm && (channels == 1 || channels == 2)) {
                if (bits == 8) return channels == 1 ? AL10.AL_FORMAT_MONO8 : AL10.AL_FORMAT_STEREO8;
                if (bits == 16 && !format.isBigEndian()) {
                    return channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
                }
            }
            throw new IllegalArgumentException("不支持的本地音频格式: " + format);
        }
        private static void check(String operation) {
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) {
                throw new IllegalStateException(operation + "失败，OpenAL 错误 0x" + Integer.toHexString(error));
            }
        }
    }
}
