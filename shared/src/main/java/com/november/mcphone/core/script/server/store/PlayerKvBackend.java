package com.november.mcphone.core.script.server.store;

import com.november.mcphone.core.PhonePlayerData;
import com.november.mcphone.core.script.engine.HostError;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.server.economy.CurrencyGateway;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.function.Supplier;

/** 每次调用绑定到服务端确定的玩家；玩家附件仅在主线程读写。摘要变化不改变数据命名空间。 */
public final class PlayerKvBackend implements KvBackend {
    private final MinecraftServer server;
    private final CurrencyGateway gateway;
    private final UUID player, serverId;
    private final String app;
    private final Map<String, ArrayDeque<Long>> writeTimes;
    private final DurablePlayerStore durable;
    private Runnable authorization=()->{};
    public PlayerKvBackend authorization(Runnable value){authorization=Objects.requireNonNull(value);return this;}
    private java.util.function.Supplier<com.november.mcphone.core.script.server.QuotaConfig> quotas=()->com.november.mcphone.core.script.server.QuotaConfig.DEFAULT;
    public PlayerKvBackend quotas(java.util.function.Supplier<com.november.mcphone.core.script.server.QuotaConfig> config){quotas=config;return this;}
    public PlayerKvBackend(MinecraftServer server, CurrencyGateway gateway, UUID player, UUID serverId,
                           String app, Map<String, ArrayDeque<Long>> writeTimes) {
        this(server,gateway,player,serverId,app,writeTimes,null);
    }
    public PlayerKvBackend(MinecraftServer server, CurrencyGateway gateway, UUID player, UUID serverId,
                           String app, Map<String, ArrayDeque<Long>> writeTimes, DurablePlayerStore durable) {
        this.server = server; this.gateway = gateway; this.player = player; this.serverId = serverId;
        this.app = app; this.writeTimes = writeTimes;
        this.durable = durable;
    }
    private String namespace(String id) {
        if (!app.equals(id)) throw HostError.invalid("存储 App 不匹配");
        // Deployment.deploymentId 是带摘要的显示名；数据采用本服稳定的逻辑部署 id。
        return ScriptKv.namespace(serverId.toString(), "app:" + app, app, 1);
    }
    private static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.-]{1,64}")) throw HostError.invalid("存储键必须为 1–64 位字母、数字、点、横线或下划线");
    }
    private PhonePlayerData data() {
        var p = server.getPlayerList().getPlayer(player);
        if (p == null) throw HostError.denied(ScriptErrorCode.UNAVAILABLE, "mcphone.script.intent_unavailable", "玩家已离线");
        return PhonePlayerData.of(p);
    }
    private <T> T call(Supplier<T> action) {
        try { return gateway.call(()->{authorization.run();return action.get();}); }
        catch (HostError | StoreQuota.QuotaExceeded known) { throw known; }
        catch (RuntimeException unavailable) { throw HostError.denied(ScriptErrorCode.UNAVAILABLE, "mcphone.script.server_busy", "存储此刻不可用"); }
    }
    private void rate() {
        long now = System.nanoTime();
        writeTimes.entrySet().removeIf(e -> e.getValue().isEmpty() || now-e.getValue().peekLast() >= 1_000_000_000L);
        var q = writeTimes.computeIfAbsent(player + "|" + app, ignored -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= 1_000_000_000L) q.removeFirst();
        if (q.size() >= StoreQuota.SHARED_WRITES_PER_SEC) throw new StoreQuota.QuotaExceeded("写入超过每秒 10 次");
        q.addLast(now);
    }
    @Override public String getString(String appId, String key) {
        key(key); String ns = namespace(appId);
        return call(() -> current(data()).get(ScriptKv.fullKey(ns, key)));
    }
    @Override public void setString(String appId, String key, String value) {
        key(key); String ns = namespace(appId);
        call(() -> { rate(); var d = data(); var q=quotas.get();save(d,current(d).with(ns, key, value,q.get("kv.per_player_app"),(int)q.get("kv.keys"))); return null; });
    }
    @Override public void remove(String appId, String key) {
        key(key); String ns = namespace(appId);
        call(() -> { rate(); var d = data(); save(d,current(d).without(ns, key)); return null; });
    }
    @Override public List<String> keys(String appId) {
        String prefix = namespace(appId) + "|";
        return call(() -> current(data()).values().keySet().stream().filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length())).sorted().toList());
    }
    private ScriptKv current(PhonePlayerData data) { return durable == null ? data.scriptKv() : durable.kv(player,data.scriptKv()); }
    private void save(PhonePlayerData data,ScriptKv next) {
        if(durable!=null) durable.writeKv(player,next);
        data.setScriptKv(next);
    }
}
