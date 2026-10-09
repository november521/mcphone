package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 升级、重复旧记录、个人可见性、重载与摘要的回归；不启动游戏或替换真实存档。 */
public final class ChatDeletionDataTest {
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    public static void main(String[] args) {
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        ConversationKey key = ConversationKey.of(a, b);
        var old = new ChatMessage(ChatMessageIdentity.MISSING_ID, a, 100, new TextBody("重复"));
        var legacy = List.of(old, old, new ChatMessage(ChatMessageIdentity.MISSING_ID, b, 101, new TextBody("最后")));
        var first = ChatMessageIdentity.normalize(key, legacy);
        var again = ChatMessageIdentity.normalize(key, legacy);
        check(first.changed(), "旧记录标为已迁移");
        check(first.messages().equals(again.messages()), "旧存档重复装载 ID 稳定");
        check(!first.messages().get(0).id().equals(first.messages().get(1).id()), "同时间同正文的旧消息仍有独立 ID");
        check(first.messages().stream().noneMatch(m -> m.id().equals(ChatMessageIdentity.MISSING_ID)), "迁移没有空 ID");
        check(!ChatMessageIdentity.normalize(key, first.messages()).changed(), "现行 ID 不重复迁移");
        var duplicateId = new ChatMessage(first.messages().get(0).id(), b, 101, new TextBody("另一条"));
        var normalized = ChatMessageIdentity.normalize(key, List.of(first.messages().get(0), duplicateId));
        check(!normalized.messages().get(0).id().equals(normalized.messages().get(1).id()), "损坏的重复 ID 不导致两条消息一起删除");
        var codec = Codec.unboundedMap(Codec.STRING, ChatMessage.CODEC.listOf());
        var tag = new CompoundTag();
        tag.put("conversations", codec.encodeStart(NbtOps.INSTANCE, Map.of(key.toStorageKey(), legacy)).result().orElseThrow());
        ChatData chat = ChatData.load(tag);
        check(chat.isDirty(), "旧存档装载后安排保存迁移 ID");
        var messages = chat.getMessages(a, b);
        check(messages.equals(first.messages()), "实际存储层使用迁移规则");
        var saved = chat.write(new CompoundTag());
        var reloaded = ChatData.load(saved);
        check(reloaded.getMessages(a, b).equals(messages), "保存后重载 ID 保持不变");
        check(!reloaded.isDirty(), "已迁移的存档不反复标脏");
        var deletions = new ChatDeletionData();
        UUID deletedId = messages.get(2).id();
        check(deletions.hide(a, b, deletedId), "本人可以标记收到的消息");
        check(!deletions.hide(a, b, deletedId), "重复删除幂等");
        check(deletions.visible(a, b, messages).size() == 2, "本人看不到删除消息");
        check(deletions.visible(b, a, messages).size() == 3, "对方仍看到完整记录");
        check(chat.getMessages(a, b).size() == 3, "共享原文不被删除");
        var disk = deletions.write(new CompoundTag());
        var restored = ChatDeletionData.load(disk);
        check(restored.visible(a, b, messages).size() == 2, "重载后删除标记仍生效");
        check(restored.visible(b, a, messages).size() == 3, "重载后仍隔离双方");
        var visibleTail = ChatData.tail(restored.visible(a, b, messages), b, 0);
        check(visibleTail.last().id().equals(messages.get(1).id()), "删除尾消息后摘要回退到可见上一条");
        check(visibleTail.unread() == 0, "删除对方未读后不留幽灵角标");
        check(ChatData.tail(messages, b, 0).unread() == 1, "原记录仍保留对方消息");
        restored.hide(a, b, messages.get(0).id()); restored.hide(a, b, messages.get(1).id());
        check(ChatData.tail(restored.visible(a, b, messages), b, 0).last() == null, "全部删除后会话摘要为空");
        restored.retain(a, b, Set.of(messages.get(0).id()));
        check(ChatDeletionData.load(restored.write(new CompoundTag())).visible(a, b, messages).size() == 2,
                "淘汰的旧标记清理，只保留仍在历史中的消息");
        var visibility = new ChatMessageVisibility();
        visibility.hide(a, b, messages.get(0).id());
        try { visibility.snapshot().clear(); throw new AssertionError("快照应不可变"); }
        catch (UnsupportedOperationException expected) { checks++; }
        var brokenTag = new CompoundTag(); brokenTag.putString("hidden", "损坏原文");
        var broken = ChatDeletionData.load(brokenTag);
        check(!broken.isAvailable(), "损坏数据返回锁定实例，防止 SavedData 重建空记录");
        check(broken.visible(a, b, messages).isEmpty(), "损坏标记不暴露可能已经删除的消息");
        check(!broken.hide(a, b, deletedId), "损坏数据拒绝新的删除写入");
        broken.retain(a, b, Set.of());
        check(broken.write(new CompoundTag()).equals(brokenTag) && !broken.isDirty(), "损坏原文保留且不安排覆盖保存");
        UUID outsider = new UUID(0, 3);
        try { ChatMessageVisibility.restore(Map.of(outsider + ";" + key.toStorageKey(), List.of(deletedId)));
            throw new AssertionError("第三方所有者应拒绝"); }
        catch (IllegalArgumentException expected) { checks++; }
        var snapshot = chat.getMessages(a, b);
        for (int i = 0; i < 105; i++) chat.addMessage(a, b, ChatMessage.text(a, "追加", 200 + i));
        check(chat.getMessages(a, b).size() == 100 && snapshot.size() == 3, "容量与不可变快照规则不回归");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
