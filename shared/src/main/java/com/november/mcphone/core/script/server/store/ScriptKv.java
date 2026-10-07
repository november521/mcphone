package com.november.mcphone.core.script.server.store;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * 脚本 App 的 {@code shared} 档：服务端明文的每玩家 KV（施工方案 §17.3）。
 *
 * <p>挂在 {@code PhonePlayerData} 上（§10.4.1），<b>不另立第二个门面</b>。
 * §17.3 / §20.2 / §22.7 里引的那个旧门面 §10.4.1 早已删掉，别照着它们写
 * （详见 docs/script-app-s14-verification.md 的勘误收口一节）。
 *
 * <h2>逻辑键</h2>
 *
 * <pre>serverId | deploymentId | appId | schemaVersion | playerUUID | key</pre>
 *
 * 前五段由 {@link #namespace} 拼成一个前缀，玩家 UUID 由"挂在谁身上"隐含。
 *
 * <p><b>不要用 packageDigest 当命名空间</b>（§17.3）：作者改一行界面摘要就变了，
 * 玩家进度当场重置。用 {@code appId + deploymentId}。
 *
 * <h2>与守卫分家</h2>
 *
 * {@code once} / 领取次数一律走 {@link ScriptGuards}，<b>不放这里</b>。
 * 放一起脚本就能把"只能领一次"清零（§19.2、§20.2）。
 */
public record ScriptKv(Map<String, String> values) {

    public static final ScriptKv DEFAULT = new ScriptKv(Map.of());

    public static final Codec<ScriptKv> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.unboundedMap(Codec.STRING, Codec.STRING)
                            .fieldOf("values").forGetter(ScriptKv::values)
            ).apply(instance, ScriptKv::new)
    );

    public ScriptKv {
        values = Map.copyOf(values);
    }

    /** 逻辑键的前五段（§17.3）。玩家那一段由"挂在谁身上"隐含。 */
    public static String namespace(String serverId, String deploymentId, String appId, int schemaVersion) {
        return serverId + "|" + deploymentId + "|" + appId + "|" + schemaVersion;
    }

    /** 拼一个完整的键。 */
    public static String fullKey(String namespace, String key) {
        return namespace + "|" + key;
    }

    public String get(String fullKey) {
        return values.get(fullKey);
    }

    /** 这个命名空间下有几个 key。配额按命名空间算，不是按整张表。 */
    public int keyCount(String namespace) {
        String prefix = namespace + "|";
        int n = 0;
        for (String k : values.keySet()) if (k.startsWith(prefix)) n++;
        return n;
    }

    /** 这个命名空间下占了多少字节。 */
    public long bytes(String namespace) {
        String prefix = namespace + "|";
        long n = 0;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                n += e.getKey().length() + e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        return n;
    }

    /** 写一个值。<b>超配额抛 {@link StoreQuota.QuotaExceeded}，不静默截断。</b> */
    public ScriptKv with(String namespace, String key, String value) {
        return with(namespace,key,value,StoreQuota.SHARED_PER_PLAYER_APP,StoreQuota.SHARED_KEYS);
    }
    public ScriptKv with(String namespace,String key,String value,long maximumBytes,int maximumKeys) {
        String full = fullKey(namespace, key);
        StoreQuota.checkValue(value, StoreQuota.SHARED_PER_VALUE, "shared");
        long previous = values.containsKey(full) ? full.length()
                + values.get(full).getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0;
        long before=bytes(namespace),after=before-previous+full.length()+value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(!values.containsKey(full)&&keyCount(namespace)>=maximumKeys||after>maximumBytes&&after>before)
            throw new StoreQuota.QuotaExceeded("此 App 的存储配额已满，先移除一些键");
        Map<String, String> next = new HashMap<>(values);
        next.put(full, value);
        return new ScriptKv(next);
    }

    public ScriptKv without(String namespace, String key) {
        String full = fullKey(namespace, key);
        if (!values.containsKey(full)) return this;
        Map<String, String> next = new HashMap<>(values);
        next.remove(full);
        return new ScriptKv(next);
    }
}
