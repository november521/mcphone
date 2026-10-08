package com.november.mcphone.feature.chat.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 可见图片请求的纯调度器；贴图缓存可以跨会话复用，请求集合只能属于当前帧的对端。 */
final class ChatImageRequests {
    static final long RETRY_AFTER_MS = 6000L;
    static final long REQUEST_INTERVAL_MS = 600L;
    private UUID framePeer;
    private final LinkedHashSet<UUID> visible = new LinkedHashSet<>();
    private long lastRequestMs;

    void beginFrame(UUID peer) {
        framePeer = peer;
        visible.clear();
    }

    void visible(UUID image) {
        if (framePeer != null && image != null) visible.add(image);
    }

    /** lastAttempt 返回 null 表示无需下载，0 表示尚未请求；只对可见 ID 查询缓存。 */
    List<UUID> batch(UUID peer, long now, int limit, Function<UUID, Long> lastAttempt) {
        if (peer == null || !peer.equals(framePeer) || limit <= 0
                || now - lastRequestMs < REQUEST_INTERVAL_MS) return List.of();
        var out = new ArrayList<UUID>();
        for (UUID image : visible) {
            Long attempted = lastAttempt.apply(image);
            if (attempted == null || (attempted != 0 && now - attempted < RETRY_AFTER_MS)) continue;
            out.add(image);
            if (out.size() >= limit) break;
        }
        if (!out.isEmpty()) lastRequestMs = now;
        return List.copyOf(out);
    }

    void clear() {
        beginFrame(null);
        lastRequestMs = 0;
    }
}
