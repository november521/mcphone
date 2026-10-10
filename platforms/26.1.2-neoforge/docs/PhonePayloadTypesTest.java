package com.november.mcphone.platform;

import com.november.mcphone.feature.chat.net.SyncMessagesPacket;
import com.november.mcphone.feature.notes.net.RequestNoteListPacket;
import com.november.mcphone.feature.store.net.RequestPurchasedAppsPacket;

/** 验证实际业务消息仍使用原来的协议 ID，而不是被原版工厂改成 minecraft 命名空间。 */
public final class PhonePayloadTypesTest {
    public static void main(String[] args) {
        check(SyncMessagesPacket.TYPE.id().toString().equals("mcphone:sync_messages_v2"));
        check(RequestNoteListPacket.TYPE.id().toString().equals("mcphone:request_note_list"));
        check(RequestPurchasedAppsPacket.TYPE.id().toString().equals("mcphone:request_purchased_apps"));
        System.out.println("全部通过：3 条实际消息标识断言");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("消息协议命名空间或路径被修改");
    }
}
