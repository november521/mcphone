package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.feature.chat.ChatImage;
import com.november.mcphone.feature.chat.ChatImageStore;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 发图那条路的断言测试：压缩的结果、透明通道、以及"同一张只压一次"的缓存。
 * 要 Minecraft 的类路径（ImageCodec 引了 NativeImage）。跑法（要挂 Forge 的编译类路径）：见 docs/PORTING.md 的"复现这套筛法"一节，
 * 把 out 目录和本文件一起编了即可。
 *
 * 守着的几件事：
 *   1. 表情的透明底不能变成黑底——玩家在表情页看到的与发出去的必须是同一张；
 *   2. 截图不能白留一个 alpha 通道，这条路上每个字节都要过网络；
 *   3. 压不进上限的自动降档，最后一定 ≤ MAX_BYTES、长边 ≤ MAX_SIDE；
 *   4. 同一个文件第二次发直接拿缓存，文件被换掉之后缓存要失效；
 *   5. 动图拆帧之后，第几帧就摆在第几格——发件人怎么摆，收件人就怎么取，差一列满屏错位；
 *   6. 压出来的东西服务端收得下（ChatImageStore.looksLikePng）——两边各有一套上限，对不上就发不出去。
 *
 * 测不了的：所有会写日志的失败路径。MCphone.LOGGER 一碰就要初始化模组主类，而那要 FML。
 */
