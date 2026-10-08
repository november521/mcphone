package com.november.mcphone.core.client;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** 高分辨率皮肤元数据兼容性与默认圆角素材，不启动窗口。 */
public class ChatSurfaceTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        for (int advance : new int[]{6, 12, 18}) {
            var ink = UnreadBadge.numericInk(advance, 9, UnreadBadge.TEXT_SCALE);
            check(ink.height() < UnreadBadge.HEIGHT - 2, "小角标数字上下仍有留白");
            check(ink.width() < advance * UnreadBadge.TEXT_SCALE, "数字居中不能计入末尾字间距");
            float width = Math.max(UnreadBadge.HEIGHT, (int) Math.ceil(ink.width()) + 4);
            check((width - ink.width()) / 2 >= 2, "一位、两位和 99+ 的数字两侧都保留空间");
        }
        var old = PhoneSkin.parseSkinMetadata(JsonParser.parseString("{\"border\":3}").getAsJsonObject());
        check(old.border() == 3 && old.scale() == 1, "旧资源包边角尺寸保持不变");
        var empty = PhoneSkin.parseSkinMetadata(JsonParser.parseString("{}").getAsJsonObject());
        check(empty.border() == 0 && empty.scale() == 1, "未写元数据时整张拉伸");
        var high = PhoneSkin.parseSkinMetadata(JsonParser.parseString("{\"border\":24,\"scale\":8}").getAsJsonObject());
        check(PhoneSkin.effectiveSkinMetadata(high, true).equals(high), "PNG 与元数据同包时保留八倍精度");
        var inherited = PhoneSkin.effectiveSkinMetadata(high, false);
        check(inherited.scale() == 1 && inherited.border() == 3, "旧包只覆盖普通 PNG 时不能继承八倍缩放");
        check(PhoneSkin.effectiveSkinMetadata(old, false).equals(old), "旧 border 元数据跨包覆盖照常生效");
        for (int scale : new int[]{0, -1, 17}) {
            boolean rejected = false;
            try {
                PhoneSkin.parseSkinMetadata(JsonParser.parseString("{\"scale\":" + scale + "}").getAsJsonObject());
            } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "非法精度倍率应走元数据降级路径");
        }
        boolean rejectedFraction = false;
        try {
            PhoneSkin.parseSkinMetadata(JsonParser.parseString("{\"scale\":8.5}").getAsJsonObject());
        } catch (IllegalArgumentException e) { rejectedFraction = true; }
        check(rejectedFraction, "倍率不能静默截断小数");
        var colored = PhoneSkin.parseSkinMetadata(JsonParser.parseString("{\"border\":24,\"scale\":8,\"text_color\":\"#48283A\"}").getAsJsonObject());
        check(colored.textColor() == 0xFF48283A, "六位颜色补全不透明 alpha");
        check(PhoneSkin.effectiveSkinMetadata(colored, true).textColor().equals(colored.textColor()), "同包保留文字颜色");
        check(PhoneSkin.effectiveSkinMetadata(colored, false).textColor() == null, "旧 PNG 覆盖不能继承浅色底板的文字颜色");
        check(old.textColor() == null, "旧包没有文字颜色时保留原有回退");
        var argb = PhoneSkin.parseSkinMetadata(JsonParser.parseString("{\"text_color\":\"8048283A\"}").getAsJsonObject());
        check(argb.textColor() == 0x8048283A, "八位颜色保留 alpha");
        for (String invalid : new String[]{"#12345", "#ZZZZZZ"}) {
            boolean rejected = false;
            try { PhoneSkin.parseSkinMetadata(JsonParser.parseString("{\"text_color\":\"" + invalid + "\"}").getAsJsonObject()); }
            catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "非法文字颜色应拒绝并走资源降级");
        }
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("shared/src/main/resources"))) root = root.getParent();
        check(root != null, "找到真实默认素材");
        long rgbaBytes = 0;
        for (String name : new String[]{"bubble_self", "bubble_peer", "input_bar"}) {
            Path png = root.resolve("shared/src/main/resources/assets/mcphone/textures/chat/" + name + ".png");
            var im = ImageIO.read(png.toFile());
            var metadata = PhoneSkin.parseSkinMetadata(JsonParser.parseString(Files.readString(
                    png.resolveSibling(name + ".png.mcmeta"))).getAsJsonObject().getAsJsonObject("mcphone_skin"));
            check(metadata.scale() == 8, "默认局部贴图精度为八倍");
            check(metadata.border() / metadata.scale() == 3, "放大源图后仍只有三逻辑像素边角");
            check(im.getWidth() == (name.equals("input_bar") ? 90 : 32) * 8, "等比放大宽度");
            check(im.getHeight() == (name.equals("input_bar") ? 12 : 32) * 8, "等比放大高度");
            check((im.getRGB(0, 0) >>> 24) == 0, "圆角外侧需要完全透明");
            int centerAlpha = im.getRGB(im.getWidth() / 2, im.getHeight() / 2) >>> 24;
            check(name.equals("input_bar") ? centerAlpha >= 160 && centerAlpha <= 200 : centerAlpha == 255,
                    "输入框应透出背景，消息气泡保持不透明以保证可读性");
            if (name.equals("input_bar")) {
                check(metadata.textColor() == 0xFFFFF2F7, "偏黑底板使用浅色文字");
                int rgb = im.getRGB(im.getWidth() / 2, im.getHeight() / 2);
                check(((rgb >> 16) & 255) < 50 && ((rgb >> 8) & 255) < 50 && (rgb & 255) < 50,
                        "输入框中心保持偏黑的低亮度");
            }
            boolean antialiased = false;
            for (int y = 0; y < metadata.border(); y++) {
                for (int x = 0; x < metadata.border(); x++) {
                    int alpha = im.getRGB(x, y) >>> 24;
                    if (alpha > 0 && alpha < 255) antialiased = true;
                }
            }
            check(antialiased, "圆弧边缘需要抗锯齿覆盖");
            check(Files.exists(root.resolve("docs/assets/chat-ui/surfaces/" + name + ".svg")), "保留可编辑矢量原稿");
            rgbaBytes += im.getWidth() * (long) im.getHeight() * 4;
        }
        var badgePath = root.resolve("shared/src/main/resources/assets/mcphone/textures/phone/unread_badge.png");
        var badge = ImageIO.read(badgePath.toFile());
        var badgeMeta = PhoneSkin.parseSkinMetadata(JsonParser.parseString(Files.readString(
                badgePath.resolveSibling("unread_badge.png.mcmeta"))).getAsJsonObject().getAsJsonObject("mcphone_skin"));
        check(badge.getWidth() == 96 && badge.getHeight() == 64, "未读角标保留八倍精度");
        check(badgeMeta.scale() == 8 && badgeMeta.border() == 24, "角标边角按逻辑尺寸绘制");
        check((badge.getRGB(0, 0) >>> 24) == 0 && (badge.getRGB(48, 32) >>> 24) == 255, "小角标边缘透明，数字底板清晰");
        check(Files.exists(root.resolve("docs/assets/chat-ui/surfaces/unread_badge.svg")), "保留角标矢量原稿");
        rgbaBytes += badge.getWidth() * (long) badge.getHeight() * 4;
        check(rgbaBytes < 1024 * 1024, "默认表面的基础 RGBA 数据应小于一 MiB");
        System.out.println("全部通过：" + checks + " 条断言，默认表面 RGBA " + rgbaBytes + " 字节");
    }
}
