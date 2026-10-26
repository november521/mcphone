package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.core.PhonePaths;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 货币存档的原子快照：{@code <世界>/mcphone/economy/economy.dat}。
 *
 * <p>Forge 1.20.1 与 Fabric（原版）写 SavedData 是就地截断重写，写到一半被杀，那一份就坏了、上一份也没了；
 * 这一份写临时文件、fsync、再原子改名 —— 任何时刻去读，拿到的要么是完整的上一份，要么是完整的这一份。
 * <b>由 {@link EconomyData#write} 在世界保存时、主线程上写，与 SavedData 那份是同一个 tag</b>，不是另一份各自序列化的状态。
 *
 * <p>格式是 gzip 包着的 NBT。gzip 自带 CRC：读到尾巴才校验，所以 {@link #read} 会把流读完。
 */
final class EconomySnapshot {

    private EconomySnapshot() {
    }

    static Path path(MinecraftServer server) {
        return PhonePaths.WORLD_ECONOMY.in(server.getWorldPath(LevelResource.ROOT)).resolve("economy.dat");
    }

    /** 整份替换。失败时抛，目标文件一个字节都没动。 */
    static void write(Path file, CompoundTag tag) throws IOException {
        write(file, out -> NbtIo.write(tag, out));
    }

    /** 往流里写内容的那一段。拆出来是为了测得到"写到一半出错"。 */
    @FunctionalInterface
    interface Body {
        void writeTo(DataOutputStream out) throws IOException;
    }

    static void write(Path file, Body body) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            GZIPOutputStream gz = new GZIPOutputStream(Channels.newOutputStream(ch));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(gz));
            body.writeTo(out);
            out.flush();
            gz.finish();
            // 改名之前内容先落到盘上：否则掉电后改名生效了、内容却没有，读到的是一份空文件
            ch.force(true);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 临时文件就在旁边，同一个文件系统，正常到不了这里
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 写快照失败时，上一份完整快照挪到这里（{@link EconomyData}）。开服不自动用它 —— 它比 SavedData 旧；
     * 两份都读不出、整份锁住时在日志里点名它，由服主决定要不要回到那一次保存。
     */
    static Path stalePath(Path file) {
        return file.resolveSibling(file.getFileName() + ".stale");
    }

    /** 解压后最多多大。正常一份远小于这个数；再大就是坏了，不许为它把堆吃光。 */
    private static final int MAX_BYTES = 256 * 1024 * 1024;

    /** 读不出来（没有、截断、CRC 不对、不是 NBT、长度字段坏成巨大值、解压后超大）一律抛 IOException。 */
    static CompoundTag read(Path file) throws IOException {
        return read(file, MAX_BYTES);
    }

    static CompoundTag read(Path file, int maxBytes) throws IOException {
        try {
            byte[] data;
            try (InputStream raw = Files.newInputStream(file);
                 GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(raw))) {
                // 先整份读完：读到流尾 gzip 才核 CRC —— 先核再解析，坏数据进不了解析器
                data = gz.readNBytes(maxBytes + 1);
                if (data.length > maxBytes) throw new IOException("快照解压后超过 " + maxBytes + " 字节");
            }
            return NbtIo.read(new DataInputStream(new java.io.ByteArrayInputStream(data)));
        } catch (RuntimeException | OutOfMemoryError e) {
            // 解压到上限要临时占两倍的堆；长度字段坏成巨大值时原版解析器会直接按它开数组。
            // 都接住、当成读不出，回退 SavedData 那份 —— 抛到开服那一层就是起不来
            throw new IOException("快照读不出来：" + e, e);
        }
    }
}
