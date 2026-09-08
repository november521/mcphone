package com.november.mcphone.core.client;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 文件选择器（{@link ImagePicker}）的断言测试 —— 守两件静默出错的判断。
 *
 * <h2>为什么这两件值得单独钉</h2>
 *
 * <b>一、收哪些后缀。</b>{@code isImageName} 决定玩家能不能在对话框里看见某个文件。
 * 它多认一个 {@code .webp}，玩家就会选中一张 {@code ImageIO} 根本读不了的图，
 * 界面上表现为"选了但什么都没发生"——没有报错，只有一个沉默的空操作。
 * 少认一个 {@code .jpeg}，玩家会觉得"我的图明明在文件夹里，怎么挑不到"。
 * 两头都不报错，所以只能靠断言钉住。
 *
 * <b>二、不许放大。</b>缩略图与预览都用 {@code fit} 收尾，而它最容易写错的一处是
 * 把"按比例缩到框内"写成"拉满框"——那样小图会被拉糊。糊了也不报错，
 * 只是看起来比原图差，玩家不会想到是这里的问题。
 *
 * 另外钉住 {@code decode} 对非图片文件返回 null 而不是抛异常：它跑在 EDT 与
 * 缩略图线程上，抛出去就是对话框卡死或图标永远转圈。
 *
 * 跑法见 docs/ 下其它 *Test.java；`./gradlew check` 会自动带上。
 */
public class ImagePickerTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    public static void main(String[] args) throws Exception {
        // ---- 收哪些后缀 ----
        check(ImagePicker.isImageName("a.png"), "小写 .png 应被接受");
        check(ImagePicker.isImageName("A.PNG"), "大写 .PNG 应被接受（大小写不敏感）");
        check(ImagePicker.isImageName("shot.jpeg"), ".jpeg 应被接受");
        check(ImagePicker.isImageName("shot.jpg"), ".jpg 应被接受");
        check(ImagePicker.isImageName("anim.gif"), ".gif 应被接受");
        check(ImagePicker.isImageName("old.bmp"), ".bmp 应被接受");
        check(ImagePicker.isImageName("我的 壁纸.png"), "带空格与中文的名字应被接受");

        // webp 是刻意不收的：ImageIO 默认读不了，收了就是"选中了却没反应"
        check(!ImagePicker.isImageName("x.webp"), ".webp 不应被接受（ImageIO 读不了）");
        check(!ImagePicker.isImageName("a.txt"), ".txt 不应被接受");
        check(!ImagePicker.isImageName("png"), "没有后缀不应被接受");
        check(!ImagePicker.isImageName("png."), "只有点没有后缀不应被接受");
        check(!ImagePicker.isImageName(null), "null 不应被接受");

        // ---- decode：尺寸与不放大 ----
        Path tmp = Files.createTempDirectory("mcphone-picker-test");

        // 大图（400×200）缩到 100×100 以内 → 100×50，保持比例
        File big = writePng(tmp.resolve("big.png"), 400, 200);
        BufferedImage scaled = ImagePicker.decode(big, 100, 100);
        check(scaled != null, "400×200 的 PNG 应能解码");
        if (scaled != null) {
            check(scaled.getWidth() == 100 && scaled.getHeight() == 50,
                    "400×200 缩到 100×100 框内应为 100×50，实际 "
                            + scaled.getWidth() + "×" + scaled.getHeight());
        }

        // 小图（20×10）不许被放大 → 仍是 20×10
        File small = writePng(tmp.resolve("small.png"), 20, 10);
        BufferedImage kept = ImagePicker.decode(small, 160, 160);
        check(kept != null, "20×10 的 PNG 应能解码");
        if (kept != null) {
            check(kept.getWidth() == 20 && kept.getHeight() == 10,
                    "小图不应被放大，实际 " + kept.getWidth() + "×" + kept.getHeight());
        }

        // 正方形图缩进正方形框 → 正好填满
        File square = writePng(tmp.resolve("square.png"), 300, 300);
        BufferedImage squared = ImagePicker.decode(square, 48, 48);
        check(squared != null && squared.getWidth() == 48 && squared.getHeight() == 48,
                "300×300 缩到 48×48 应为 48×48");

        // ---- decode：不是图片时返回 null，而不是抛异常 ----
        Path notImage = tmp.resolve("not-an-image.txt");
        Files.writeString(notImage, "这不是图片");
        check(ImagePicker.decode(notImage.toFile(), 64, 64) == null,
                "非图片文件应返回 null");

        Path missing = tmp.resolve("does-not-exist.png");
        check(ImagePicker.decode(missing.toFile(), 64, 64) == null,
                "不存在的文件应返回 null");

        // 清理
        try (var stream = Files.walk(tmp)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }

        if (!failures.isEmpty()) {
            System.out.println("FAIL " + failures.size() + " / " + checks);
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("PASS " + checks + " checks");
    }

    /** 造一张纯色 PNG。颜色不重要，这里只关心尺寸 */
    private static File writePng(Path path, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(0x33, 0x66, 0x99));
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
        return path.toFile();
    }
}
