package com.november.mcphone.feature.music.client.playback;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** 流式通道的所有权、暂停与错误回收测试；真实 OpenAL 验收另见 docs/local-audio-compat.md。 */
public final class LocalStreamChannelTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        noVanillaChannelDependency();
        lifecycle();
        emptyAndShortStreams();
        startupFailures();
        readFailureDuringPlayback();
        nativeFailureDuringPlayback();
        cleanupFailures();
        System.out.println("Local stream channel assertions: " + checks);
    }

    /** 防止未来又把本地声源接回有全局 Mixin 的原版 Channel。检查实际产物而非源码注释。 */
    private static void noVanillaChannelDependency() throws IOException {
        for (String name : new String[]{"LocalPlayback", "LocalStreamChannel", "LocalStreamChannel$OpenAlDriver"}) {
            try (var input = LocalStreamChannelTest.class.getResourceAsStream(name + ".class")) {
                check(input != null, "找到实际编译产物: " + name);
                String constants = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                check(!constants.contains("com/mojang/blaze3d/audio/Channel"), "本地播放不依赖原版 Channel: " + name);
                check(!constants.contains("com/sonicether/soundphysics"), "本地播放不依赖 SPR 私有 API: " + name);
            }
        }
    }

    private static void lifecycle() throws Exception {
        Stream stream = new Stream(7);
        Driver driver = new Driver();
        LocalStreamChannel channel = LocalStreamChannel.open(stream, driver);
        eq(4, stream.reads, "启动只预填四块");
        eq(16, stream.lastSize, "每块为一秒 PCM");
        eq(4, driver.queue.size(), "预填缓冲全部进入队列");
        check(driver.configured, "先配置耳机声源");
        channel.unpause();
        eq(0, driver.plays, "未暂停时继续不得启动声源");
        channel.play();
        channel.setVolume(0.35F);
        check(driver.gain == 0.35F, "音量传到内部声源");
        check(!channel.stopped(), "播放中");
        channel.pause();
        channel.pause();
        eq(1, driver.pauses, "重复暂停不重复操作");
        eq(AL10.AL_PAUSED, driver.state, "暂停保留声源与队列");
        eq(4, driver.buffers.size(), "暂停不释放缓冲");
        channel.unpause();
        eq(2, driver.plays, "继续使用原声源");
        driver.processed = 2;
        channel.updateStream();
        eq(6, stream.reads, "只补已经消费的两块");
        eq(4, driver.queue.size(), "补流后仍是四块");
        eq(2, driver.deletedBuffers, "旧缓冲及时释放");
        driver.processed = 4;
        channel.updateStream();
        eq(8, stream.reads, "尾部读一次 EOF 后停止读流");
        eq(1, driver.queue.size(), "尾部只排有效数据");
        driver.processed = 1;
        channel.updateStream();
        eq(8, stream.reads, "EOF 后不再向解码器读取");
        check(channel.stopped(), "排队数据播完自然停止");
        channel.close();
        assertReleased(stream, driver, "正常结束");
        int operations = driver.operations;
        channel.close();
        channel.play();
        channel.pause();
        channel.unpause();
        channel.stop();
        channel.setVolume(1F);
        channel.updateStream();
        check(channel.stopped(), "关闭后的状态查询安全");
        eq(operations, driver.operations, "关闭后不能调用已释放的句柄");
        eq(1, stream.closes, "重复关闭幂等");
    }

    private static void emptyAndShortStreams() throws Exception {
        for (int chunks : new int[]{0, 1, 2}) {
            Stream stream = new Stream(chunks);
            Driver driver = new Driver();
            LocalStreamChannel channel = LocalStreamChannel.open(stream, driver);
            eq(chunks, driver.queue.size(), "不把空 EOF 缓冲排进队列");
            eq(chunks + 1, stream.reads, "短流遇到 EOF 立即停止预填");
            channel.play();
            channel.pause();
            channel.close();
            assertReleased(stream, driver, "短流/暂停关闭");
        }
    }

    private static void startupFailures() {
        for (String stage : new String[]{"createSource", "configure", "createBuffer", "queue", "read", "format"}) {
            Stream stream = new Stream(9);
            Driver driver = new Driver();
            if (stage.equals("read")) stream.failAt = 3;
            else if (stage.equals("format")) stream.badFormat = true;
            else driver.failure = stage;
            Throwable problem = failure(() -> LocalStreamChannel.open(stream, driver));
            check(problem instanceof IOException || problem instanceof IllegalStateException, "启动故障保留原因: " + stage);
            assertReleased(stream, driver, "启动失败: " + stage);
            // 同一驱动再次播放：上一首失败不能占住唯一声源。
            driver.failure = "";
            Stream retry = new Stream(1);
            try (LocalStreamChannel ignored = LocalStreamChannel.open(retry, driver)) {
                check(driver.sourceLive, "失败后能重新分配声源: " + stage);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            assertReleased(retry, driver, "启动重试: " + stage);
        }
        Stream emptySourceStream = new Stream(1);
        Driver emptySourceDriver = new Driver();
        emptySourceDriver.noSource = true;
        check(failure(() -> LocalStreamChannel.open(emptySourceStream, emptySourceDriver)) instanceof IOException,
                "声源分配返回零必须失败");
        assertReleased(emptySourceStream, emptySourceDriver, "无声源");

        Stream fatalStream = new Stream(9);
        fatalStream.fatalAt = 3;
        Driver fatalDriver = new Driver();
        Throwable fatal = failure(() -> LocalStreamChannel.open(fatalStream, fatalDriver));
        check(fatal == fatalStream.fatal, "Error 清理后原样抛出");
        assertReleased(fatalStream, fatalDriver, "Error 退出");
    }

    private static void readFailureDuringPlayback() throws Exception {
        Stream stream = new Stream(9);
        stream.failAt = 5;
        Driver driver = new Driver();
        try (LocalStreamChannel channel = LocalStreamChannel.open(stream, driver)) {
            channel.play();
            driver.processed = 2;
            channel.updateStream();
            eq(2, driver.queue.size(), "读流失败时保留尚未播放的缓冲");
            driver.processed = 2;
            channel.updateStream();
            eq(5, stream.reads, "读流失败后不再循环读取坏流");
            check(channel.stopped(), "读流失败后排空并停止");
        }
        assertReleased(stream, driver, "中途读流失败");
    }

    private static void nativeFailureDuringPlayback() throws Exception {
        for (String stage : new String[]{"play", "unqueue", "deleteBuffer"}) {
            Stream stream = new Stream(9);
            Driver driver = new Driver();
            LocalStreamChannel channel = LocalStreamChannel.open(stream, driver);
            driver.failure = stage;
            driver.processed = 2;
            Throwable problem = failure(() -> {
                if (stage.equals("play")) channel.play();
                else channel.updateStream();
            });
            check(problem instanceof IllegalStateException, "中途原生故障交给上层收尾: " + stage);
            driver.failure = "";
            channel.close();
            assertReleased(stream, driver, "中途故障: " + stage);
        }
    }

    private static void cleanupFailures() throws Exception {
        Stream stream = new Stream(9);
        stream.closeFailure = true;
        Driver driver = new Driver();
        LocalStreamChannel channel = LocalStreamChannel.open(stream, driver);
        driver.failure = "stop";
        channel.close();
        assertReleased(stream, driver, "停止和关流异常也完成其他回收");
        channel.close();
        eq(1, stream.closes, "关流抛异常后也不重复关流");
    }

    private static void assertReleased(Stream stream, Driver driver, String context) {
        check(!driver.sourceLive, context + "：声源已释放");
        check(driver.buffers.isEmpty(), context + "：缓冲已释放");
        check(driver.queue.isEmpty(), context + "：没有残留队列");
        eq(1, stream.closes, context + "：流只关闭一次");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(int expected, int actual, String message) {
        check(expected == actual, message + "，期望 " + expected + "，实际 " + actual);
    }
    private interface Operation { void run() throws Exception; }
    private static Throwable failure(Operation action) {
        try { action.run(); } catch (Throwable failure) { return failure; }
        throw new AssertionError("预期操作失败");
    }

    private static final class Stream implements AudioStream {
        private int chunks;
        private int reads;
        private int closes;
        private int lastSize;
        private int failAt;
        private int fatalAt;
        private boolean badFormat;
        private boolean closeFailure;
        private final Error fatal = new AssertionError("测试 Error");
        Stream(int chunks) { this.chunks = chunks; }
        @Override public AudioFormat getFormat() {
            return new AudioFormat(badFormat ? -1F : 8F, 16, 1, true, false);
        }
        @Override public ByteBuffer read(int size) throws IOException {
            reads++;
            lastSize = size;
            if (reads == failAt) throw new IOException("测试读流失败");
            if (reads == fatalAt) throw fatal;
            return ByteBuffer.allocateDirect(chunks-- > 0 ? size : 0);
        }
        @Override public void close() throws IOException {
            closes++;
            if (closeFailure) throw new IOException("测试关流失败");
        }
    }

    /** 驱动同时检查“先解绑再删除缓冲”，避免假驱动放过真实 OpenAL 会拒绝的顺序。 */
    private static final class Driver implements LocalStreamChannel.Driver {
        private final ArrayDeque<Integer> queue = new ArrayDeque<>();
        private final Set<Integer> buffers = new HashSet<>();
        private boolean sourceLive;
        private boolean configured;
        private boolean noSource;
        private int state = AL10.AL_INITIAL;
        private int processed;
        private int nextBuffer;
        private int deletedBuffers;
        private int plays;
        private int pauses;
        private int operations;
        private float gain;
        private String failure = "";
        private void step(String operation) {
            operations++;
            if (failure.equals(operation)) throw new IllegalStateException("测试驱动失败: " + operation);
        }
        @Override public int createSource() {
            step("createSource");
            if (noSource) return 0;
            check(!sourceLive, "不能重复占用同一声源");
            sourceLive = true;
            state = AL10.AL_INITIAL;
            return 1;
        }
        @Override public void configure(int source) { step("configure"); configured = true; }
        @Override public int state(int source) { step("state"); return state; }
        @Override public void play(int source) {
            step("play"); plays++; state = queue.isEmpty() ? AL10.AL_STOPPED : AL10.AL_PLAYING;
        }
        @Override public void pause(int source) { step("pause"); pauses++; state = AL10.AL_PAUSED; }
        @Override public void stop(int source) { step("stop"); state = AL10.AL_STOPPED; }
        @Override public void volume(int source, float volume) { step("volume"); gain = volume; }
        @Override public int createBuffer(ByteBuffer data, AudioFormat format) {
            if (nextBuffer >= 2) step("createBuffer");
            int buffer = ++nextBuffer;
            buffers.add(buffer);
            return buffer;
        }
        @Override public void queue(int source, int buffer) {
            if (queue.size() >= 2) step("queue");
            check(configured && sourceLive && buffers.contains(buffer), "排队时声源与缓冲有效");
            queue.addLast(buffer);
        }
        @Override public int processed(int source) { step("processed"); return processed; }
        @Override public int unqueue(int source) {
            step("unqueue");
            check(processed-- > 0, "只能取回已经消费的缓冲");
            int buffer = queue.removeFirst();
            if (queue.isEmpty()) state = AL10.AL_STOPPED;
            return buffer;
        }
        @Override public void deleteSource(int source) { step("deleteSource"); sourceLive = false; queue.clear(); }
        @Override public void deleteBuffer(int buffer) {
            step("deleteBuffer");
            check(!queue.contains(buffer), "缓冲解绑后才能删除");
            check(buffers.remove(buffer), "不能重复删除缓冲");
            deletedBuffers++;
        }
    }
}
