package com.november.mcphone.core.script.pkg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 一个校验过的 App 包：manifest + 内容条目 + 包摘要。
 *
 * <p>只能由 {@link PackageReader} 造出来 —— 拿到一个 AppPackage 就意味着 §3.2 与 §3.4
 * 的全部判据都已经跑过了。
 *
 * <p>{@code META/} 下的东西不在 {@link #paths()} 里，也不进摘要（§12.4）：摘要含签名、
 * 签名又签摘要，那是个自引用。
 */
public final class AppPackage {

    private final Manifest manifest;
    private final Map<String, byte[]> entries;
    private final byte[] signature;
    private final byte[] rotate;
    private final String digest;

    AppPackage(Manifest manifest, Map<String, byte[]> entries, byte[] signature, byte[] rotate) {
        this(manifest,entries,signature,rotate,Map.of());
    }
    /** 仅前端分发使用：补入后端叶子哈希后重建作者签过的原包摘要，不下发后端源码。 */
    AppPackage(Manifest manifest, Map<String, byte[]> entries, byte[] signature, byte[] rotate, Map<String,byte[]> proof) {
        // 连内容一起拷：只拷 map 的话，造包的人手里还攥着同一批数组，改一个字节就能让
        // entry() 与 digest() 对不上。
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) copy.put(e.getKey(), e.getValue().clone());
        this.manifest = manifest;
        this.entries = Collections.unmodifiableMap(copy);
        this.signature = signature == null ? null : signature.clone();
        this.rotate = rotate == null ? null : rotate.clone();
        var leaves = new LinkedHashMap<String,byte[]>();
        this.entries.forEach((path,body)->leaves.put(path,PackageDigest.leaf(path,body)));
        for(var leaf:proof.entrySet()) {
            if(!FrontendDigest.isServerSide(leaf.getKey()) || leaves.putIfAbsent(leaf.getKey(),leaf.getValue().clone())!=null)
                throw new IllegalArgumentException("前端证明不能省略客户端内容或覆盖已有路径");
        }
        this.digest = PackageDigest.ofLeaves(leaves);
    }

    public Manifest manifest() {
        return manifest;
    }

    /** §3.3 的包摘要，小写十六进制。 */
    public String digest() {
        return digest;
    }

    /** 全部内容路径，不含 {@code META/}。 */
    public Set<String> paths() {
        return entries.keySet();
    }

    /** 某一条的内容，没有则 null。给的是副本 —— 调用方改了不许影响摘要。 */
    public byte[] entry(String path) {
        byte[] v = entries.get(path);
        return v == null ? null : v.clone();
    }

    /** 全部条目的副本。摘要就是按这张表算的。 */
    public Map<String, byte[]> entries() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) copy.put(e.getKey(), e.getValue().clone());
        return copy;
    }

    /** 包里带没带 {@code META/sig.json}。验签是 S16 的事，这里只负责把它原样带上来。 */
    public boolean signed() {
        return signature != null;
    }

    /** {@code META/sig.json} 的原始字节，没有则 null。 */
    /** {@code META/rotate.json} 的内容，没有就是 null（§12.6）。同样不进摘要。 */
    public byte[] rotate() {
        return rotate == null ? null : rotate.clone();
    }

    public byte[] signature() {
        return signature == null ? null : signature.clone();
    }

    @Override
    public String toString() {
        return "AppPackage[" + manifest.id() + " " + manifest.version()
                + " " + entries.size() + " 条 " + digest.substring(0, 8) + "]";
    }
}
