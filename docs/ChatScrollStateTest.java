package com.november.mcphone.feature.chat.client;

/** 翻历史时保持位置、贴底跟随、窗口变大和切换会话的回归。 */
public final class ChatScrollStateTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var scroll = new ChatScrollState();
        scroll.contentChanged(300);
        scroll.viewport(100);
        check(scroll.offset() == 0, "初始贴底");
        scroll.contentChanged(350);
        scroll.viewport(100);
        check(scroll.offset() == 0, "贴底收到消息仍贴底");
        check(scroll.scroll(2) && scroll.offset() == 36, "滚轮按逻辑像素翻历史");
        int before = 100 - scroll.contentHeight() + scroll.offset();
        scroll.contentChanged(380);
        scroll.viewport(100);
        check(100 - scroll.contentHeight() + scroll.offset() == before, "翻历史时追加消息不改变视野原点");
        scroll.scroll(1000);
        check(scroll.offset() == 280, "滚到最早历史有上限");
        scroll.viewport(200);
        check(scroll.offset() == 180, "视口变大后滚动量收敛");
        scroll.latest();
        check(scroll.offset() == 0, "发送后回最新消息");
        scroll.contentChanged(40);
        scroll.viewport(100);
        check(!scroll.scroll(1), "不足一屏不滚动");
        scroll.reset();
        check(scroll.contentHeight() == 0 && scroll.offset() == 0 && !scroll.scroll(1), "切换会话不带旧状态");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
