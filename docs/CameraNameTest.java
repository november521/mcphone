package com.november.mcphone.feature.camera.client;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link CameraStamp#nextName} 的断言测试。
 *
 * <h2>钉的是哪一条</h2>
 *
 * 同一秒、站在同一格上连拍两张，两次算出来的基底一模一样。而原版
 * {@code Screenshot.grab} 的写盘是异步的（丢进 {@code Util.ioPool()}），所以第二张
 * 算名字时第一张还没落盘 —— 只问 {@code exists()} 的话两张会拿到同一个名字，
 * <b>后写的盖掉先写的</b>：一张照片消失，游戏里却弹了两条「保存成功」。
 *
 * 所以这里的每一次调用都<b>不去创建文件</b>：那正是在模拟「还在 ioPool 队列里」的状态。
 * 名字必须两两不同。
 */
public final class CameraNameTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mcphone-shots");
        File d = dir.toFile();

        // ── 一张都没落盘时连拍：名字必须各不相同 ──
        {
            String base = "2026-09-10_12.00.00_X1_Y2_Z3";
            Set<String> got = new LinkedHashSet<>();
            for (int i = 0; i < 5; i++) got.add(CameraStamp.nextName(d, base));
            check(got.size() == 5, "同一基底连拍 5 张应当拿到 5 个不同的名字（一张都没落盘）");
            check(got.contains(base + ".png"), "第一张应当是不带序号的那个名字");
            check(got.contains(base + "_1.png"), "第二张应当缀 _1");
        }

        // ── 基底一换就从头开始：不同秒/不同格之间不会撞名 ──
        {
            String other = "2026-09-10_12.00.01_X1_Y2_Z3";
            check(CameraStamp.nextName(d, other).equals(other + ".png"),
                    "换了基底应当从不带序号的名字重新开始");
        }

        // ── 盘上已有的要让开（上一局留下的照片） ──
        {
            String base = "2026-09-10_13.00.00_X9_Y9_Z9";
            Files.createFile(dir.resolve(base + ".png"));
            Files.createFile(dir.resolve(base + "_1.png"));
            check(CameraStamp.nextName(d, base).equals(base + "_2.png"),
                    "盘上已有的名字应当跳过");
        }

        // ── 盘上有的与本进程刚发出去的，两条都要挡 ──
        {
            String base = "2026-09-10_14.00.00_X0_Y0_Z0";
            Files.createFile(dir.resolve(base + ".png"));       // 上一局留下的
            String a = CameraStamp.nextName(d, base);           // 应当是 _1
            String b = CameraStamp.nextName(d, base);           // 还没落盘，应当是 _2
            check(a.equals(base + "_1.png"), "盘上占了不带序号的那个，这一张应当是 _1");
            check(b.equals(base + "_2.png"), "上一张还在队列里没落盘，这一张也不许与它同名");
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
