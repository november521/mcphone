package com.november.mcphone.core.client;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 整份替换一个文件：<b>先写临时文件，再改名盖过去</b>。
 *
 * <h2>为什么不能直接往目标文件里写</h2>
 *
 * {@link Files#newBufferedWriter} 默认是 {@code CREATE + TRUNCATE_EXISTING}：
 * <b>打开的那一刻目标文件就被截成 0 字节了</b>，内容一点点写回去。写到一半进程没了
 * （崩溃、被杀、强退），留在盘上的就是一份截断的 JSON。
 *
 * 而读的那一侧一律是 {@code catch (Exception)} 之后按空处理 —— 那是对的，
 * 一份坏文件不该把界面带崩。两者合起来的后果却是：
 *
 * <b>丢的不是正在写的那一条，是整份文件。</b>所有小说的阅读进度、整个书架、
 * 主屏上装了哪些 App —— 一次崩溃全部回到默认，而且只在日志里留一行玩家看不到的警告。
 *
 * <h2>写得越勤，越容易撞上</h2>
 *
 * {@code TxtProgress} 的类注释写着「每翻一页就写一次盘……『崩溃/强退/拔电源』从不给
 * 我们『退出时再写』的机会」。那条推理是对的，但直写目标文件时它把自己反过来了：
 * 写得越勤，落进「文件正被截断」那个窗口的机会越多，而每一次的代价是<b>全部</b>。
 *
 * <h2>做法与它的边界</h2>
 *
 * 写进同目录下的 {@code <文件名>.tmp}，写完关流，再 {@code ATOMIC_MOVE} 改名盖过去。
 * 改名在同一个文件系统里是原子的：任何时刻去读，拿到的要么是完整的旧内容，
 * 要么是完整的新内容，不会是半份。
 *
 * ⚠ <b>没有 fsync，所以断电这一档没有完全覆盖。</b>改名本身是原子的，但临时文件的
 * 内容不保证在改名被记录之前已经落到盘上。加 fsync 能补上，代价是每翻一页一次真正的
 * 磁盘同步 —— 那个卡顿玩家感觉得到，而它防的是「掉电且文件系统没有把写序排在改名前」
 * 这一档。<b>没加是权衡后的决定，不是漏了</b>：进程死亡（崩溃、被杀、强退）是常见的那一档，
 * 已经被覆盖住了。
 *
 * ⚠ 临时文件可能留下来：写 {@code .tmp} 的过程中进程死掉，那份残骸会留在目录里。
 * 无害 —— 下次写同一个文件时会被覆盖，而且它永远不会被读。
 */
public final class AtomicWrite {

    private AtomicWrite() {}

    /** 往流里写内容的那一段。抛 IOException 交给调用方处理。 */
    @FunctionalInterface
    public interface Body {
        void writeTo(Writer w) throws IOException;
    }

    /**
     * 用 {@code body} 写出来的内容整份替换 {@code file}，UTF-8。
     *
     * 父目录不存在会建出来。失败时抛 {@link IOException}，目标文件<b>一个字节都没动</b>。
     */
    public static void replace(Path file, Body body) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            body.writeTo(w);
        }

        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 跨文件系统才会走到这里，而临时文件就在目标文件旁边，正常不会。
            // 退回非原子的替换：仍然比直写目标文件强 —— 至少内容是完整写完才开始搬的
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
