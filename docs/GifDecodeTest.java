package com.november.mcphone.core.client;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 拆 GIF 那条路的断言测试。要 Minecraft 的类路径（ImageCodec 引了 NativeImage），照着下面两行跑：
 *
 * 跑法：./gradlew assertTests（在 platforms/<目标名>/ 下），它会把 docs/ 里
 * 带 main() 的都编好跑一遍。不必手工拼类路径 —— 仓库改成多平台目录之后，
 * 手工那套写死的相对路径会指向搬家前遗留的旧 build/，编译进去的是过期的类，
 * 而且不报错。
 */
public class GifDecodeTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    /** 调色板：挑得远一点，缩放插值之后仍分得开 */
    static final int[] PAL = {
            0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00,
            0xFF00FF, 0xFFFFFF, 0x000000, 0x808080,
    };
    static final int RED = 0, GREEN = 1, BLUE = 2, YELLOW = 3, WHITE = 5, CLEAR_IDX = 7;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mcphone-gif-test");

        //  1. 基线：整幅帧、统一延迟
        Gif g1 = new Gif(32, 32, true);
        g1.loopExtension();
        for (int i = 0; i < 4; i++) { g1.gce(10, 0, -1); g1.frame(0, 0, 32, 32, fill(32, 32, i), false, false); }
        g1.trailer();
        GifCodec.Animation a1 = GifCodec.read(save(dir, "baseline.gif", g1), 64, 36);
        check(a1 != null && a1.frames().size() == 4, "基线应当拆出 4 帧");
        check(a1 != null && a1.frameMs() == 100, "基线每帧应当是 100 毫秒");
        if (a1 != null) {
            boolean colors = true;
            for (int i = 0; i < 4; i++) colors &= rgb(a1.frames().get(i), 16, 16) == PAL[i];
            check(colors, "基线每帧的颜色应当各是各的");
        }

        //  2. 局部帧 + 偏移（优化过的动图几乎都是这个形状）
        Gif g2 = new Gif(32, 32, true);
        g2.gce(10, 1, -1); g2.frame(0, 0, 32, 32, fill(32, 32, RED), false, false);
        g2.gce(10, 1, -1); g2.frame(8, 8, 8, 8, fill(8, 8, GREEN), false, false);
        g2.gce(10, 1, -1); g2.frame(20, 20, 8, 8, fill(8, 8, BLUE), false, false);
        g2.trailer();
        GifCodec.Animation a2 = GifCodec.read(save(dir, "partial.gif", g2), 64, 36);
        check(a2 != null && a2.frames().size() == 3, "局部帧应当拆出 3 帧");
        if (a2 != null && a2.frames().size() == 3) {
            BufferedImage f = a2.frames().get(2);
            check(rgb(f, 10, 10) == PAL[GREEN], "第二帧那块应当留在原处（doNotDispose）");
            check(rgb(f, 22, 22) == PAL[BLUE], "第三帧应当落在它自己的偏移上");
            check(rgb(f, 2, 2) == PAL[RED], "没被盖住的地方应当还是第一帧的底");
        }

        //  3. disposal = restoreToBackgroundColor：那一块要擦成透明
        Gif g3 = new Gif(32, 32, true);
        g3.gce(10, 1, -1); g3.frame(0, 0, 32, 32, fill(32, 32, RED), false, false);
        g3.gce(10, 2, -1); g3.frame(8, 8, 8, 8, fill(8, 8, GREEN), false, false);
        g3.gce(10, 1, -1); g3.frame(20, 20, 8, 8, fill(8, 8, BLUE), false, false);
        g3.trailer();
        GifCodec.Animation a3 = GifCodec.read(save(dir, "restore-bg.gif", g3), 64, 36);
        if (a3 != null && a3.frames().size() == 3) {
            check(rgb(a3.frames().get(1), 10, 10) == PAL[GREEN], "restoreToBackgroundColor 的那一帧本身要画出来");
            check(alpha(a3.frames().get(2), 10, 10) == 0, "下一帧里那块应当被擦成透明");
        } else check(false, "restoreToBackgroundColor 应当拆出 3 帧");

        //  4. disposal = restoreToPrevious：画完要还原成画它之前的样子
        Gif g4 = new Gif(32, 32, true);
        g4.gce(10, 1, -1); g4.frame(0, 0, 32, 32, fill(32, 32, RED), false, false);
        g4.gce(10, 3, -1); g4.frame(8, 8, 8, 8, fill(8, 8, GREEN), false, false);
        g4.gce(10, 1, -1); g4.frame(20, 20, 8, 8, fill(8, 8, BLUE), false, false);
        g4.trailer();
        GifCodec.Animation a4 = GifCodec.read(save(dir, "restore-prev.gif", g4), 64, 36);
        if (a4 != null && a4.frames().size() == 3) {
            check(rgb(a4.frames().get(1), 10, 10) == PAL[GREEN], "restoreToPrevious 的那一帧本身要画出来");
            check(rgb(a4.frames().get(2), 10, 10) == PAL[RED], "下一帧里那块应当还原成上一帧");
        } else check(false, "restoreToPrevious 应当拆出 3 帧");

        //  5. 透明索引：透明处要透出下面那一帧，不能变成黑块
        Gif g5 = new Gif(32, 32, true);
        g5.gce(10, 1, -1); g5.frame(0, 0, 32, 32, fill(32, 32, RED), false, false);
        g5.gce(10, 1, CLEAR_IDX); g5.frame(8, 8, 8, 8, fill(8, 8, CLEAR_IDX), false, false);
        g5.trailer();
        GifCodec.Animation a5 = GifCodec.read(save(dir, "transparent.gif", g5), 64, 36);
        check(a5 != null && a5.frames().size() == 2 && rgb(a5.frames().get(1), 10, 10) == PAL[RED],
                "透明像素应当透出下面的画面");

        //  6. 逐帧换调色板
        Gif g6 = new Gif(32, 32, true);
        g6.gce(10, 1, -1); g6.frame(0, 0, 32, 32, fill(32, 32, RED), false, false);
        g6.gce(10, 1, -1); g6.frame(0, 0, 32, 32, fill(32, 32, BLUE), true, false);
        g6.trailer();
        GifCodec.Animation a6 = GifCodec.read(save(dir, "lct.gif", g6), 64, 36);
        check(a6 != null && a6.frames().size() == 2 && rgb(a6.frames().get(1), 16, 16) == PAL[BLUE],
                "局部调色板的帧应当照读");

        //  7. 隔行扫描
        byte[] halves = new byte[32 * 32];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) halves[y * 32 + x] = (byte) (y < 16 ? RED : BLUE);
        Gif g7 = new Gif(32, 32, true);
        g7.gce(10, 1, -1); g7.frame(0, 0, 32, 32, halves, false, true);
        g7.gce(10, 1, -1); g7.frame(0, 0, 32, 32, fill(32, 32, GREEN), false, false);
        g7.trailer();
        GifCodec.Animation a7 = GifCodec.read(save(dir, "interlaced.gif", g7), 64, 36);
        check(a7 != null && rgb(a7.frames().get(0), 16, 4) == PAL[RED]
                        && rgb(a7.frames().get(0), 16, 28) == PAL[BLUE],
                "隔行扫描的帧应当还原成正的");

        //  8. 零延迟按 100 毫秒算
        Gif g8 = new Gif(16, 16, true);
        for (int i = 0; i < 4; i++) { g8.gce(0, 1, -1); g8.frame(0, 0, 16, 16, fill(16, 16, i), false, false); }
        g8.trailer();
        GifCodec.Animation a8 = GifCodec.read(save(dir, "zero-delay.gif", g8), 64, 36);
        check(a8 != null && a8.frameMs() == 100, "零延迟应当按 100 毫秒播");

        //  9. 延迟不统一时取众数
        int[] delays = {10, 10, 50, 10, 10};
        Gif g9 = new Gif(16, 16, true);
        for (int i = 0; i < delays.length; i++) {
            g9.gce(delays[i], 1, -1); g9.frame(0, 0, 16, 16, fill(16, 16, i % 4), false, false);
        }
        g9.trailer();
        GifCodec.Animation a9 = GifCodec.read(save(dir, "mixed-delay.gif", g9), 64, 36);
        check(a9 != null && a9.frameMs() == 100, "延迟不统一时应当取众数");

        // 10. 帧数超上限：隔帧留，延迟跟着乘上去
        Gif g10 = new Gif(16, 16, true);
        for (int i = 0; i < 50; i++) { g10.gce(10, 1, -1); g10.frame(0, 0, 16, 16, fill(16, 16, i % 8), false, false); }
        g10.trailer();
        GifCodec.Animation a10 = GifCodec.read(save(dir, "long.gif", g10), 64, 36);
        check(a10 != null && a10.frames().size() <= 36, "帧数不应当超过上限");
        check(a10 != null && a10.frameMs() == 200, "抽稀之后每帧应当停两倍的时间");

        // 11. 缺全局调色板、GIF87a、夹杂扩展块、尾部挂垃圾——都照读
        Gif g11 = new Gif(32, 32, false);
        g11.gce(10, 1, -1); g11.frame(0, 0, 32, 32, fill(32, 32, RED), true, false);
        g11.gce(10, 1, -1); g11.frame(0, 0, 32, 32, fill(32, 32, BLUE), true, false);
        g11.trailer();
        check(read(dir, "no-gct.gif", g11) == 2, "没有全局调色板也应当读得出来");

        Gif g12 = new Gif(32, 32, true);
        g12.frame(0, 0, 32, 32, fill(32, 32, RED), false, false);
        g12.frame(0, 0, 32, 32, fill(32, 32, BLUE), false, false);
        g12.trailer();
        byte[] old = g12.bytes();
        old[4] = '7';   // GIF89a -> GIF87a，也就没有 GCE
        Path p87 = dir.resolve("gif87a.gif");
        Files.write(p87, old);
        GifCodec.Animation a12 = GifCodec.read(p87, 64, 36);
        check(a12 != null && a12.frames().size() == 2, "GIF87a 应当读得出来");
        check(a12 != null && a12.frameMs() == 100, "没有 GCE 时应当按 100 毫秒算");

        Gif g13 = new Gif(32, 32, true);
        g13.comment("made with some ancient tool");
        g13.gce(10, 1, -1); g13.frame(0, 0, 32, 32, fill(32, 32, RED), false, false);
        g13.plainText();
        g13.gce(10, 1, -1); g13.frame(0, 0, 32, 32, fill(32, 32, BLUE), false, false);
        g13.trailer();
        check(read(dir, "extensions.gif", g13) == 2, "夹杂的扩展块不应当影响拆帧");

        Gif g14 = new Gif(32, 32, true);
        for (int i = 0; i < 4; i++) { g14.gce(10, 1, -1); g14.frame(0, 0, 32, 32, fill(32, 32, i), false, false); }
        g14.trailer();
        byte[] padded = Arrays.copyOf(g14.bytes(), g14.bytes().length + 128);
        Path pJunk = dir.resolve("trailing-junk.gif");
        Files.write(pJunk, padded);
        GifCodec.Animation a14 = GifCodec.read(pJunk, 64, 36);
        check(a14 != null && a14.frames().size() == 4, "尾部多出来的字节不应当影响拆帧");

        Gif g15 = new Gif(32, 32, true);
        for (int i = 0; i < 6; i++) { g15.gce(10, 1, -1); g15.frame(0, 0, 32, 32, fill(32, 32, i % 4), false, false); }
        Path pNoTrailer = dir.resolve("no-trailer.gif");
        Files.write(pNoTrailer, g15.bytes());   // 故意不写结尾那个字节
        GifCodec.Animation a15 = GifCodec.read(pNoTrailer, 64, 36);
        check(a15 != null && a15.frames().size() == 6, "缺 trailer 也应当拆得出来");

        // 12. 静态那条路：看到的必须是铺在逻辑屏幕上的第一帧
        Gif g16 = new Gif(64, 64, true);
        g16.gce(10, 1, CLEAR_IDX); g16.frame(0, 0, 1, 1, fill(1, 1, CLEAR_IDX), false, false);
        g16.gce(10, 1, -1); g16.frame(0, 0, 64, 64, fill(64, 64, RED), false, false);
        g16.gce(10, 1, -1); g16.frame(0, 0, 64, 64, fill(64, 64, BLUE), false, false);
        g16.trailer();
        BufferedImage still16 = ImageCodec.read(save(dir, "tiny-first.gif", g16));
        check(still16 != null && still16.getWidth() == 64 && still16.getHeight() == 64,
                "1×1 占位帧开头的 GIF，静态那条路也应当拿到整幅逻辑屏幕");

        Gif g17 = new Gif(64, 64, true);
        g17.gce(10, 1, -1); g17.frame(16, 16, 32, 32, fill(32, 32, RED), false, false);
        g17.gce(10, 1, -1); g17.frame(16, 16, 32, 32, fill(32, 32, BLUE), false, false);
        g17.trailer();
        Path pOffset = save(dir, "offset-first.gif", g17);
        BufferedImage still17 = ImageCodec.read(pOffset);
        check(still17 != null && still17.getWidth() == 64 && still17.getHeight() == 64,
                "首帧带偏移时，静态那条路不应当把画面裁小");
        check(still17 != null && still17.getWidth() == 64 && still17.getHeight() == 64
                        && rgb(still17, 32, 32) == PAL[RED] && alpha(still17, 2, 2) == 0,
                "首帧应当摆在它自己的偏移上，四周留空");
        GifCodec.Animation a17 = GifCodec.read(pOffset, 128, 36);
        check(a17 != null && a17.frames().get(0).getWidth() == still17.getWidth(),
                "同一张 GIF，动图那条路与静态那条路的画面尺寸必须一致");

        // 单帧 GIF：动图那条路按设计不接，全靠静态这条路铺对
        Gif g18 = new Gif(64, 64, true);
        g18.gce(10, 1, -1); g18.frame(8, 8, 16, 16, fill(16, 16, GREEN), false, false);
        g18.trailer();
        Path pSingle = save(dir, "single-partial.gif", g18);
        check(GifCodec.read(pSingle, 128, 36) == null, "单帧 GIF 应当交给静态那条路");
        BufferedImage still18 = ImageCodec.read(pSingle);
        check(still18 != null && still18.getWidth() == 64 && still18.getHeight() == 64
                        && rgb(still18, 16, 16) == PAL[GREEN],
                "单帧局部 GIF 也应当铺成整幅逻辑屏幕");

        // 13. 别的格式不受影响
        Path png = dir.resolve("plain.png");
        ImageIO.write(solid(40, 20, 0x123456, true), "png", png.toFile());
        BufferedImage backPng = ImageCodec.read(png);
        check(backPng != null && backPng.getWidth() == 40 && rgb(backPng, 1, 1) == 0x123456, "PNG 应当照旧");

        Path jpg = dir.resolve("plain.jpg");
        ImageIO.write(solid(40, 20, 0x123456, false), "jpg", jpg.toFile());
        BufferedImage backJpg = ImageCodec.read(jpg);
        check(backJpg != null && backJpg.getWidth() == 40, "JPG 应当照旧");

        // 名字骗人的：叫 .gif 其实是 PNG。认的是文件头，不是扩展名
        Path liar = dir.resolve("actually-png.gif");
        ImageIO.write(solid(24, 24, 0xFF00FF, true), "png", liar.toFile());
        BufferedImage backLiar = ImageCodec.read(liar);
        check(backLiar != null && backLiar.getWidth() == 24, "扩展名是 .gif 的 PNG 也应当读得出来");

        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 项：");
            for (String f : failures) System.out.println("  × " + f);
            System.exit(1);
        }
    }

    //  辅助

    static int rgb(BufferedImage img, int x, int y) { return img.getRGB(x, y) & 0xFFFFFF; }

    static int alpha(BufferedImage img, int x, int y) { return img.getRGB(x, y) >>> 24; }

    static byte[] fill(int w, int h, int idx) {
        byte[] b = new byte[w * h];
        Arrays.fill(b, (byte) idx);
        return b;
    }

    static BufferedImage solid(int w, int h, int color, boolean alpha) {
        BufferedImage img = new BufferedImage(w, h,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(color));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    static Path save(Path dir, String name, Gif gif) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, gif.bytes());
        return p;
    }

    static int read(Path dir, String name, Gif gif) throws IOException {
        GifCodec.Animation a = GifCodec.read(save(dir, name, gif), 64, 36);
        return a == null ? 0 : a.frames().size();
    }

    //  手写的 GIF 编码器

    /** 位流，LSB 在前——GIF 的 LZW 就是这么摆的 */
    static final class BitOut {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        int cur, nbits;

        void write(int code, int size) {
            cur |= code << nbits;
            nbits += size;
            while (nbits >= 8) { o.write(cur & 0xFF); cur >>= 8; nbits -= 8; }
        }

        byte[] finish() {
            if (nbits > 0) o.write(cur & 0xFF);
            return o.toByteArray();
        }
    }

    /**
     * "不压缩"的 LZW：每个像素一个码，码表快涨到要加位宽时就清一次表。
     *
     * 合法（解码器认的就是这套），而且短得能一眼看完——测试要的是样本的形状对，
     * 不是文件小。
     */
    static byte[] lzw(byte[] indices) {
        BitOut bits = new BitOut();
        int clear = 256, end = 257, size = 9, next = 258;
        bits.write(clear, size);
        for (byte b : indices) {
            bits.write(b & 0xFF, size);
            if (++next >= 510) { bits.write(clear, size); next = 258; }
        }
        bits.write(end, size);
        return bits.finish();
    }

    static final class Gif {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        Gif(int w, int h, boolean globalPalette) {
            o.writeBytes("GIF89a".getBytes());
            le16(w);
            le16(h);
            o.write(globalPalette ? 0xF7 : 0x77);   // 有没有全局调色板 + 256 色
            o.write(0);   // 背景色索引
            o.write(0);   // 像素宽高比
            if (globalPalette) palette();
        }

        void le16(int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }

        void palette() {
            for (int i = 0; i < 256; i++) {
                int c = PAL[i % PAL.length];
                o.write((c >> 16) & 0xFF);
                o.write((c >> 8) & 0xFF);
                o.write(c & 0xFF);
            }
        }

        /** 循环次数，正经动图都有这一段 */
        void loopExtension() {
            o.write(0x21); o.write(0xFF); o.write(0x0B);
            o.writeBytes("NETSCAPE2.0".getBytes());
            o.write(3); o.write(1); le16(0); o.write(0);
        }

        void comment(String text) {
            o.write(0x21); o.write(0xFE);
            byte[] b = text.getBytes();
            o.write(b.length); o.writeBytes(b); o.write(0);
        }

        /** 老工具真的会写这个块 */
        void plainText() {
            o.write(0x21); o.write(0x01); o.write(12);
            for (int i = 0; i < 12; i++) o.write(0);
            o.write(2); o.write('h'); o.write('i'); o.write(0);
        }

        /** @param disposal 0=none 1=doNotDispose 2=restoreToBackgroundColor 3=restoreToPrevious */
        void gce(int delayCs, int disposal, int transparentIndex) {
            o.write(0x21); o.write(0xF9); o.write(4);
            o.write((disposal << 2) | (transparentIndex >= 0 ? 1 : 0));
            le16(delayCs);
            o.write(Math.max(transparentIndex, 0));
            o.write(0);
        }

        void frame(int x, int y, int w, int h, byte[] pixels, boolean localPalette, boolean interlace) {
            o.write(0x2C);
            le16(x); le16(y); le16(w); le16(h);
            o.write((localPalette ? 0x87 : 0) | (interlace ? 0x40 : 0));
            if (localPalette) palette();

            o.write(8);   // LZW 起始码宽
            byte[] data = lzw(interlace ? interlaced(pixels, w, h) : pixels);
            for (int i = 0; i < data.length; i += 255) {
                int n = Math.min(255, data.length - i);
                o.write(n);
                o.write(data, i, n);
            }
            o.write(0);
        }

        void trailer() { o.write(0x3B); }

        byte[] bytes() { return o.toByteArray(); }
    }

    /** 按 GIF 那四趟隔行顺序重排行 */
    static byte[] interlaced(byte[] px, int w, int h) {
        byte[] out = new byte[px.length];
        int row = 0;
        for (int[] pass : new int[][]{{0, 8}, {4, 8}, {2, 4}, {1, 2}}) {
            for (int y = pass[0]; y < h; y += pass[1]) {
                System.arraycopy(px, y * w, out, row * w, w);
                row++;
            }
        }
        return out;
    }
}
