package com.november.mcphone.core.script.pkg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端摘要的谓词（定向对抗 Q8）：包内<b>除 {@code server.js} 与 {@code server/**} 之外</b>的全部条目。
 *
 * <p><b>客户端能加载什么，就必须与这个集合一致</b>：不在摘要集合里的条目一律不加载，否则把
 * {@code evil.js} 改名成 {@code server/evil.js} 就能"改了前端而摘要不变"。
 *
 * <p>服务端（{@code ServerPackageScanner}）与客户端（前端摘要回显、每次 {@code ScriptRpc}
 * 里带的那一格）都走这一份 —— 两份实现迟早对不上，而这里对不上的表现是"诚实的玩家
 * 被误报改了包"。
 */
public final class FrontendDigest {

    /** 后端入口：它属于服务端那一半，永不下发（§11.3）。 */
    public static final String SERVER_ENTRY = "server.js";
    /** 后端模块目录前缀。 */
    public static final String SERVER_DIR = "server/";

    private FrontendDigest() {
    }

    /** 这条路径算不算后端那一半。 */
    public static boolean isServerSide(String path) {
        return path.equals(SERVER_ENTRY) || path.startsWith(SERVER_DIR);
    }

    /**
     * 对整份条目表算前端摘要。{@code entries} 的 key 是规范化路径，value 是内容。
     * <b>{@code META/} 不在 {@link AppPackage#entries()} 里</b>，因此天然不进摘要（那是信任轴）。
     */
    public static String of(Map<String, byte[]> entries) {
        Map<String, byte[]> front = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (isServerSide(e.getKey())) continue;
            front.put(e.getKey(), e.getValue());
        }
        return PackageDigest.of(front);
    }

    /** 一个已读出来的包的前端摘要；{@code pkg} 为 null（单文件 .vue）时返回 null。 */
    public static String of(AppPackage pkg) {
        return pkg == null ? null : of(pkg.entries());
    }
}
