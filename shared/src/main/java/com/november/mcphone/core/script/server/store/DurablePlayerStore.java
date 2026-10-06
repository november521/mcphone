package com.november.mcphone.core.script.server.store;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.server.ScriptStateData;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 玩家 KV 与保险箱的同步原子快照。附件是兼容镜像；这里成功落盘后才向脚本确认写入。 */
public final class DurablePlayerStore {
    private record State(ScriptKv kv, Map<String,SealedRecord> sealed) { }
    private final Path root;
    private final Map<UUID,State> players = new HashMap<>();
    private final Map<String,Long> byApp = new HashMap<>();
    private long total;
    private boolean locked;
    private final java.util.function.Supplier<com.november.mcphone.core.script.server.QuotaConfig> quotas;
    private final com.november.mcphone.core.script.server.StorageBudget budget;
    public DurablePlayerStore(Path root) throws java.io.IOException {
        this(root,()->com.november.mcphone.core.script.server.QuotaConfig.DEFAULT,null);
    }
    public DurablePlayerStore(Path root,java.util.function.Supplier<com.november.mcphone.core.script.server.QuotaConfig> quotas,com.november.mcphone.core.script.server.StorageBudget budget)throws java.io.IOException {
        this.quotas=quotas;this.budget=budget;
        this.root=root.toAbsolutePath().normalize();
        if(Files.exists(root)) try(var files=Files.list(root)) {
            for(Path file:files.toList()) {
                if(file.getFileName().toString().endsWith(".tmp")) continue;
                if(Files.isSymbolicLink(file) || !file.getFileName().toString().matches("[0-9a-f-]{36}\\.json") || Files.size(file)>1024*1024)
                    throw new IllegalArgumentException("玩家存储文件不规范");
                UUID player=UUID.fromString(file.getFileName().toString().substring(0,36));
                State state=decode(Files.readString(file,StandardCharsets.UTF_8));
                if(usage(state).values().stream().mapToLong(Long::longValue).sum()>1048576)throw new StoreQuota.QuotaExceeded("存档中的玩家总量超过硬上限");
                if(budget!=null)budget.restore("player:"+player,usage(state));else checkGlobal(null,state);
                install(player,state);
            }
        }
    }
    public synchronized ScriptKv kv(UUID player,ScriptKv attachment) {
        available(); State state=players.get(player);
        if(state==null) {
            state=new State(attachment,Map.of()); checkGlobal(null,state);
            if(!attachment.values().isEmpty()) commit(player,state);
        }
        return state.kv();
    }
    public synchronized void writeKv(UUID player,ScriptKv next) {
        available(); State old=players.getOrDefault(player,new State(ScriptKv.DEFAULT,Map.of()));
        commit(player,new State(next,old.sealed()));
    }
    public synchronized SealedRecord sealed(UUID player,String namespace,String key) {
        available(); return players.getOrDefault(player,new State(ScriptKv.DEFAULT,Map.of())).sealed().get(ScriptKv.fullKey(namespace,key));
    }
    public synchronized void sealed(UUID player,String namespace,String key,SealedRecord record) {
        available(); State old=players.getOrDefault(player,new State(ScriptKv.DEFAULT,Map.of()));
        String full=ScriptKv.fullKey(namespace,key); var next=new LinkedHashMap<>(old.sealed()); SealedRecord previous=next.get(full);
        if(previous!=null && record.recordVersion()<=previous.recordVersion()) {
            if(record.recordVersion()==previous.recordVersion() && encodeRecord(record).equals(encodeRecord(previous))) return;
            throw new IllegalArgumentException("保险箱版本倒退或同版本换内容");
        }
        next.put(full,record); int count=0; long bytes=0;
        for(var e:next.entrySet()) if(e.getKey().startsWith(namespace+"|")) { count++; bytes+=size(e.getValue()); }
        int previousCount=0;long previousBytes=0;for(var e:old.sealed().entrySet())if(e.getKey().startsWith(namespace+"|")){previousCount++;previousBytes+=size(e.getValue());}
        var q=quotas.get();if(count>q.get("sealed.keys")&&count>previousCount||bytes>q.get("sealed.per_player_app")&&bytes>previousBytes)throw new StoreQuota.QuotaExceeded("保险箱配额已满");
        commit(player,new State(old.kv(),Map.copyOf(next)));
    }
    public synchronized long playerBytes(UUID player) { State s=players.get(player); return s==null?0:usage(s).values().stream().mapToLong(Long::longValue).sum(); }
    public synchronized long appBytes(String app) { return byApp.getOrDefault(app,0L); }
    public record AppUsage(int keys,long kvBytes,long sealedBytes){}
    public synchronized AppUsage appUsage(UUID player,String appId){available();State state=players.getOrDefault(player,new State(ScriptKv.DEFAULT,Map.of()));long kv=0,sealed=0;int keys=0;Gson json=new Gson();for(var e:state.kv().values().entrySet())if(app(e.getKey()).equals(appId)){keys++;kv+=(json.toJson(e.getKey())+":"+json.toJson(e.getValue())+",").getBytes(StandardCharsets.UTF_8).length;}for(var e:state.sealed().entrySet())if(app(e.getKey()).equals(appId))sealed+=(json.toJson(e.getKey())+":"+encodeRecord(e.getValue())+",").getBytes(StandardCharsets.UTF_8).length;return new AppUsage(keys,kv,sealed);}
    /** 原生确认后的本人普通 KV 清理；守卫、钱包、托管和密文不属于此存储，不能随清理重置。 */
    public synchronized void clearAppKv(UUID player,String appId){available();State old=players.getOrDefault(player,new State(ScriptKv.DEFAULT,Map.of()));Map<String,String> next=new LinkedHashMap<>(old.kv().values());next.entrySet().removeIf(e->app(e.getKey()).equals(appId));commit(player,new State(new ScriptKv(next),old.sealed()));}
    public synchronized long namespaceBytes(UUID player,String namespace,boolean sealed){State state=players.get(player);if(state==null)return 0;if(!sealed)return state.kv().bytes(namespace);return state.sealed().entrySet().stream().filter(e->e.getKey().startsWith(namespace+"|")).mapToLong(e->size(e.getValue())).sum();}
    private static long size(SealedRecord r) { return r.salt().length+r.nonce().length+r.cipher().length+12; }
    private static String app(String key) {
        String[] parts=key.split("\\|",-1);
        if(parts.length!=5 || !parts[2].matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || parts[4].isEmpty() || parts[4].length()>64)
            throw new IllegalArgumentException("玩家存储键的命名空间损坏");
        UUID.fromString(parts[0]); return parts[2];
    }
    private static Map<String,Long> usage(State state) {
        Map<String,Long> bytes=new HashMap<>();
        Gson json=new Gson();state.kv().values().forEach((k,v)->bytes.merge(app(k),(long)(json.toJson(k)+":"+json.toJson(v)+",").getBytes(StandardCharsets.UTF_8).length,Long::sum));
        state.sealed().forEach((k,v)->bytes.merge(app(k),(long)(json.toJson(k)+":"+encodeRecord(v)+",").getBytes(StandardCharsets.UTF_8).length,Long::sum)); return bytes;
    }
    private void checkGlobal(State old,State next) {
        Map<String,Long> before=old==null?Map.of():usage(old), after=usage(next); long sum=0;
        for(var e:after.entrySet()) {
            sum+=e.getValue();
            long oldApp=byApp.getOrDefault(e.getKey(),0L),nextApp=oldApp-before.getOrDefault(e.getKey(),0L)+e.getValue();
            if(nextApp>quotas.get().get("kv.per_app")&&nextApp>oldApp)
                throw new StoreQuota.QuotaExceeded("离线玩家在内的 App 总量超过 4 MiB");
        }
        long oldBytes=before.values().stream().mapToLong(Long::longValue).sum(),newTotal=total-oldBytes+sum;
        if(sum>quotas.get().get("data.per_player")&&sum>oldBytes ||newTotal>quotas.get().get("data.server")&&newTotal>total)
            throw new StoreQuota.QuotaExceeded("玩家全 App 总量超过 256 KiB 或全服超过 64 MiB");
    }
    private void install(UUID player,State state) {
        State old=players.put(player,state);
        if(old!=null) usage(old).forEach((app,bytes)->{total-=bytes; byApp.merge(app,-bytes,Long::sum);});
        usage(state).forEach((app,bytes)->{total+=bytes; byApp.merge(app,bytes,Long::sum);});
    }
    private void available() { if(locked) throw new IllegalStateException("玩家存储持久化已锁定"); }
    private void commit(UUID player,State next) {
        available(); checkGlobal(players.get(player),next); byte[] bytes=encode(next).toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>1024*1024) throw new StoreQuota.QuotaExceeded("玩家存储编码超额");
        try {if(budget==null){ScriptStateData.atomicWrite(root.resolve(player+".json"),bytes);install(player,next);}
            else budget.replace("player:"+player,usage(next),()->{try{ScriptStateData.atomicWrite(root.resolve(player+".json"),bytes);}catch(java.io.IOException bad){throw new java.io.UncheckedIOException(bad);}install(player,next);});}
        catch(java.io.UncheckedIOException failure){locked=true;throw new IllegalStateException("玩家存储写盘失败；拒绝继续执行",failure);}
        catch(java.io.IOException failure) { locked=true; throw new IllegalStateException("玩家存储写盘失败；拒绝继续执行",failure); }
    }
    private static JsonObject encode(State state) {
        JsonObject o=new JsonObject(); o.addProperty("format",1); o.add("values",new Gson().toJsonTree(state.kv().values()));
        JsonObject sealed=new JsonObject(); state.sealed().forEach((key,r)->sealed.add(key,encodeRecord(r))); o.add("sealed",sealed); return o;
    }
    public static JsonObject encodeRecord(SealedRecord r) {
        JsonObject o=new JsonObject(); Base64.Encoder b=Base64.getEncoder(); o.addProperty("salt",b.encodeToString(r.salt()));
        o.addProperty("nonce",b.encodeToString(r.nonce())); o.addProperty("cipher",b.encodeToString(r.cipher()));
        o.addProperty("schemaVersion",r.schemaVersion()); o.addProperty("recordVersion",Long.toString(r.recordVersion())); return o;
    }
    public static SealedRecord decodeRecord(JsonObject o) {
        if(o.size()!=5) throw new IllegalArgumentException("保险箱记录字段无效"); Base64.Decoder b=Base64.getDecoder();
        return new SealedRecord(b.decode(o.get("salt").getAsString()),b.decode(o.get("nonce").getAsString()),b.decode(o.get("cipher").getAsString()),
                o.get("schemaVersion").getAsBigDecimal().intValueExact(),o.get("recordVersion").getAsBigDecimal().longValueExact());
    }
    private static State decode(String json) {
        if(JsonScan.check(json,8)!=null) throw new IllegalArgumentException("玩家存储损坏"); JsonObject o=JsonParser.parseString(json).getAsJsonObject();
        if(o.size()!=3 || o.get("format").getAsBigDecimal().intValueExact()!=1) throw new IllegalArgumentException("玩家存储格式无效");
        Map<String,String> values=new HashMap<>();
        o.getAsJsonObject("values").entrySet().forEach(e->{
            if(!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString()) throw new IllegalArgumentException("KV 值不是字符串");
            values.put(e.getKey(),e.getValue().getAsString());
        });
        Map<String,SealedRecord> sealed=new HashMap<>(); o.getAsJsonObject("sealed").entrySet().forEach(e->sealed.put(e.getKey(),decodeRecord(e.getValue().getAsJsonObject())));
        ScriptKv checked=ScriptKv.DEFAULT;
        for(var e:values.entrySet()) {
            app(e.getKey()); int split=e.getKey().lastIndexOf('|');
            checked=checked.with(e.getKey().substring(0,split),e.getKey().substring(split+1),e.getValue(),65536,64);
        }
        Map<String,Integer> sealedCounts=new HashMap<>(); Map<String,Long> sealedBytes=new HashMap<>();
        for(var e:sealed.entrySet()) {
            app(e.getKey()); String ns=e.getKey().substring(0,e.getKey().lastIndexOf('|'));
            if(sealedCounts.merge(ns,1,Integer::sum)>16 || sealedBytes.merge(ns,size(e.getValue()),Long::sum)>16384)
                throw new StoreQuota.QuotaExceeded("存档中的保险箱超出配额");
        }
        return new State(checked,Map.copyOf(sealed));
    }
}
