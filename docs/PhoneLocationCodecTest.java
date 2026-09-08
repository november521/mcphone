package com.november.mcphone.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.InteractionHand;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PhoneLocation 线格式的断言测试 —— 守的是"改设备名不会改错那一部手机"。
 *
 * 为什么这一条要钉死到字节
 *
 * 这个编解码是【手写】的：一个种类字节，后面跟各自的字段。手写的东西读写两侧是两段
 * 各自独立的代码，中间没有任何东西保证它们对得上，而写错的症状不是报错 ——
 * 客户端说"手机在饰品槽第 0 格"，服务端解成"在背包第 3 格"，于是改名改到了另一部
 * 手机上，或者什么都没发生。两边都不抛异常，日志里什么都没有。
 *
 * 更要紧的是这个格式【要跨版本活着】：种类字节的取值写死在 TYPE_ 那四个常量里，
 * 1.20.1 那一支得原样再实现一遍（那边没有 ByteBufCodecs）。所以这份测试断言的不是
 * "自己和自己对得上"，而是"字节序列就是这几个字节" —— 前者两边一起写错也能全绿。
 *
 * 跑法与 ChatMessageCodecTest 相同，现在由 ./gradlew assertTests 统一跑。
 */
public class PhoneLocationCodecTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    /** 把一个位置编成字节 */
    static byte[] bytes(PhoneLocation location) {
        ByteBuf buf = Unpooled.buffer();
        PhoneLocation.STREAM_CODEC.encode(buf, location);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    /** 从字节解回一个位置 */
    static PhoneLocation decode(byte... raw) {
        return PhoneLocation.STREAM_CODEC.decode(Unpooled.wrappedBuffer(raw));
    }

    static void eqBytes(byte[] actual, byte[] expected, String what) {
        checks++;
        if (!Arrays.equals(actual, expected)) {
            failures.add(what + "  期望 " + Arrays.toString(expected)
                    + "，实际 " + Arrays.toString(actual));
        }
    }

    static void roundTrip(PhoneLocation original, String what) {
        checks++;
        PhoneLocation back = PhoneLocation.STREAM_CODEC.decode(
                Unpooled.wrappedBuffer(bytes(original)));
        if (!original.equals(back)) {
            failures.add(what + "  往返之后变了：" + original + " -> " + back);
        }
    }

    public static void main(String[] args) {

        //  一、种类字节：这四个值是跨版本的契约，改一个就是协议不兼容
        eq((int) PhoneLocation.TYPE_MAIN_HAND, 0, "TYPE_MAIN_HAND");
        eq((int) PhoneLocation.TYPE_OFF_HAND, 1, "TYPE_OFF_HAND");
        eq((int) PhoneLocation.TYPE_INVENTORY, 2, "TYPE_INVENTORY");
        eq((int) PhoneLocation.TYPE_CURIO, 3, "TYPE_CURIO");
        eq(PhoneLocation.MAX_SLOT_ID_LENGTH, 64, "MAX_SLOT_ID_LENGTH");

        //  二、逐字节钉死
        // 手上：只有一个种类字节，没有别的字段
        eqBytes(bytes(new PhoneLocation.InHand(InteractionHand.MAIN_HAND)),
                new byte[]{0}, "主手");
        eqBytes(bytes(new PhoneLocation.InHand(InteractionHand.OFF_HAND)),
                new byte[]{1}, "副手");

        // 背包：种类字节 + VarInt 槽位号
        eqBytes(bytes(new PhoneLocation.InInventory(0)), new byte[]{2, 0}, "背包第 0 格");
        eqBytes(bytes(new PhoneLocation.InInventory(17)), new byte[]{2, 17}, "背包第 17 格");
        // 300 = 0b1_0010_1100，VarInt 是两个字节：低七位置续位 -> 0xAC，高位 -> 0x02
        eqBytes(bytes(new PhoneLocation.InInventory(300)),
                new byte[]{2, (byte) 0xAC, 0x02}, "背包第 300 格（跨字节的 VarInt）");

        // 饰品槽：种类字节 + 长度前缀的 UTF-8 + VarInt 序号
        byte[] curio = bytes(new PhoneLocation.InCurio("charm", 0));
        byte[] expectedCurio = new byte[]{3, 5, 'c', 'h', 'a', 'r', 'm', 0};
        eqBytes(curio, expectedCurio, "饰品槽 charm#0");

        // 字段顺序不能对调：slotId 在前、index 在后。调过来的话上面那条会红，
        // 这一条则说明"为什么是这个顺序"——读的一侧照着同一个顺序读
        eqBytes(bytes(new PhoneLocation.InCurio("a", 7)),
                new byte[]{3, 1, 'a', 7}, "饰品槽的字段顺序是 slotId 在前");

        //  三、往返
        roundTrip(new PhoneLocation.InHand(InteractionHand.MAIN_HAND), "主手");
        roundTrip(new PhoneLocation.InHand(InteractionHand.OFF_HAND), "副手");
        for (int slot : new int[]{0, 1, 8, 35, 40, 300, 65535}) {
            roundTrip(new PhoneLocation.InInventory(slot), "背包第 " + slot + " 格");
        }
        roundTrip(new PhoneLocation.InCurio("charm", 0), "饰品槽 charm#0");
        roundTrip(new PhoneLocation.InCurio("necklace", 3), "饰品槽 necklace#3");
        roundTrip(new PhoneLocation.InCurio("", 0), "饰品槽 空 slotId");

        // 失效的槽位号照样要能往返：线格式这一层不该拦，拦了就是解码抛异常，
        // 而 netty 的解码异常等于断开这条连接。挡它的是上面各层，且【两种位置不是同一层挡的】：
        //
        //   InInventory  PhoneLocation.InInventory.resolve 自己判 slot < 0，返回空堆
        //   InCurio      index 直接递给 CuriosApi 的 findCurio(slotId, index)，
        //                下界在 Curios 手里，不在这个仓库里
        //
        // 后者暴露面有限：writeBack 在 isPhone 判过之后才走，写那一侧到不了；只有 resolve
        // 那一跳会把负数递出去，而它跑在 enqueueWork 里，异常只会记日志、不会崩服。
        // 但"谁拦什么"这件事得写下来——这份测试的立意就是把各层的责任说清楚。
        roundTrip(new PhoneLocation.InInventory(-1), "背包第 -1 格（失效值，resolve 自己拦）");
        roundTrip(new PhoneLocation.InCurio("charm", -1), "饰品槽序号 -1（失效值，下界由 Curios 拦）");

        //  四、认不出来的种类字节，退回主手而不是抛
        // 这是刻意的：版本不一致或伪造客户端送来未知值时，宁可退回一个无害的默认位置，
        // 也不要打断整条连接——反正服务端还要验一次那儿是不是真有手机
        eq(decode((byte) 0), new PhoneLocation.InHand(InteractionHand.MAIN_HAND),
                "种类 0 = 主手");
        eq(decode((byte) 99), new PhoneLocation.InHand(InteractionHand.MAIN_HAND),
                "未知种类 99 退回主手");
        eq(decode((byte) -1), new PhoneLocation.InHand(InteractionHand.MAIN_HAND),
                "未知种类 -1 退回主手");
        eq(decode((byte) 4), new PhoneLocation.InHand(InteractionHand.MAIN_HAND),
                "未知种类 4（紧挨着最大的那个）退回主手");

        //  五、slotId 的长度上限，两侧都要拦
        String justFits = "x".repeat(PhoneLocation.MAX_SLOT_ID_LENGTH);
        roundTrip(new PhoneLocation.InCurio(justFits, 0), "slotId 正好 64 个字符");

        String tooLong = "x".repeat(PhoneLocation.MAX_SLOT_ID_LENGTH + 1);
        checks++;
        try {
            bytes(new PhoneLocation.InCurio(tooLong, 0));
            failures.add("slotId 超过 64 时编码应当抛，实际没抛");
        } catch (RuntimeException expected) {
            // 编码这一侧拦住了，正是我们要的：抛在发的那一端，指着攒出这串东西的那一行。
            // 不拦的话错误会挪到收的那一端，而 netty 的解码异常等于把对方踢下线
        }

        // 上限按【字符数】算，不是字节数 —— 这一条是移植时最容易搞错的地方。
        //
        // stringUtf8(64) 拦两道：先看 String.length() 是不是超过 64，再看编码后的字节数
        // 是不是超过 64 × 3。所以 64 个汉字（64 字符 / 192 字节）刚好两道都贴边过关，
        // 65 个汉字则是【字符数】那道拦下的，不是字节数。
        //
        // 1.20.1 那一支没有 ByteBufCodecs，这段得自己写一遍。照"字节数不超过 64"写的话
        // 中文槽位名会莫名其妙发不出去，而照"只看字符数"写则少了那道 3 倍上限。
        roundTrip(new PhoneLocation.InCurio("槽".repeat(PhoneLocation.MAX_SLOT_ID_LENGTH), 0),
                "64 个汉字的 slotId（64 字符 / 192 字节，贴边过关）");

        checks++;
        try {
            bytes(new PhoneLocation.InCurio("槽".repeat(PhoneLocation.MAX_SLOT_ID_LENGTH + 1), 0));
            failures.add("65 个汉字应当超限（字符数那道），实际没抛");
        } catch (RuntimeException expected) {
            // 意料之中
        }

        //  六、UTF-8 真的是 UTF-8
        PhoneLocation cjkOk = new PhoneLocation.InCurio("槽", 0);
        byte[] cjkBytes = bytes(cjkOk);
        byte[] raw = "槽".getBytes(StandardCharsets.UTF_8);
        eq(cjkBytes.length, 2 + raw.length + 1, "一个汉字的 slotId 占 种类+长度+3字节+序号");
        roundTrip(cjkOk, "饰品槽 中文 slotId");

        //  收尾
        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 项：");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }
}
