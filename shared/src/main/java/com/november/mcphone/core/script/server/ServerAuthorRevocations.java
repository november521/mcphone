package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.pkg.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 作者 feed 的独立有界队列。服主关闭出口或取消跟随时不联网；落地前复核当前部署与授权配置。 */
public final class ServerAuthorRevocations implements AutoCloseable {
    private record Source(String app,String digest,URI feed,byte[] key){}
    private final MinecraftServer server;private final Supplier<ScriptRuntimeConfig> runtime;private final Runnable changed;private final AuthorRevocationCache cache;
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"MCphone-author-revocations");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Source> sources=new LinkedHashMap<>();private final Map<String,Long> checked=new HashMap<>();private final Set<String> pending=new HashSet<>();private final Set<String> failed=new HashSet<>();private volatile boolean closed;private long lastDispatch;
    public ServerAuthorRevocations(MinecraftServer server,Supplier<ScriptRuntimeConfig> runtime,Runnable changed)throws IOException {this.server=server;this.runtime=runtime;this.changed=changed;cache=new AuthorRevocationCache(server.getWorldPath(LevelResource.ROOT).resolve("mcphone/author-revocations.json"));}
    public void register(AppPackage pkg){String id=pkg.manifest().id();sources.remove(id);checked.remove(id);if(!pkg.signed())return;SigManifest sig=SigManifest.parse(pkg.signature());if(!sig.verify(pkg.digest()))return;JsonObject manifest=JsonParser.parseString(new String(pkg.entry("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();if(!manifest.has("updates"))return;URI feed=URI.create(manifest.getAsJsonObject("updates").get("feed").getAsString());SafeFetch.validate(feed.toString(),Set.of(feed.getHost().toLowerCase(Locale.ROOT)));if(sources.size()>=256)throw new IllegalStateException("作者 feed 超额");sources.put(id,new Source(id,pkg.digest(),feed,sig.pubkey()));}
    public RevocationPolicy policy(AppPackage pkg){String app=pkg.manifest().id();if(!runtime.get().followsAuthor(app)||!pkg.signed())return RevocationPolicy.NONE;try{if(failed.contains(app))return blocked(app);return cache.policy(app,SigManifest.parse(pkg.signature()).pubkey());}catch(RuntimeException bad){return blocked(app);}}
    private static RevocationPolicy blocked(String app){return new RevocationPolicy(Map.of(app,new RevocationPolicy.Rule(Long.MAX_VALUE,Set.of(Long.MAX_VALUE),Set.of(),"作者吊销记录不可用，请联系服主")));}
    public RevocationPolicy policy(String app){Source source=sources.get(app);if(source==null||!runtime.get().followsAuthor(app))return RevocationPolicy.NONE;if(failed.contains(app))return blocked(app);return cache.policy(app,source.key());}
    public RevocationPolicy policy(String app,byte[] key){if(!runtime.get().followsAuthor(app))return RevocationPolicy.NONE;if(failed.contains(app))return blocked(app);return cache.policy(app,key);}
    public void tick(){if(closed)return;long now=System.currentTimeMillis();ScriptRuntimeConfig current=runtime.get();if(!current.net().enabled()||now-lastDispatch<1000)return;
        for(Source source:sources.values()){if(!current.followsAuthor(source.app())||pending.contains(source.app())||now-checked.getOrDefault(source.app(),0L)<21600000||!current.net().hosts().contains(source.feed().getHost().toLowerCase(Locale.ROOT)))continue;lastDispatch=now;pending.add(source.app());checked.put(source.app(),now);
            try{workers.execute(()->{Optional<AuthorRevocation.Evidence> evidence=Optional.empty();try{byte[] xml=SafeFetch.get(source.feed().toString(),current.net().hosts(),Set.of(SafeFetch.Type.XML),SafeFetch.MAX_BYTES,null).body();evidence=AuthorRevocation.evidence(xml,source.feed(),source.app(),source.key());}catch(Exception unavailable){/* 网络失败保留旧证据，不把未验证的内容交给玩家。 */}var result=evidence;server.execute(()->{if(closed)return;pending.remove(source.app());Source live=sources.get(source.app());ScriptRuntimeConfig settings=runtime.get();if(live!=source||!settings.followsAuthor(source.app())||!settings.net().enabled()||!settings.net().hosts().contains(source.feed().getHost().toLowerCase(Locale.ROOT)))return;result.ifPresent(valid->{try{boolean update=cache.accept(source.key(),valid);boolean recovery=failed.remove(source.app());if(update||recovery)changed.run();}catch(IOException bad){failed.add(source.app());changed.run();MCphone.LOGGER.error("[MCphone] {} 的作者吊销无法持久化，已阻止该 App",source.app(),bad);}});});});}catch(RejectedExecutionException busy){pending.remove(source.app());checked.remove(source.app());}break;
        }
    }
    public void remove(String app){sources.remove(app);checked.remove(app);failed.remove(app);}
    public void reload(){checked.clear();}
    @Override public void close(){closed=true;workers.shutdownNow();sources.clear();pending.clear();checked.clear();failed.clear();}
}
