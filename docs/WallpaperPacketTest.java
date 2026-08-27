package com.november.mcphone.feature.settings.net;

import com.november.mcphone.core.PhonePlayerData;
import com.november.mcphone.feature.settings.WallpaperData;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 壁纸那一对网络包的断言测试 —— 线格式往返，以及存档往返。
 *
 * 网络层最该验的就是"编出去的和解回来的是同一个东西"。这一条不需要进游戏
 * 也不需要开服务器：FriendlyByteBuf 只是 netty 缓冲区的一层包装，
 * CompoundTag 与 NbtOps 也都是纯数据，都能在 JVM 里直接跑。
 *
 * 跑法（要挂 Forge 的编译类路径，因为用到 FriendlyByteBuf）：
 *   见 docs/PORTING.md 的"复现这套筛法"一节，把 out 目录和本文件一起编了即可。
 */
public class WallpaperPacketTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    /** 造一个空缓冲区，写进去再读回来 */
    static String roundTripSet(String name) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SetWallpaperPacket.encode(new SetWallpaperPacket(name), buf);
        return SetWallpaperPacket.decode(buf).wallpaperFileName();
    }

    static String roundTripSync(String name) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SyncWallpaperPacket.encode(new SyncWallpaperPacket(name), buf);
        return SyncWallpaperPacket.decode(buf).wallpaperFileName();
    }

    /** 两个方向的包都要能原样往返 */
    static void bothDirectionsRoundTrip() {
        String[] samples = {
                "",                          // 空串＝用默认背景，是有意义的值不是缺失值
                "sunset.png",
                "my wallpaper.png",          // 带空格
                "壁纸.png",                   // 非 ASCII，UTF-8 编码要能扛住
                "a/b/../c.png",              // 路径味道的串：包本身不该做解释，原样带过去
                "\u0000\u001f",              // 控制字符
        };
        for (String s : samples) {
            eq(roundTripSet(s), s, "C2S 往返: " + debug(s));
            eq(roundTripSync(s), s, "S2C 往返: " + debug(s));
        }
    }

    /** 读完之后缓冲区应当正好读空，多一个字节少一个字节都是错 */
    static void consumesExactlyWhatItWrote() {
        for (String s : new String[]{"", "x.png", "长一点的名字用来占字节.png"}) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            SetWallpaperPacket.encode(new SetWallpaperPacket(s), buf);
            int written = buf.writerIndex();
            SetWallpaperPacket.decode(buf);
            eq(buf.readerIndex(), written, "读写字节数应相等: " + debug(s));
            eq(buf.readableBytes(), 0, "读完应无剩余: " + debug(s));
        }
    }

    /**
     * 上限：writeUtf 不带参数时是 32767 个字符。
     *
     * 这条在测的是"上限确实存在"，不是"上限是多少好看"。读的是客户端送来的
     * 字节，没有上限就等于允许任何人往服务端存档里塞任意长的串。
     */
    static void hasALengthCap() {
        String justUnder = "a".repeat(32767);
        eq(roundTripSet(justUnder), justUnder, "32767 字符应当能过");

        String tooLong = "a".repeat(32768);
        boolean rejected = false;
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            SetWallpaperPacket.encode(new SetWallpaperPacket(tooLong), buf);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        check(rejected, "32768 字符应当被拒绝");
    }

    /** 玩家数据的存档往返：写进 NBT 再读回来，壁纸不能变 */
    static void playerDataSurvivesNbt() {
        for (String s : new String[]{"", "sunset.png", "壁纸.png"}) {
            PhonePlayerData from = new PhonePlayerData();
            from.setWallpaper(new WallpaperData(s));

            CompoundTag tag = from.serializeNBT();
            PhonePlayerData to = new PhonePlayerData();
            to.deserializeNBT(tag);

            eq(to.wallpaper().wallpaperFileName(), s, "存档往返: " + debug(s));
        }
    }

    /**
     * 读一份坏存档不能崩，要退回默认值。
     *
     * 三种都是真会发生的：这一格从没写过（第一次装 mod）、类型不对（手改的
     * 存档或旧版本写的）、整个 tag 是空的。
     */
    static void badNbtFallsBackToDefault() {
        CompoundTag empty = new CompoundTag();
        PhonePlayerData a = new PhonePlayerData();
        a.setWallpaper(new WallpaperData("会被覆盖.png"));
        a.deserializeNBT(empty);
        eq(a.wallpaper(), WallpaperData.DEFAULT, "空 tag 应退回默认");

        CompoundTag wrongType = new CompoundTag();
        wrongType.putInt("wallpaper_data", 42);
        PhonePlayerData b = new PhonePlayerData();
        b.setWallpaper(new WallpaperData("会被覆盖.png"));
        b.deserializeNBT(wrongType);
        eq(b.wallpaper(), WallpaperData.DEFAULT, "类型不对应退回默认");
    }

    /** copyFrom 要真的拷到，而且拷完两边互不影响 */
    static void copyFromCopies() {
        PhonePlayerData from = new PhonePlayerData();
        from.setWallpaper(new WallpaperData("旧的.png"));

        PhonePlayerData to = new PhonePlayerData();
        to.copyFrom(from);
        eq(to.wallpaper().wallpaperFileName(), "旧的.png", "copyFrom 应当拷到");

        from.setWallpaper(new WallpaperData("又改了.png"));
        eq(to.wallpaper().wallpaperFileName(), "旧的.png", "拷完之后两边应当互不影响");
    }

    static String debug(String s) {
        return "\"" + s.replace("\u0000", "\\0").replace("\u001f", "\\x1f") + "\"("
                + s.length() + ")";
    }

    public static void main(String[] args) {
        bothDirectionsRoundTrip();
        consumesExactlyWhatItWrote();
        hasALengthCap();
        playerDataSurvivesNbt();
        badNbtFallsBackToDefault();
        copyFromCopies();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }
}
