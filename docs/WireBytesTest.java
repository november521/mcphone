package com.november.mcphone.core.net;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Wire#writeBytes} / {@link Wire#readBytes} 的断言测试。
 *
 * <h2>为什么单独有这一份</h2>
 *
 * 这两个方法一度<b>在任何一个目标上都没有断言碰过</b>：
 *
 * <ul>
 *   <li>调用方只有 Forge 1.20.1 那一支 —— 1.21.1 上聊天图片走
 *       {@code ByteBufCodecs.byteArray}，那是 1.20.5+ 才有的；
 *   <li>能覆盖它们的那几份编解码测试住在 {@code layers/version/1.20.5+/docs/}，
 *       而 1.20.1 不挂那一层。
 * </ul>
 *
 * 一边有调用方没测试、一边有测试没调用方，中间那格是空的。而它守的是
 * {@code ChatImage.CHUNK_BYTES}（16 KiB）那道上限：漏掉之后，一个伪造的包就能让
 * 服务端按包里写的长度去分配内存。
 *
 * 所以这一份放在<b>中立的</b> {@code docs/} 下 —— {@code Wire} 只碰
 * {@code FriendlyByteBuf}，每个目标都跑得了它。
 *
 * <h2>上限必须拦在分配之前</h2>
 *
 * 读那一侧靠 {@code readByteArray(max)}：它先读长度、比上限、再分配。写成
 * 「先 readByteArray() 再判长度」的话，判断发生在分配之后，上限就白设了。
 */
public class WireBytesTest {

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

    static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    public static void main(String[] args) {
        // ── 往返 ──
        for (int n : new int[]{0, 1, 2, 255, 256, 1024, 16 * 1024}) {
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) data[i] = (byte) (i * 31 + 7);
            FriendlyByteBuf b = buf();
            Wire.writeBytes(b, data, 16 * 1024);
            byte[] back = Wire.readBytes(b, 16 * 1024);
            eq(back.length, n, "往返长度 n=" + n);
            check(java.util.Arrays.equals(data, back), "往返内容 n=" + n);
            eq(b.readableBytes(), 0, "读完不该有剩余 n=" + n);
        }

        // ── 写超上限要抛，且抛的是 EncoderException ──
        {
            byte[] tooBig = new byte[16 * 1024 + 1];
            FriendlyByteBuf b = buf();
            boolean threw = false;
            try {
                Wire.writeBytes(b, tooBig, 16 * 1024);
            } catch (EncoderException e) {
                threw = true;
                check(e.getMessage() != null && e.getMessage().contains("16384"),
                        "写超上限的报错里要带上限值，实际：" + e.getMessage());
            }
            check(threw, "写超上限必须抛 EncoderException");
            eq(b.readableBytes(), 0, "抛了就不该已经写进去");
        }

        // ── 恰好等于上限要放行（边界） ──
        {
            byte[] exact = new byte[16 * 1024];
            FriendlyByteBuf b = buf();
            boolean ok = true;
            try {
                Wire.writeBytes(b, exact, 16 * 1024);
            } catch (RuntimeException e) {
                ok = false;
            }
            check(ok, "恰好等于上限必须放行，不能是 > 写成了 >=");
        }

        // ── 读超上限要抛，【而且要在分配之前】 ──
        //
        // 构造一个「声称自己有 1 GiB」的包：真去分配就会 OOM 或者卡住，
        // 拦在分配之前才会是一个干脆的 DecoderException。
        {
            FriendlyByteBuf b = buf();
            b.writeVarInt(1 << 30);          // 只写长度，不写内容
            boolean threw = false;
            try {
                Wire.readBytes(b, 16 * 1024);
            } catch (DecoderException e) {
                threw = true;
            }
            check(threw, "读超上限必须抛 DecoderException（且拦在分配之前）");
        }

        // ── 负长度不能变成分配请求 ──
        {
            FriendlyByteBuf b = buf();
            b.writeVarInt(-1);
            boolean threw = false;
            try {
                Wire.readBytes(b, 16 * 1024);
            } catch (RuntimeException e) {
                threw = true;
            }
            check(threw, "负长度必须抛");
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
