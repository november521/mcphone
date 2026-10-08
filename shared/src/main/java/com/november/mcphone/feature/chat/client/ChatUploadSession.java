package com.november.mcphone.feature.chat.client;

/** 上传任务归属：只允许当前任务回到启动时的连接，取消后旧后台结果不得继续发包或结束新任务。 */
final class ChatUploadSession {
    record Ticket(Object connection) {}
    private Ticket active;

    Ticket start(Object connection) {
        if (connection == null) throw new IllegalArgumentException("上传需要有效连接");
        active = new Ticket(connection);
        return active;
    }

    boolean accepts(Ticket ticket, Object currentConnection) {
        return ticket != null && ticket == active && currentConnection != null
                && ticket.connection() == currentConnection;
    }

    void cancel() { active = null; }
}
