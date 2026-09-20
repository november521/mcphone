package com.november.mcphone.feature.chat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Pure regressions for bounded persisted chat read state. */
public final class ChatSecurityTest {

    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Map<UUID, Long> oversized = new HashMap<>();
        for (int i = 0; i < ChatReadState.MAX_PEERS + 50; i++) {
            oversized.put(new UUID(0, i + 1L), (long) i);
        }
        ChatReadState decoded = new ChatReadState(oversized);
        check(decoded.lastRead().size() == ChatReadState.MAX_PEERS,
                "constructor/codec boundary clamps an oversized persisted map");
        check(!decoded.lastRead().containsKey(new UUID(0, 1)),
                "oldest read entries are removed first");

        UUID newest = new UUID(1, 1);
        ChatReadState updated = decoded.withLastRead(newest, Long.MAX_VALUE);
        check(updated.lastRead().size() == ChatReadState.MAX_PEERS,
                "adding a new peer preserves the hard capacity");
        check(updated.lastRead().containsKey(newest), "new read peer is retained");
        check(decoded.lastRead().size() == ChatReadState.MAX_PEERS,
                "record remains immutable");

        System.out.println("Chat security assertions: " + checks);
    }
}