public class ImageEncodeTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mcphone-image-test");

        Path sticker = dir.resolve("sticker.png");
        write(sticker, transparentSticker(512));

        Path shot = dir.resolve("screenshot.png");
        write(shot, screenshot(1920, 1080));

        Path noisy = dir.resolve("noisy.png");
        write(noisy, noise(2048));

        //  1. 透明底压完还得是透明的
        ImageCodec.Encoded encodedSticker = encodeWithinLimit(sticker);
        check(encodedSticker != null, "透明底的表情应当发得出去");
        if (encodedSticker != null) {
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(encodedSticker.png()));
            check((back.getRGB(0, 0) >>> 24) == 0, "表情四角的透明像素压完仍应透明（曾经变成纯黑）");
            check(back.getColorModel().hasAlpha(), "有透明像素的图必须带 alpha 通道");
            check((back.getRGB(back.getWidth() / 2, back.getHeight() / 2) >>> 24) == 0xFF,
                    "图案本身仍应是不透明的");
        }

        //  2. 截图不该白留一个通道
        ImageCodec.Encoded encodedShot = encodeWithinLimit(shot);
        check(encodedShot != null, "截图应当发得出去");
        if (encodedShot != null) {
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(encodedShot.png()));
            check(!back.getColorModel().hasAlpha(), "没有透明像素的图不该带 alpha 通道");
        }

        //  3. 上限：降档之后一定装得进去
        for (Path p : List.of(sticker, shot, noisy)) {
            ImageCodec.Encoded e = encodeWithinLimit(p);
            check(e != null, p.getFileName() + " 应当压得进上限");
            if (e != null) {
                check(e.png().length <= ChatImage.maxBytes(),
                        p.getFileName() + " 压完 " + e.png().length + " 字节，超过上限 " + ChatImage.maxBytes());
                check(Math.max(e.width(), e.height()) <= ChatImage.MAX_SIDE,
                        p.getFileName() + " 长边 " + Math.max(e.width(), e.height()) + " 超过 " + ChatImage.MAX_SIDE);
                check(e.width() > 0 && e.height() > 0, p.getFileName() + " 尺寸不该是 0");
                // 客户端压出来的，服务端必须认——两边各有一套上限，对不上就是发不出去
                check(ChatImageStore.looksLikePng(e.png()),
                        p.getFileName() + " 服务端不认这张图（looksLikePng 退回）");
            }
        }

        //  4. 同一个文件第二次直接拿缓存；文件换掉之后不能再拿旧的
        ImageCodec.Encoded first = encodeWithinLimit(noisy);
        ImageCodec.Encoded second = encodeWithinLimit(noisy);
        check(first == second, "同一个文件压第二次应当命中缓存，拿到同一份字节");

        write(noisy, transparentSticker(256));          // 同名换内容：大小与改动时间都变了
        ImageCodec.Encoded third = encodeWithinLimit(noisy);
        check(third != first, "文件被换掉之后缓存必须失效");
        if (third != null) {
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(third.png()));
            check((back.getRGB(0, 0) >>> 24) == 0, "换上去的那张是透明底，压完应当还是透明的");
        }

        //  6. 动图：拆帧、拼雪碧图、每一格都摆在收件人算得出来的位置上

        int gifFrames = 9;
        Path gif = dir.resolve("anim.gif");
        writeGif(gif, 64, gifFrames, 80);

        ImageCodec.Encoded anim = encodeWithinLimit(gif);
        check(anim != null, "动图应当发得出去");
        if (anim != null) {
            check(anim.frames() == gifFrames, "帧数应当是 " + gifFrames + "，实际 " + anim.frames());
            check(anim.frameMs() == 80, "每帧延迟应当是 80ms，实际 " + anim.frameMs());
            check(anim.png().length <= ChatImage.maxBytes(), "雪碧图不能超过上限");
            // 这一条守的是"客户端拼的雪碧图，服务端收不收"：它按整张 PNG 的尺寸判，
            // 而雪碧图比一帧大好几倍——按一帧的上限判的话，动图会被当成坏图退回
            check(ChatImageStore.looksLikePng(anim.png()),
                    "服务端必须认这张雪碧图（looksLikePng 退回了）");

            BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(anim.png()));
            int cols = ChatImage.cols(anim.frames());
            int rows = ChatImage.rows(anim.frames());
            check(Math.max(sheet.getWidth(), sheet.getHeight()) <= ChatImage.SHEET_MAX_SIDE,
                    "雪碧图长边不能超过 " + ChatImage.SHEET_MAX_SIDE);
            check(sheet.getWidth() == cols * anim.width(),
                    "雪碧图宽应当正好是 列数 × 帧宽（收件人就是这么反算的）");
            check(sheet.getHeight() == rows * anim.height(),
                    "雪碧图高应当正好是 行数 × 帧高");

            // 这一条是整件事的关键：发件人怎么摆，收件人就怎么取，差一列就是满屏错位
            for (int i = 0; i < anim.frames(); i++) {
                int px = (i % cols) * anim.width() + anim.width() / 2;
                int py = (i / cols) * anim.height() + anim.height() / 2;
                check(near(sheet.getRGB(px, py), frameColor(i)),
                        "第 " + i + " 格里应当是第 " + i + " 帧的颜色");
            }
            // 四角是透明的：GIF 的透明底要一路留到雪碧图上
            check((sheet.getRGB(0, 0) >>> 24) == 0, "动图的透明底应当留到雪碧图上");

            // 存进相册的是第一帧，不是整张九宫格
            byte[] cell = ImageCodec.cropCell(anim.png(), cols, rows);
            check(cell != null, "应当裁得出第一帧");
            if (cell != null) {
                BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(cell));
                check(cropped.getWidth() == anim.width() && cropped.getHeight() == anim.height(),
                        "裁出来的应当正好是一帧的大小");
                check(near(cropped.getRGB(cropped.getWidth() / 2, cropped.getHeight() / 2),
                                frameColor(0)),
                        "裁出来的应当是第一帧");
            }
        }

        //  6b. 雪碧图【比一帧大好几倍】的那种：服务端按整张 PNG 的尺寸判，必须按雪碧图的
        //      上限判而不是按一帧的。用一张够大的动图逼出这个情况——上面那张 64px 的太小，
        //      拼出来还没超过一帧的上限，根本走不到这条路上

        Path bigGif = dir.resolve("big.gif");
        writeGif(bigGif, 240, 16, 80);
        ImageCodec.Encoded big = encodeWithinLimit(bigGif);
        check(big != null, "大一点的动图也该发得出去");
        if (big != null) {
            BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(big.png()));
            int longSide = Math.max(sheet.getWidth(), sheet.getHeight());
            check(longSide > ChatImage.MAX_SIDE,
                    "这一条要的就是雪碧图超过一帧的上限，实际长边 " + longSide
                            + "，一帧上限 " + ChatImage.MAX_SIDE + "——不超就没测到东西");
            check(longSide <= ChatImage.SHEET_MAX_SIDE,
                    "雪碧图长边 " + longSide + " 超过 " + ChatImage.SHEET_MAX_SIDE);
            check(ChatImageStore.looksLikePng(big.png()),
                    "服务端必须认这张比一帧大好几倍的雪碧图");
        }

        //  7. 帧太多的自动抽稀，延迟跟着乘上去——不然会播成快进

        Path longGif = dir.resolve("long.gif");
        writeGif(longGif, 64, 100, 60);
        ImageCodec.Encoded thinned = encodeWithinLimit(longGif);
        check(thinned != null, "很长的动图也该发得出去");
        if (thinned != null) {
            check(thinned.frames() <= ChatImage.MAX_FRAMES,
                    "帧数应当被抽到 " + ChatImage.MAX_FRAMES + " 以内，实际 " + thinned.frames());
            check(thinned.frames() >= ChatImage.MIN_FRAMES, "也不能抽到不成动画");
            check(thinned.frameMs() > 60 && thinned.frameMs() % 60 == 0,
                    "抽了几分之一，每帧就该停几倍的时间，实际 " + thinned.frameMs());
        }

        //  8. 本来就短的动图照发，不能被"抽稀底线"误伤成静态图

        Path shortGif = dir.resolve("short.gif");
        writeGif(shortGif, 64, 3, 120);
        ImageCodec.Encoded shortAnim = encodeWithinLimit(shortGif);
        check(shortAnim != null && shortAnim.frames() == 3,
                "只有三帧的动图应当原样发成动图，实际 "
                        + (shortAnim == null ? "发不出去" : shortAnim.frames() + " 帧"));

        //  9. 单帧 GIF 走静态图那条路

        Path oneFrame = dir.resolve("one.gif");
        writeGif(oneFrame, 64, 1, 100);
        ImageCodec.Encoded still = encodeWithinLimit(oneFrame);
        check(still != null && still.frames() == 1, "单帧 GIF 应当当静态图处理");

        // 解不开的文件（拖错了、下了一半）这里测不了：那条路要写一行 warn，而 MCphone.LOGGER
        // 一碰就会把整个模组主类初始化起来，那要 FML 已经装好。留给游戏里跑。

        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 项：");
            failures.forEach(f -> System.out.println("  ✗ " + f));
            System.exit(1);
        }
    }

    /**
     * ChatImageSender.encodeWithinLimit 是私有的：它是实现细节，但正是要守的那一段。
     *
     * 它返回的 Attempt 也是私有的（成了带 PNG，没成带要跟玩家说的那句话），所以这里
     * 反射两层：先拿到 Attempt，再问它 encoded()。
     */
    static ImageCodec.Encoded encodeWithinLimit(Path photo) throws Exception {
        Method m = ChatImageSender.class.getDeclaredMethod("encodeWithinLimit", Path.class, int.class);
        m.setAccessible(true);
        Object attempt = m.invoke(null, photo, ChatImage.maxBytes());

        Method encoded = attempt.getClass().getDeclaredMethod("encoded");
        encoded.setAccessible(true);
        return (ImageCodec.Encoded) encoded.invoke(attempt);
    }

    static void write(Path path, BufferedImage image) throws IOException {
        ImageIO.write(image, "png", path.toFile());
    }

    /** 第 i 帧该是什么颜色。挑得远一点，GIF 量化之后仍分得开 */
    static int frameColor(int i) {
        int[] palette = {0xE43B3B, 0x3BE43B, 0x3B3BE4, 0xE4E43B, 0xE43BE4,
                         0x3BE4E4, 0xE49A3B, 0x9A3BE4, 0x3B9AE4, 0xFFFFFF};
        return palette[i % palette.length];
    }

    /** 量化会让颜色偏一点，比大概齐就行 */
    static boolean near(int actual, int expected) {
        if ((actual >>> 24) != 0xFF) return false;
        for (int shift : new int[]{16, 8, 0}) {
            if (Math.abs(((actual >> shift) & 0xFF) - ((expected >> shift) & 0xFF)) > 24) return false;
        }
        return true;
    }

    /** 造一张动图：每帧一块纯色方块，四周留透明边——颜色用来验"第几帧摆在第几格" */
    static void writeGif(Path path, int size, int frames, int delayMs) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);

            for (int i = 0; i < frames; i++) {
                BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.setComposite(AlphaComposite.Src);
                g.setColor(new Color(frameColor(i)));
                g.fillRect(size / 8, size / 8, size * 3 / 4, size * 3 / 4);
                g.dispose();

                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata meta = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(img), param);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode gce = childNode(root, "GraphicControlExtension");
                gce.setAttribute("delayTime", String.valueOf(delayMs / 10));
                gce.setAttribute("disposalMethod", "restoreToBackgroundColor");
                gce.setAttribute("transparentColorFlag", "TRUE");
                meta.setFromTree(format, root);

                writer.writeToSequence(new IIOImage(img, null, meta), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    static IIOMetadataNode childNode(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    /** 一张透明底的表情：中间一张黄脸，四周全透明 */
    static BufferedImage transparentSticker(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xFFFFD93B, true));
        g.fillOval(n / 8, n / 8, n * 3 / 4, n * 3 / 4);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(Math.max(1f, n / 32f)));
        g.drawArc(n * 5 / 16, n * 7 / 16, n * 3 / 8, n / 4, 200, 140);
        g.dispose();
        return img;
    }

    /** 一张普通截图：天到地的渐变，没有透明像素 */
    static BufferedImage screenshot(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x87CEEB), 0, h, new Color(0x228B22)));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /** 最坏情况：满屏噪点，压出来必然超上限，逼着降档 */
    static BufferedImage noise(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(20260906L);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        }
        return img;
    }
}
