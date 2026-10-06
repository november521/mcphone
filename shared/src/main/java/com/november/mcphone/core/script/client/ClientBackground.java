package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.*;
import com.november.mcphone.core.script.pkg.BackgroundDeclaration;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 默认关闭；玩家在 App 详情允许后才调度。所有到期需求合成一个宿主请求。 */
public final class ClientBackground {
    private static final BackgroundSchedule SCHEDULE=new BackgroundSchedule(Math::random);
    private static final Map<String,ScriptApp> APPS=new LinkedHashMap<>();private static final Map<String,String> STATUS=new LinkedHashMap<>();
    private static final Set<String> ENABLED=new LinkedHashSet<>();private static final Set<String> ACTIVE=new HashSet<>();
    private static boolean loaded,broken,busy;private static long epoch,checkAt,deadline,generation;private static List<BackgroundSchedule.Due> pending=List.of();
    private ClientBackground() { }
    public static void register(ScriptApp app){List<BackgroundDeclaration.Task> declaration=BackgroundDeclaration.of(app.pkg());if(declaration.isEmpty()){APPS.remove(app.id().toString());return;}if(APPS.size()>=256&&!APPS.containsKey(app.id().toString()))return;APPS.put(app.id().toString(),app);
        AppOptions.register(app.id(),new AppOptions.Toggle("mcphone.background.allowed","mcphone.options.on","mcphone.options.off",()->enabled(app),value->enabled(app,value)));
    }
    public static String status(String app){return STATUS.getOrDefault(app,"");}
    private static String key(ScriptApp app){return app.id()+"|"+app.pkg().digest();}
    public static boolean enabled(ScriptApp app){load();return !broken&&ENABLED.contains(key(app));}
    public static void enabled(ScriptApp app,boolean value){load();if(broken)return;String key=key(app);if(value){if(ENABLED.size()>=32&&!ENABLED.contains(key))return;ENABLED.removeIf(k->k.startsWith(app.id()+"|"));ENABLED.add(key);}else ENABLED.remove(key);SCHEDULE.remove(app.id().toString());ACTIVE.remove(app.id().toString());STATUS.remove(app.id().toString());save();}
    public static void tick(){long now=System.currentTimeMillis();if(!ClientHandshake.complete()){if(epoch!=0){epoch=0;generation++;busy=false;pending=List.of();SCHEDULE.reset();ACTIVE.clear();}return;}
        if(epoch!=ClientHandshake.connectionEpoch()){epoch=ClientHandshake.connectionEpoch();generation++;busy=false;pending=List.of();SCHEDULE.reset();ACTIVE.clear();}
        if(busy){if(now>=deadline)finish(generation,null,"mcphone.background.timeout");return;}if(now<checkAt)return;checkAt=now+1000;
        Set<String> installed=new HashSet<>(),enabled=new HashSet<>(),open=new HashSet<>();boolean phoneOpen=Minecraft.getInstance().screen instanceof PhoneScreen;
        for(var app:PhoneScreenRegistry.getApps())if(app instanceof ScriptAppAdapter adapter){String id=app.getId().toString();installed.add(id);ScriptApp script=adapter.script();var deployment=ClientHandshake.deployment(id);
            if(deployment==null||!Objects.equals(script.frontendDigest(),deployment.frontendDigest())||!APPS.containsKey(id)||!enabled(script))continue;
            enabled.add(id);if(ACTIVE.add(id))SCHEDULE.register(id,BackgroundDeclaration.of(script.pkg()),now);
            if(phoneOpen&&((PhoneScreen)Minecraft.getInstance().screen).isScriptAppOpen(id))open.add(id);
        }
        for(String id:new ArrayList<>(ACTIVE))if(!installed.contains(id)||!enabled.contains(id)){ACTIVE.remove(id);SCHEDULE.remove(id);}
        pending=SCHEDULE.due(now,true,phoneOpen,installed,enabled,open);if(pending.isEmpty())return;
        JsonArray items=new JsonArray();for(var due:pending){JsonObject item=new JsonObject();item.addProperty("app",due.app());item.addProperty("action",due.task().action());item.addProperty("revision",ClientHandshake.deployment(due.app()).deployRev());items.add(item);}
        JsonObject args=new JsonObject();args.add("items",items);busy=true;deadline=now+60_000;long expected=++generation;
        ClientStore.rpc("background.run",args,result->finish(expected,result,null),error->finish(expected,null,"mcphone.background.failed"));
    }
    private static void finish(long expected,JsonObject result,String problem){if(expected!=generation||!busy)return;busy=false;long now=System.currentTimeMillis();boolean phoneOpen=Minecraft.getInstance().screen instanceof PhoneScreen;Map<String,Boolean> outcomes=new HashMap<>();
        if(result!=null&&result.has("items"))for(JsonElement e:result.getAsJsonArray("items")){JsonObject item=e.getAsJsonObject();outcomes.put(item.get("app").getAsString()+"/"+item.get("action").getAsString(),item.get("code").getAsString().equals("OK"));}
        for(var due:pending){boolean success=problem==null&&outcomes.getOrDefault(due.app()+"/"+due.task().action(),false);SCHEDULE.complete(due,success,now,phoneOpen);if(success)STATUS.remove(due.app());else STATUS.put(due.app(),problem==null?"mcphone.background.failed":problem);
            if(SCHEDULE.stopped(due.app())){ScriptApp app=APPS.get(due.app());if(app!=null)ENABLED.remove(key(app));STATUS.put(due.app(),"mcphone.background.stopped");save();}
        }
        pending=List.of();
    }
    private static Path file(){return Minecraft.getInstance().gameDirectory.toPath().resolve("mcphone/background-permissions.json");}
    private static void load(){if(loaded)return;loaded=true;Path path=file();if(!Files.exists(path))return;try{if(Files.isSymbolicLink(path)||Files.size(path)>16*1024)throw new IllegalArgumentException("后台设置超额");String json=Files.readString(path,StandardCharsets.UTF_8);if(com.november.mcphone.core.script.JsonScan.check(json,2)!=null)throw new IllegalArgumentException("后台设置无效");for(JsonElement e:JsonParser.parseString(json).getAsJsonArray()){String key=e.getAsString();if(ENABLED.size()>=32||!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+\\|[0-9a-f]{64}"))throw new IllegalArgumentException("后台设置无效");ENABLED.add(key);}}catch(Exception bad){broken=true;MCphone.LOGGER.error("[MCphone] 后台设置损坏，已停用后台任务",bad);}}
    private static void save(){try{JsonArray values=new JsonArray();ENABLED.forEach(values::add);com.november.mcphone.core.script.server.StoreRepository.atomic(file(),values.toString().getBytes(StandardCharsets.UTF_8));}catch(java.io.IOException bad){broken=true;MCphone.LOGGER.error("[MCphone] 后台设置未保存",bad);}}
}
