package com.november.mcphone.core.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AtomicWrite#replace} 的断言测试。
 *
 * <h2>它守的是什么</h2>
 *
 * 这三份文件的读取侧都是 {@code catch (Exception)} 之后按空处理 ——
 * 阅读进度、书架、主屏装了哪些 App。那是对的，一份坏文件不该把界面带崩。
 *
 * 但正因为如此，<b>写坏了不会有人发现</b>：没有异常、没有红字，只有日志里一行
 * 玩家看不到的警告，然后所有内容回到默认。所以「写到一半失败时旧内容一个字节没动」
 * 这一条必须被断言钉住 —— 它一旦悄悄失效，症状与「玩家自己清空了书架」分不开。
 *
 * <h2>没测的那一档</h2>
 *
 * 断电。{@link AtomicWrite} 没有 fsync，那是权衡后的决定（理由写在它的类注释里），
 * 而且沙箱里也造不出掉电。这里测的是<b>进程死亡</b>那一档：写的过程中抛异常，
 * 等价于写到一半没了。
 */
public final class AtomicWriteTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mcphone-atomic");
        Path file = dir.resolve("state.json");

        // ── 目标不存在时建得出来 ──
        AtomicWrite.replace(file, w -> w.write("{\"v\":1}"));
        check(read(file).equals("{\"v\":1}"), "第一次写应当把文件建出来");

        // ── 整份替换，不是追加 ──
        AtomicWrite.replace(file, w -> w.write("{\"v\":2}"));
        check(read(file).equals("{\"v\":2}"), "第二次写应当整份替换");

        // ── 写到一半失败：旧内容一个字节都不许动 ──
        //
        // 这是这个类存在的全部理由。直写目标文件时，这一步之后文件是被截断的半份，
        // 而读取侧会把它当成空 —— 所有内容回到默认，还不报错
        {
            boolean threw = false;
            try {
                AtomicWrite.replace(file, w -> {
                    w.write("{\"v\":3,\"half\":");
                    throw new IOException("模拟写到一半进程没了");
                });
            } catch (IOException e) {
                threw = true;
            }
            check(threw, "写的过程中抛异常应当传出来，不该被吞掉");
            check(read(file).equals("{\"v\":2}"), "写失败之后目标文件必须还是上一次那份完整内容");
        }

        // ── 父目录不存在时建得出来 ──
        {
            Path deep = dir.resolve("a/b/c/state.json");
            AtomicWrite.replace(deep, w -> w.write("[]"));
            check(read(deep).equals("[]"), "父目录不存在时应当建出来");
        }

        // ── UTF-8：非 ASCII 原样回来 ──
        {
            Path u = dir.resolve("utf8.json");
            String text = "{\"书\":\"三体·黑暗森林\"}";
            AtomicWrite.replace(u, w -> w.write(text));
            check(read(u).equals(text), "非 ASCII 应当按 UTF-8 原样写回");
        }

        // ── 失败之后不许留下挡路的临时文件残骸把下一次写也带坏 ──
        {
            Path p = dir.resolve("retry.json");
            AtomicWrite.replace(p, w -> w.write("ok1"));
            try {
                AtomicWrite.replace(p, w -> { w.write("bad"); throw new IOException("x"); });
            } catch (IOException ignored) {
                // 预期
            }
            AtomicWrite.replace(p, w -> w.write("ok2"));
            check(read(p).equals("ok2"), "一次失败之后下一次写应当照常成功");
        }

        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
        } else {
            System.out.println("失败 " + failures.size() + " 项，共 " + checks + " 项断言：");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }
}
