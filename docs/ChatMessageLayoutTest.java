package com.november.mcphone.feature.chat.client;

import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ImageBody;
import net.minecraft.client.gui.Font;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;
import java.util.UUID;

/** 使用确定的字体测量替身验证消息排版，不依赖游戏窗口、GL 或实际字体文件。 */
public final class ChatMessageLayoutTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static final class TestFont extends Font {
        TestFont() { super(id -> null, false); }
        @Override public int width(FormattedCharSequence text) {
            int[] width = {0};
            text.accept((index, style, codePoint) -> { width[0] += 6; return true; });
            return width[0];
        }
        @Override public StringSplitter getSplitter() { return new StringSplitter((cp, style) -> 6); }
        @Override public List<FormattedCharSequence> split(FormattedText text, int width) {
            return getSplitter().splitLines(text, width, Style.EMPTY).stream()
                    .map(line -> FormattedCharSequence.forward(line.getString(), Style.EMPTY)).toList();
        }

    }

    public static void main(String[] args) {
        Font font = new TestFont();
        UUID self = new UUID(0, 1), peer = new UUID(0, 2), imageId = new UUID(0, 3);
        long time = 1000000L;
        var src = List.of(ChatMessage.text(peer, "你好", time),
                ChatMessage.text(self, "Hello\n世界", time + 1),
                new ChatMessage(peer, time + 2, new ImageBody(imageId, 320, 512, 4, 100)),
                ChatMessage.text(peer, "稍后", time + 300003));
        var blocks = ChatMessageLayout.build(font, src, 112, self);
        check(blocks.size() == 6, "首次及相隔五分钟插时间行，其余消息不重复插入");
        check(blocks.get(0).type() == ChatMessageLayout.BlockType.STAMP, "第一块是时间行");
        check(!blocks.get(1).self() && blocks.get(2).self(), "发送者决定头像和气泡侧边");
        check(blocks.get(2).lines().size() == 2, "显式换行保留为两行");
        var shortText = blocks.get(1);
        check(shortText.placement().contentY() + shortText.h()
                == shortText.placement().avatarY() + ChatLayout.AVATAR_SIZE, "短气泡底边对齐头像");
        check(shortText.textOrigin().x() > 3 && shortText.textOrigin().y() < 2.875f, "实际排版使用正文光学校准");
        var image = blocks.get(3);
        check(image.type() == ChatMessageLayout.BlockType.IMAGE && image.image().equals(imageId), "图片保留标识");
        check(image.h() == 56 && image.w() == 35, "竖图等比限制高度");
        check(image.frames() == 4 && image.frameMs() == 100, "动图元数据不因拆分丢失");
        check(image.lines().isEmpty() && image.textOrigin() == null, "图片不套文字块");
        var longSrc = List.of(ChatMessage.text(peer, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", time));
        var narrow = ChatMessageLayout.build(font, longSrc, 112, self).get(1);
        var wide = ChatMessageLayout.build(font, longSrc, 232, self).get(1);
        check(narrow.lines().size() > wide.lines().size(), "宽度变化改变换行");
        check(narrow.w() <= 80 && wide.w() <= 167, "换行后气泡仍在最大宽度内");
        var smallImage = ChatMessageLayout.build(font,
                List.of(new ChatMessage(peer, time, new ImageBody(imageId, 8, 4))), 112, self).get(1);
        check(smallImage.w() == 8 && smallImage.h() == 4, "小图不放大");
        check(ChatMessageLayout.build(font, List.of(), 112, self).isEmpty(), "空会话没有旧块");
        try { blocks.clear(); throw new AssertionError("排版结果必须不可变"); }
        catch (UnsupportedOperationException expected) { checks++; }
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
