package com.november.mcphone.core.script.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 幂等键（施工方案 §15.6）：
 *
 * <pre>SHA-256( length-prefixed(serverId, playerUUID, appId, deployRev, actionId, requestId) )</pre>
 *
 * <h2>为什么键里没有 connectionEpoch</h2>
 *
 * 两者管的是两件事，混起来会让"断线重连不重复发奖"失效：
 *
 * <ul>
 *   <li>{@code connectionEpoch} 管<b>传输层</b>："这个请求是不是这一次连接发的"。
 *       过期的一律 {@code INVALID_ARGUMENT}，不入账本、不计限流。</li>
 *   <li>账本管<b>业务层</b>："这个逻辑请求执行过没有"。</li>
 * </ul>
 *
 * 所以重连之后客户端<b>应该</b>用新 epoch 重发同一个 requestId：epoch 校验过、账本命中、
 * 拿回上次的结果。把 epoch 放进键里，重连后同一个 requestId 就成了一个新键 —— 再执行一次。
 *
 * <h2>换 requestId 绕不过业务守卫</h2>
 *
 * 幂等键只防"同一次点击被重复投递"。防"点十次领十份"靠 §20 的守卫计数，两者都要。
 */
public final class IdempotencyKey {

    private IdempotencyKey() {
    }

    /** 算一个键。返回 32 字节。 */
    public static byte[] of(UUID serverId, UUID player, String appId,
                            String deployRev, String actionId, long requestId) {
        MessageDigest md = sha256();
        put(md, serverId);
        put(md, player);
        put(md, appId);
        put(md, deployRev);
        put(md, actionId);
        put(md, Long.toString(requestId));
        return md.digest();
    }

    /** 参数摘要。§15.6 的"参数摘要相同"比的就是它。 */
    public static byte[] digestOf(byte[] params) {
        MessageDigest md = sha256();
        md.update(params == null ? new byte[0] : params);
        return md.digest();
    }

    /** 十六进制，只用于日志与账本的键。 */
    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    private static void put(MessageDigest md, UUID u) {
        put(md, u == null ? "" : u.toString());
    }

    private static void put(MessageDigest md, String s) {
        if (s == null) {
            putLength(md, -1);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        putLength(md, bytes.length);
        md.update(bytes);
    }

    /** 固定四字节大端长度；-1 专门表示 null，所以 null 与空串也不会碰撞。 */
    private static void putLength(MessageDigest md, int length) {
        md.update((byte) (length >>> 24));
        md.update((byte) (length >>> 16));
        md.update((byte) (length >>> 8));
        md.update((byte) length);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JRE 必须提供的；到不了这里
            throw new IllegalStateException("这个 JRE 没有 SHA-256", e);
        }
    }
}
