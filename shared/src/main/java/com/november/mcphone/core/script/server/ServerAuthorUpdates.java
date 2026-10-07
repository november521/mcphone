package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.pkg.*;
import net.minecraft.server.MinecraftServer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 服主明确开启才跟随作者。两个有界 worker 做 HTTPS 与双签名，落地重新检查当前包、白名单和权限。 */
public final class ServerAuthorUpdates implements AutoCloseable {
    private record Source(AppPackage pkg,URI feed,String channel,String identity,Set<String> approved,long highest,RevocationPolicy policy){}
    private final MinecraftServer server;private final Supplier<ScriptRuntimeConfig> config;private final ServerStore store;
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"MCphone-author-updates");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Source> sources=new LinkedHashMap<>();private final Map<String,Long> checked=new HashMap<>();private final Set<String> pending=new HashSet<>();private volatile boolean closed;private long lastDispatch;
    public ServerAuthorUpdates(MinecraftServer server,Supplier<ScriptRuntimeConfig> config,ServerStore store){this.server=server;this.config=config;this.store=store;}
    public void register(AppPackage pkg,Set<String> approved){
        String id=pkg.manifest().id();Source prior=sources.get(id);if(prior!=null&&prior.pkg().digest().equals(pkg.digest())&&prior.approved().equals(approved))return;
        sources.remove(id);checked.remove(id);StoreRepository.requireSignature(pkg);
        JsonObject manifest=JsonParser.parseString(new String(pkg.entry("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();if(!manifest.has("updates"))return;
        JsonObject updates=manifest.getAsJsonObject("updates");URI feed=URI.create(updates.get("feed").getAsString());SafeFetch.validate(feed.toString(),Set.of(feed.getHost().toLowerCase(Locale.ROOT)));
        String channel=updates.has("channel")?updates.get("channel").getAsString():"stable";if(!channel.matches("[a-z][a-z0-9_-]{0,31}"))throw new IllegalArgumentException("更新频道无效");
        if(sources.size()>=64)throw new IllegalArgumentException("服务器更新源超额");
        byte[] key=SigManifest.parse(pkg.signature()).pubkey();ScriptHost h=ScriptHost.current();
        sources.put(id,new Source(pkg,feed,channel,id+"|"+Base64.getEncoder().encodeToString(key),Set.copyOf(approved),Math.max(RevocationPolicy.versionOf(pkg),h.witnesses().approved(id,key)),h.revocations(pkg)));
    }
    public void tick(){
        if(closed)return;long now=System.currentTimeMillis();ScriptRuntimeConfig cfg=config.get();if(!cfg.net().enabled()||now-lastDispatch<1000)return;
        for(Source source:sources.values()){
            String id=source.pkg().manifest().id();if(!cfg.followsUpdates(id)||pending.contains(id)||now-checked.getOrDefault(source.identity(),0L)<21600000||!cfg.net().hosts().contains(source.feed().getHost().toLowerCase(Locale.ROOT)))continue;
            pending.add(id);checked.put(source.identity(),now);lastDispatch=now;
            try{workers.execute(()->{
                byte[] raw=null;UpdateVerifier.Verified verified=null;List<AppPackage> evidence=new ArrayList<>();String failure="";
                try{
                    byte[] xml=SafeFetch.get(source.feed().toString(),cfg.net().hosts(),Set.of(SafeFetch.Type.XML),SafeFetch.MAX_BYTES,null).body();List<Appcast.Release> releases=Appcast.parse(xml,source.feed());
                    for(Appcast.Release old:releases.stream().filter(r->r.channel().equals(source.channel())&&r.minimumApi()<=com.november.mcphone.core.script.net.ScriptProtocol.SCRIPT_API&&r.version()<=source.highest()).limit(4).toList()){
                        byte[] historical=download(old,cfg);evidence.add(UpdateVerifier.evidence(historical,old,source.pkg()).app());
                    }
                    Optional<Appcast.Release> latest=Appcast.latest(releases,source.channel(),com.november.mcphone.core.script.net.ScriptProtocol.SCRIPT_API,source.highest());
                    if(latest.isPresent()){raw=download(latest.get(),cfg);verified=UpdateVerifier.verify(raw,latest.get(),source.pkg(),source.highest(),source.approved(),source.policy());}
                }catch(Exception bad){failure=bad.getClass().getSimpleName();}
                byte[] bundle=raw;var result=verified;String issue=failure;
                server.execute(()->{
                    if(closed)return;pending.remove(id);ScriptRuntimeConfig live=config.get();if(sources.get(id)!=source||!live.followsUpdates(id)||!live.net().enabled()||!live.net().hosts().contains(source.feed().getHost().toLowerCase(Locale.ROOT)))return;
                    try{for(AppPackage proof:evidence)ScriptHost.current().witnesses().observe(proof,false);if(result!=null)store.acceptAuthorUpdate(source.pkg(),result,bundle,source.approved());else if(!issue.isEmpty())MCphone.LOGGER.warn("[MCphone] {} 作者更新检查失败：{}，保留当前版本",id,issue);}
                    catch(Exception bad){MCphone.LOGGER.warn("[MCphone] {} 作者更新落地失败：{}",id,bad.toString());}
                    finally{ScriptHost.current().publishRevocations();}
                });
            });}catch(RejectedExecutionException busy){pending.remove(id);checked.remove(source.identity());}break;
        }
    }
    private static byte[] download(Appcast.Release release,ScriptRuntimeConfig cfg)throws Exception{return SafeFetch.get(release.url().toString(),cfg.net().hosts(),Set.of(SafeFetch.Type.ZIP),release.length(),null).body();}
    public void reload(){checked.clear();}
    @Override public void close(){closed=true;workers.shutdownNow();sources.clear();checked.clear();pending.clear();}
}
