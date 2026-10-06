package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.core.script.pkg.*;
import com.november.mcphone.core.script.server.RevocationPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;

/** 仅用于提示与安装门控；服务端每次 RPC 的检查不依赖这份缓存。 */
public final class ClientRevocations {
    private record ServerRule(ServerRevocationHint hint,long revision){}
    private static final Map<String,ServerRule> SERVER=new HashMap<>();private static AuthorRevocationCache authors;private static boolean cacheFailed;
    private static final Map<ScriptApp,Integer> ACTIVE=new IdentityHashMap<>();
    private static final Map<String,String> USAGE=new HashMap<>();
    private static final Map<String,Long> PENDING=new HashMap<>();
    private static long sequence;
    private ClientRevocations(){}
    public static synchronized void authorChanged(){authors=null;cacheFailed=false;loadAuthors();}
    public static synchronized void register(ScriptApp app){if(app.pkg()!=null&&app.pkg().signed())loadAuthors();}
    private static void loadAuthors(){if(authors!=null||cacheFailed)return;try{authors=new AuthorRevocationCache(Minecraft.getInstance().gameDirectory.toPath().resolve("config/mcphone/author-revocations.json"));}catch(Exception bad){cacheFailed=true;MCphone.LOGGER.error("[MCphone] 作者吊销缓存损坏",bad);}}
    public static void resetPolicy(){SERVER.clear();USAGE.clear();PENDING.clear();}
    public static void clear(){resetPolicy();ACTIVE.clear();}
    public static void push(ScriptPush push){if(!push.isHost()||!push.topic().equals("mcphone:revocation"))return;try{JsonObject o=JsonParser.parseString(new String(push.data(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();if(!ClientHandshake.complete()||!o.get("serverId").getAsString().equals(String.valueOf(ClientHandshake.serverId()))||Long.parseLong(o.get("epoch").getAsString())!=ClientHandshake.connectionEpoch())return;String app=o.get("app").getAsString();ServerRule prior=SERVER.get(app);if(prior!=null&&prior.revision()>=push.revision()||SERVER.size()>=256&&!SERVER.containsKey(app))return;byte[] key=o.has("publicKey")?Base64.getDecoder().decode(o.get("publicKey").getAsString()):new byte[0];ServerRevocationHint hint=new ServerRevocationHint(o.get("digest").getAsString(),key,Long.parseLong(o.get("minVersion").getAsString()),o.has("ownerMinimum")?o.get("ownerMinimum").getAsBigDecimal().longValueExact():0,o.get("reason").getAsString(),o.get("revoked").getAsBoolean(),o.has("ownerRevoked")&&o.get("ownerRevoked").getAsBoolean());SERVER.put(app,new ServerRule(hint,push.revision()));for(ScriptApp active:List.copyOf(ACTIVE.keySet()))if(active.id().toString().equals(app))checkUsage(active);}catch(RuntimeException bad){MCphone.LOGGER.warn("[MCphone] 忽略无效吊销提示");}}
    public static synchronized RevocationPolicy.Rule rejected(ScriptApp app){if(app.pkg()==null)return null;String id=app.id().toString();long version=app.versionCode();ServerRule row=SERVER.get(id);if(row!=null){RevocationPolicy.Rule reason=row.hint.rejected(version,app.pkg().digest(),app.authorKey());if(reason!=null)return reason;}if(!app.pkg().signed())return null;
        loadAuthors();
        if(cacheFailed)return new RevocationPolicy.Rule(Long.MAX_VALUE,Set.of(),Set.of(),Component.translatable("mcphone.update.revocation_cache_failed").getString());return authors==null?null:authors.policy(id,app.authorKey()).rejected(id,version,app.pkg().digest());
    }
    public static Component blocked(ScriptApp app){Component history=ClientPackageVersions.blocked(app);if(history!=null)return history;var rule=rejected(app);if(rule!=null)return Component.translatable("mcphone.update.revoked",rule.reason());String key=usageKey(app);String reason=USAGE.get(key);return reason==null||reason.isEmpty()?null:Component.literal(reason);}
    public static boolean checking(ScriptApp app){return PENDING.containsKey(usageKey(app));}
    private static String usageKey(ScriptApp app){return app.id()+"|"+Base64.getEncoder().encodeToString(app.authorKey())+"|"+(app.pkg()==null?app.frontendDigest():app.pkg().digest());}
    public static void open(ScriptApp app){ACTIVE.merge(app,1,Integer::sum);checkUsage(app);}
    public static void close(ScriptApp app){int refs=ACTIVE.getOrDefault(app,0);if(refs<=1)ACTIVE.remove(app);else ACTIVE.put(app,refs-1);}
    public static void handshakeReady(){for(ScriptApp app:List.copyOf(ACTIVE.keySet()))checkUsage(app);}
    private static void checkUsage(ScriptApp app){if(!ClientHandshake.complete()||app.pkg()==null)return;String key=usageKey(app);if(USAGE.size()>=256&&!USAGE.containsKey(key))USAGE.clear();long expected=++sequence;PENDING.put(key,expected);JsonObject args=new JsonObject();args.addProperty("app",app.id().toString());args.addProperty("version",Long.toString(app.versionCode()));args.addProperty("digest",app.pkg().digest());args.addProperty("publicKey",app.pkg().signed()?Base64.getEncoder().encodeToString(app.authorKey()):"");ClientStore.rpc("policy.check",args,data->{if(!Objects.equals(PENDING.get(key),expected))return;PENDING.remove(key);USAGE.put(key,data.get("revoked").getAsBoolean()?Component.translatable("mcphone.update.revoked",data.get("reason").getAsString()).getString():"");},error->{if(!Objects.equals(PENDING.get(key),expected))return;PENDING.remove(key);USAGE.put(key,error);});}
    public static void checkInstall(ScriptApp app,java.util.function.Consumer<Component> result){checkInstall(app,false,result);}
    /** 人工降级只跳过本机最高版本提示，作者撤销和服主策略仍逐项核对。 */
    public static void checkManualInstall(ScriptApp app,java.util.function.Consumer<Component> result){checkInstall(app,true,result);}
    private static void checkInstall(ScriptApp app,boolean manual,java.util.function.Consumer<Component> result){Component own=manual?(rejected(app)==null?null:Component.translatable("mcphone.update.revoked",rejected(app).reason())):blocked(app);if(own!=null){result.accept(own);return;}if(!ClientHandshake.complete()||app.pkg()==null){result.accept(null);return;}JsonObject args=new JsonObject();args.addProperty("app",app.id().toString());args.addProperty("version",Long.toString(app.versionCode()));args.addProperty("digest",app.pkg().digest());args.addProperty("publicKey",app.pkg().signed()?Base64.getEncoder().encodeToString(app.authorKey()):"");ClientStore.rpc("policy.check",args,data->result.accept(data.get("revoked").getAsBoolean()?Component.translatable("mcphone.update.revoked",data.get("reason").getAsString()):null),error->result.accept(Component.literal(error)));}
}
