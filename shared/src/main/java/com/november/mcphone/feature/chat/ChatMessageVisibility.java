package com.november.mcphone.feature.chat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 个人可见性的纯规则。原消息不变，任一方删除只修改自己的标记。 */
final class ChatMessageVisibility {
    private record Key(UUID owner, ConversationKey conversation) {}
    private final Map<Key, Set<UUID>> hidden = new HashMap<>();
    public boolean hide(UUID owner, UUID peer, UUID message) {
        return hidden.computeIfAbsent(new Key(owner, ConversationKey.of(owner, peer)), k -> new HashSet<>()).add(message);
    }
    public boolean visible(UUID owner, UUID peer, UUID message) {
        return !hidden.getOrDefault(new Key(owner, ConversationKey.of(owner, peer)), Set.of()).contains(message);
    }
    public List<ChatMessage> filter(UUID owner, UUID peer, List<ChatMessage> messages) {
        return messages.stream().filter(m -> visible(owner, peer, m.id())).toList();
    }
    public boolean retain(UUID a, UUID b, Set<UUID> existing) {
        boolean changed = false;
        ConversationKey conversation = ConversationKey.of(a, b);
        var iterator = hidden.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getKey().conversation().equals(conversation)) continue;
            changed |= entry.getValue().retainAll(existing);
            if (entry.getValue().isEmpty()) iterator.remove();
        }
        return changed;
    }
    public Map<String, List<UUID>> snapshot() {
        var result = new TreeMap<String, List<UUID>>();
        hidden.forEach((k, v) -> result.put(k.owner() + ";" + k.conversation().toStorageKey(), v.stream().sorted().toList()));
        return Map.copyOf(result);
    }
    public static ChatMessageVisibility restore(Map<String, List<UUID>> snapshot) {
        var state = new ChatMessageVisibility();
        snapshot.forEach((key, ids) -> {
            int delimiter = key.indexOf(';');
            if (delimiter < 0) throw new IllegalArgumentException("删除标记的会话键无效");
            UUID owner = UUID.fromString(key.substring(0, delimiter));
            ConversationKey conversation = ConversationKey.parse(key.substring(delimiter + 1));
            if (conversation == null || (!conversation.lo().equals(owner) && !conversation.hi().equals(owner)))
                throw new IllegalArgumentException("删除标记的所有者不属于会话");
            state.hidden.put(new Key(owner, conversation), new HashSet<>(ids));
        });
        return state;
    }
}
