package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.AppOptions;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.pkg.*;
import com.november.mcphone.core.script.server.*;
import net.minecraft.client.Minecraft;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 玩家逐 App 许可的前台更新检查。没有 tick、定时器或后台轮询入口。 */
public final class ClientUpdates {
    private record Settings(URI feed,String channel,String consentKey) {}
    private record Cached(long at,List<Appcast.Release> releases,byte[] xml) {}
    private record Bundle(long at,byte[] bytes) {}
    private static final ExecutorService WORKERS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"MCphone-updates");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Map<String,Cached> CACHE=new LinkedHashMap<>();
    private static final Map<String,Bundle> BUNDLES=new LinkedHashMap<>();
    private static final Set<String> IN_FLIGHT=ConcurrentHashMap.newKeySet();
    private static final Map<String,String> STATUS=new ConcurrentHashMap<>();
    private static JsonObject consent;
    private ClientUpdates() {}
    private static Path config(String name) { return Minecraft.getInstance().gameDirectory.toPath().resolve("config/mcphone/"+name); }
    private static Settings settings(ScriptApp app) {
        if(app.pkg()==null || !app.pkg().signed() || !SigManifest.parse(app.pkg().signature()).verify(app.pkg().digest())) return null;
        JsonObject manifest=JsonParser.parseString(new String(app.pkg().entry("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();
        if(!manifest.has("updates")) return null;
        JsonObject updates=manifest.getAsJsonObject("updates"); URI feed=URI.create(updates.get("feed").getAsString());
        if(feed.getHost()==null) throw new IllegalArgumentException("feed 域名无效");
        SafeFetch.validate(feed.toString(),Set.of(feed.getHost().toLowerCase(Locale.ROOT)));
        String channel=updates.has("channel")?updates.get("channel").getAsString():"stable";
        if(!channel.matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("更新频道无效");
        return new Settings(feed,channel,app.id()+"|"+Base64.getEncoder().encodeToString(SigManifest.parse(app.pkg().signature()).pubkey())+"|"+feed+"|"+channel);
    }
    private static boolean allowed(Settings settings) {
        if(consent==null) {
            consent=new JsonObject(); Path file=config("update-consent.json");
            try { if(Files.exists(file)) { String raw=Files.readString(file); if(raw.length()>65536 || JsonScan.check(raw,2)!=null) throw new IllegalArgumentException("更新授权损坏"); consent=JsonParser.parseString(raw).getAsJsonObject(); } }
            catch(Exception bad) { MCphone.LOGGER.warn("[MCphone] 更新授权无法读取，默认关闭",bad); }
        }
        return consent.has(settings.consentKey()) && consent.get(settings.consentKey()).isJsonPrimitive() && consent.get(settings.consentKey()).getAsJsonPrimitive().isBoolean() && consent.get(settings.consentKey()).getAsBoolean();
    }
    public static void register(ScriptApp app) {
        try {
            Settings settings=settings(app); if(settings==null) return;
            AppOptions.register(app.id(),new AppOptions.Toggle("mcphone.update.allow","mcphone.update.on","mcphone.update.off",()->allowed(settings),value->{
                allowed(settings); JsonObject changed=consent.deepCopy(); changed.addProperty(settings.consentKey(),value);
                try { byte[] bytes=changed.toString().getBytes(StandardCharsets.UTF_8); if(bytes.length>65536) throw new IllegalArgumentException("更新授权已满"); ScriptStateData.atomicWrite(config("update-consent.json"),bytes); consent=changed; if(value) check(app); }
                catch(Exception failure) { STATUS.put(app.id().toString(),"mcphone.update.save_failed"); MCphone.LOGGER.warn("[MCphone] 保存更新授权失败",failure); }
            }));
        } catch(RuntimeException invalid) { STATUS.put(app.id().toString(),"mcphone.update.invalid"); }
    }
    public static String domain(ScriptApp app) { try { Settings s=settings(app); return s==null?"":s.feed().getHost(); } catch(RuntimeException bad) { return ""; } }
    public static String status(String id) { return STATUS.getOrDefault(id,""); }
    /** 只能由商店 / 详情页打开动作调用；服务器内源优先。 */
    public static void check(ScriptApp app) {
        if(ClientHandshake.deployment(app.id().toString())!=null||ServerStoreSource.cached(app)){ClientServerUpdates.check(app,false);return;}
        Settings settings;
        try { settings=settings(app); if(settings==null || !allowed(settings) || ClientHandshake.deployment(app.id().toString())!=null) return; }
        catch(RuntimeException bad) { STATUS.put(app.id().toString(),"mcphone.update.invalid"); return; }
        String id=app.id().toString(); if(!IN_FLIGHT.add(id)) return;
        STATUS.put(id,"mcphone.update.checking");
        try { WORKERS.execute(()->{
            boolean scheduled=false;
            try {
                String identity=id+"|"+Base64.getEncoder().encodeToString(SigManifest.parse(app.pkg().signature()).pubkey());
                UpdateHistory history=new UpdateHistory(config("update-history.json"));
                if(history.conflicted(identity)) throw new IllegalArgumentException("同版本签名摘要冲突");
                ClientPackageVersions.observe(app.pkg());
                long installed=RevocationPolicy.versionOf(app.pkg()), highest=Math.max(history.highest(identity,installed),ClientPackageVersions.highest(app));
                if(installed==highest&&!history.record(identity,installed,app.pkg().digest()))throw new IllegalArgumentException("同版本签名摘要冲突");
                List<Appcast.Release> releases;byte[] xml;
                synchronized(CACHE) {
                    Cached cached=CACHE.get(settings.feed().toString());
                    releases=cached!=null && System.currentTimeMillis()-cached.at()<21600000?cached.releases():null;
                    xml=releases==null?null:cached.xml();
                }
                if(releases==null) {
                    xml=SafeFetch.get(settings.feed().toString(),Set.of(settings.feed().getHost().toLowerCase(Locale.ROOT)),Set.of(SafeFetch.Type.XML),SafeFetch.MAX_BYTES,null).body();
                    releases=Appcast.parse(xml,settings.feed()); synchronized(CACHE) { if(CACHE.size()>=128) CACHE.remove(CACHE.keySet().iterator().next()); CACHE.put(settings.feed().toString(),new Cached(System.currentTimeMillis(),releases,xml)); }
                }
                byte[] authorKey=SigManifest.parse(app.pkg().signature()).pubkey();AuthorRevocationCache revocations=new AuthorRevocationCache(config("author-revocations.json"));
                Optional<AuthorRevocation.Evidence> statement=AuthorRevocation.evidence(xml,settings.feed(),id,authorKey);if(statement.isPresent())revocations.accept(authorKey,statement.get());
                RevocationPolicy policy=revocations.policy(id,authorKey);ClientRevocations.authorChanged();
                for(Appcast.Release release:releases.stream().filter(r->r.channel().equals(settings.channel())&&r.minimumApi()<=com.november.mcphone.core.script.net.ScriptProtocol.SCRIPT_API&&r.version()<=highest).limit(4).toList()){
                    var evidence=UpdateVerifier.evidence(bundle(release),release,app.pkg());ClientPackageVersions.observe(evidence.app());
                    if(ClientPackageVersions.blocked(app)!=null){STATUS.put(id,"mcphone.update.conflict");return;}
                    if(evidence.version()==highest&&!new UpdateHistory(config("update-history.json")).record(identity,evidence.version(),evidence.app().digest())){STATUS.put(id,"mcphone.update.conflict");return;}
                }
                Optional<Appcast.Release> latest=Appcast.latest(releases.stream().filter(r->policy.rejected(id,r.version(),"")==null).toList(),settings.channel(),com.november.mcphone.core.script.net.ScriptProtocol.SCRIPT_API,highest);
                if(latest.isEmpty()) { STATUS.put(id,"mcphone.update.current"); return; }
                Appcast.Release release=latest.get(); byte[] zip=bundle(release);
                UpdateVerifier.Verified verified=UpdateVerifier.verify(zip,release,app.pkg(),highest,Set.of(),policy);
                ClientPackageVersions.observe(verified.app());if(ClientPackageVersions.blocked(app)!=null)throw new IllegalArgumentException("同版本作者签名冲突");
                if(verified.decision()!=UpdateVerifier.Decision.AUTOMATIC) { STATUS.put(id,"mcphone.update.approval"); return; }
                // 编译也先完成；语法坏包绝不覆盖仍可用的文件。
                ScriptApp incoming=ScriptAppFolder.read(app.file(),zip);
                Minecraft.getInstance().execute(()->{
                    boolean switched=false;
                    try {
                        if(!allowed(settings)) return;
                        ScriptApp current=LocalScriptSource.scriptOf(app.id());
                        if(current==null || current.pkg()==null || !current.pkg().digest().equals(app.pkg().digest())) return;
                        Path root=ScriptAppFolder.dir().toAbsolutePath().normalize(), target=root.resolve(app.file()).normalize();
                        if(!target.getParent().equals(root) || !target.getFileName().toString().endsWith(".zip") || Files.isSymbolicLink(target)) throw new IllegalArgumentException("更新路径无效");
                        ScriptStateData.atomicWrite(target,zip);ClientPackageVersions.accept(incoming); ScriptAppFolder.invalidate(target);
                        switched=true;LocalScriptSource.refresh();
                        // 只记已经完成原子替换的版本；崩溃后当前包的 versionCode 同样阻止降级。
                        new UpdateHistory(config("update-history.json")).record(identity,verified.version(),verified.app().digest());
                        STATUS.put(id,"mcphone.update.installed");
                    } catch(Exception failure) { STATUS.put(id,switched?"mcphone.update.history_failed":"mcphone.update.failed"); MCphone.LOGGER.warn("[MCphone] 切换更新失败 {}",id,failure); }
                    finally{IN_FLIGHT.remove(id);}
                });
                scheduled=true;
            } catch(Exception failure) { STATUS.put(id,"mcphone.update.failed"); MCphone.LOGGER.warn("[MCphone] 前台更新检查失败 {}",id,failure); }
            finally { if(!scheduled)IN_FLIGHT.remove(id); }
        }); } catch(RejectedExecutionException busy) { IN_FLIGHT.remove(id); STATUS.put(id,"mcphone.update.busy"); }
    }
    private static byte[] bundle(Appcast.Release release)throws Exception {
        String key=release.url()+"|"+Base64.getEncoder().encodeToString(release.zipSignature());long now=System.currentTimeMillis();
        synchronized(BUNDLES){Bundle cached=BUNDLES.get(key);if(cached!=null&&now-cached.at()<21600000)return cached.bytes().clone();}
        byte[] raw=SafeFetch.get(release.url().toString(),Set.of(release.url().getHost().toLowerCase(Locale.ROOT)),Set.of(SafeFetch.Type.ZIP),release.length(),null).body();
        synchronized(BUNDLES){if(BUNDLES.size()>=64)BUNDLES.remove(BUNDLES.keySet().iterator().next());BUNDLES.put(key,new Bundle(now,raw.clone()));}return raw;
    }
}
