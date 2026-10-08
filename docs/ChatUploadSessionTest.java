package com.november.mcphone.feature.chat.client;

/** 不启动游戏，模拟旧压缩结果在取消、超时、重连或新任务开始之后到达。 */
public final class ChatUploadSessionTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var session = new ChatUploadSession();
        Object firstConnection = new Object(), secondConnection = new Object();
        var old = session.start(firstConnection);
        check(session.accepts(old, firstConnection), "当前任务允许上传");
        check(!session.accepts(old, null), "断线不能上传");
        check(!session.accepts(old, secondConnection), "旧连接任务不能发到新服务器");
        session.cancel();
        check(!session.accepts(old, firstConnection), "取消或超时后旧结果不能发包");
        var next = session.start(firstConnection);
        check(!session.accepts(old, firstConnection), "同一连接上开始新任务也不能复活旧任务");
        check(session.accepts(next, firstConnection), "丢弃旧结果不影响新任务");
        var reconnect = session.start(secondConnection);
        check(!session.accepts(next, secondConnection), "重连后旧结果不能结束新上传");
        check(session.accepts(reconnect, secondConnection), "新连接正常上传");
        check(!session.accepts(new ChatUploadSession.Ticket(secondConnection), secondConnection), "相同连接的伪造票据无效");
        session.cancel();
        session.cancel();
        check(!session.accepts(reconnect, secondConnection), "重复清理安全");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
