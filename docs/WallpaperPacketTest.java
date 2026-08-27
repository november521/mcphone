package com.november.mcphone.feature.settings.net;

import com.november.mcphone.feature.settings.WallpaperData;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
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
 * 【但碰 ItemStack 就不行了】——它的静态初始化要查 BuiltInRegistries，
 * 而那要求先跑 Minecraft 的 Bootstrap，在 FML 之外起不来。所以玩家数据
 * 容器（PhonePlayerData）那一层测不了，见下面 wallpaperCodecRoundTrip 的注释。
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

    /**
     * 壁纸数据的存档往返：过一遍 Codec 再读回来，内容不能变。
     *
     * 【这里测的是 WallpaperData.CODEC 而不是 PhonePlayerData】，是有原因的：
     * PhonePlayerData 自从带上唱片仓字段（DiscState 含 ItemStack）之后，
     * 光是 new 一个就会触发 ItemStack 的静态初始化去碰 BuiltInRegistries，
     * 而那要求先跑 Minecraft 的 Bootstrap —— 在 FML 之外跑不起来
     * （会卡在 Forge 事件总线的初始化上）。
     *
     * 这个 docs/ 下的测试架子的前提就是"不需要 Minecraft"，所以容器那一层
     * 只能进游戏验。这里退而测它调用的那个 Codec，序列化逻辑本身仍然覆盖到。
     */
    static void wallpaperCodecRoundTrip() {
        for (String name : new String[]{"", "sunset.png", "壁纸.png", "a/b/../c.png"}) {
            WallpaperData from = new WallpaperData(name);

            Tag encoded = WallpaperData.CODEC.encodeStart(NbtOps.INSTANCE, from)
                    .result().orElse(null);
            check(encoded != null, "应当能编码: " + debug(name));
            if (encoded == null) continue;

            WallpaperData back = WallpaperData.CODEC.parse(NbtOps.INSTANCE, encoded)
                    .result().orElse(null);
            eq(back, from, "存档往返: " + debug(name));
        }
    }

    /**
     * 读一份坏数据不能崩，要能判出失败让调用方退回默认值。
     *
     * 两种都是真会发生的：类型不对（手改的存档或旧版本写的）、字段缺失。
     * PhonePlayerData.deserializeNBT 靠的就是这里的 result() 为空来退回默认。
     */
    static void badNbtIsRejectedNotThrown() {
        CompoundTag wrongType = new CompoundTag();
        wrongType.putInt("wallpaper", 42);
        check(WallpaperData.CODEC.parse(NbtOps.INSTANCE, wrongType).result().isEmpty(),
                "类型不对应当解析失败而不是抛异常");

        CompoundTag missing = new CompoundTag();
        check(WallpaperData.CODEC.parse(NbtOps.INSTANCE, missing).result().isEmpty(),
                "字段缺失应当解析失败而不是抛异常");
    }

    static String debug(String s) {
        return "\"" + s.replace("\u0000", "\\0").replace("\u001f", "\\x1f") + "\"("
                + s.length() + ")";
    }

    public static void main(String[] args) {
        // 【必须先 bootstrap】。PhonePlayerData 里有一个 DiscState，它含
        // ItemStack，而 ItemStack 的静态 CODEC 会去碰 BuiltInRegistries——
        // 没 bootstrap 过就抛 "Not bootstrapped"，而且是在类初始化阶段抛，
        // 报错栈里看不出跟测试本身有任何关系。
        //
        // 这一条是随着 PhonePlayerData 加上唱片仓字段才出现的：在那之前
        // 这个测试完全不碰注册表。往那个类里加字段时留意这里。
        bothDirectionsRoundTrip();
        consumesExactlyWhatItWrote();
        hasALengthCap();
        wallpaperCodecRoundTrip();
        badNbtIsRejectedNotThrown();

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
