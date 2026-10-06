package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.*;
import com.november.mcphone.core.script.net.ScriptPush;
import com.november.mcphone.core.script.JsonScan;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 角标只读计数缓存。订阅同步在 tick，语言表在安装时解一次，渲染路径不读文件。 */
public final class ClientNotifications {
    private static final Map<String,Integer> COUNTS=new LinkedHashMap<>();
    private static final Map<String,Map<String,Map<String,String>>> LANG=new LinkedHashMap<>();
    private static final Set<String> DISABLED=new LinkedHashSet<>();
    private record High(String app,String id,String title,String body,long expires){}
    private static final ArrayDeque<High> HIGH=new ArrayDeque<>();
    private static final com.november.mcphone.core.script.net.NotificationCounts.Accumulator COUNTS_PENDING=new com.november.mcphone.core.script.net.NotificationCounts.Accumulator();
    private static Runnable syncNext;private static long syncAt;
    private static long epoch,lastTick,retryAt,lastRevision;private static String sent="";private static boolean loading,busy,preferencesBroken;
    private ClientNotifications() { }
    public static void register(ScriptApp app){
        Map<String,Map<String,String>> languages=new LinkedHashMap<>();if(app.pkg()!=null)for(String path:app.pkg().paths())if(path.startsWith("lang/")&&path.endsWith(".json")){
            try{JsonObject o=JsonParser.parseString(new String(app.pkg().entry(path),StandardCharsets.UTF_8)).getAsJsonObject();Map<String,String> strings=new LinkedHashMap<>();for(String key:o.keySet())strings.put(key,o.get(key).getAsString());languages.put(path.substring(5,path.length()-5),Map.copyOf(strings));}catch(RuntimeException bad){MCphone.LOGGER.warn("[MCphone] 通知语言文件无效 app={}",app.id());}
        }
        LANG.put(app.id().toString(),Map.copyOf(languages));
        AppOptions.register(app.id(),new AppOptions.Toggle("mcphone.notify.allowed","mcphone.options.on","mcphone.options.off",()->enabled(app.id().toString()),value->enabled(app.id().toString(),value)));
    }
    public static int badge(String app){return COUNTS.getOrDefault(app,0);}
    public static int total(){return COUNTS.values().stream().mapToInt(Integer::intValue).sum();}
    public static boolean enabled(String app){load();return !preferencesBroken&&!DISABLED.contains(app);}
    public static void enabled(String app,boolean enabled){load();if(preferencesBroken)return;if(enabled)DISABLED.remove(app);else {if(DISABLED.size()>=256&&!DISABLED.contains(app))return;DISABLED.add(app);}save();sent="";}
    public static void clear(){COUNTS.clear();HIGH.clear();COUNTS_PENDING.clear();syncNext=null;epoch=0;busy=false;sent="";retryAt=0;lastRevision=0;}
    public static void tick(){
        if(!ClientHandshake.complete()){if(epoch!=0)clear();return;}long current=ClientHandshake.connectionEpoch();if(epoch!=current){clear();epoch=current;}
        long now=System.currentTimeMillis();if(syncNext!=null&&now>=syncAt){Runnable next=syncNext;syncNext=null;next.run();}if(now-lastTick<1000||now<retryAt||busy)return;lastTick=now;load();
        JsonArray apps=new JsonArray(),disabled=new JsonArray();for(var app:PhoneScreenRegistry.getApps())if(app instanceof ScriptAppAdapter||Set.of("mcphone:mailbox","mcphone:item_escrow").contains(app.getId().toString())){
            if(apps.size()>=(ClientAdministration.notificationChunks()?130:32))break;String id=app.getId().toString();apps.add(id);if(preferencesBroken||DISABLED.contains(id))disabled.add(id);
        }
        JsonObject args=new JsonObject();args.add("apps",apps);args.add("disabled",disabled);String key=args.toString();if(sent.equals(key))return;busy=true;long expected=epoch;
        if(ClientAdministration.notificationChunks())syncParts(apps,disabled,UUID.randomUUID().toString().replace("-",""),0,key,expected);
        else ClientStore.rpc("notify.sync",args,result->{if(expected!=epoch)return;busy=false;sent=key;counts(result);},error->{if(expected!=epoch)return;busy=false;retryAt=System.currentTimeMillis()+60_000;});
    }
    private static void syncParts(JsonArray apps,JsonArray disabled,String token,int offset,String key,long expected){
        if(expected!=epoch)return;int end=Math.min(apps.size(),offset+16);JsonArray part=new JsonArray(),muted=new JsonArray();
        for(int i=offset;i<end;i++){JsonElement app=apps.get(i);part.add(app);for(JsonElement value:disabled)if(app.equals(value)){muted.add(app);break;}}
        JsonObject args=ClientStore.args("token",token);args.addProperty("offset",offset);args.addProperty("total",apps.size());args.add("apps",part);args.add("disabled",muted);
        ClientStore.rpc("notify.sync.part",args,result->{if(expected!=epoch)return;
            if(result.get("next").getAsInt()!=end||result.get("complete").getAsBoolean()!=(end==apps.size())){busy=false;retryAt=System.currentTimeMillis()+60000;return;}
            if(end==apps.size()){counts(result);busy=false;sent=key;}else {syncAt=System.currentTimeMillis()+250;syncNext=()->syncParts(apps,disabled,token,end,key,expected);}
        },error->{if(expected!=epoch)return;busy=false;retryAt=System.currentTimeMillis()+60000;});
    }
    public static void counts(JsonObject data){Map<String,Integer> next=COUNTS_PENDING.accept(data);if(next==null)return;COUNTS.clear();COUNTS.putAll(next);lastRevision=COUNTS_PENDING.revision();}
    public static void push(ScriptPush push){if(!push.isHost()||!push.topic().equals("mcphone:notify.notice")||!ClientHandshake.complete())return;
        try{JsonObject data=JsonParser.parseString(new String(push.data(),StandardCharsets.UTF_8)).getAsJsonObject();if(!ClientHandshake.serverId().toString().equals(data.get("serverId").getAsString())||ClientHandshake.connectionEpoch()!=new java.math.BigDecimal(data.get("epoch").getAsString()).longValueExact()||push.revision()<=lastRevision||push.revision()!=data.get("revision").getAsBigDecimal().longValueExact())return;
            if(epoch!=ClientHandshake.connectionEpoch()){clear();epoch=ClientHandshake.connectionEpoch();}counts(data);if(data.has("notice"))high(data.getAsJsonObject("notice"));}catch(RuntimeException bad){MCphone.LOGGER.warn("[MCphone] 通知推送无效");}
    }
    private static void high(JsonObject notice){
        if(!"HIGH".equals(notice.get("priority").getAsString())||notice.get("read").getAsBoolean())return;
        String app=notice.get("app").getAsString(),id=notice.get("id").getAsString();
        if(HIGH.stream().anyMatch(n->n.app().equals(app)&&n.id().equals(id)))return;
        if(!enabled(app)||PhoneScreenRegistry.getApp(ResourceLocation.tryParse(app))==null)return;
        long expires=new java.math.BigDecimal(notice.get("expires").getAsString()).longValueExact();
        if(expires>0&&expires<=System.currentTimeMillis())return;
        String title=clean(text(app,notice.get("titleKey").getAsString(),notice.has("titleArgs")?notice.getAsJsonArray("titleArgs"):null));
        String body=notice.has("bodyKey")?clean(text(app,notice.get("bodyKey").getAsString(),notice.has("bodyArgs")?notice.getAsJsonArray("bodyArgs"):null)):"";
        HIGH.removeIf(n->n.app().equals(app));while(HIGH.size()>=4)HIGH.removeFirst();High high=new High(app,id,title,body,expires);HIGH.addLast(high);long expected=epoch;
        Minecraft.getInstance().getToasts().addToast(new NotificationToast(title,body,()->expected==epoch&&ClientHandshake.complete()&&enabled(app)&&HIGH.contains(high)&&(expires<=0||expires>System.currentTimeMillis())));
    }
    private static String clean(String value){String clean=value.replaceAll("[\\p{Cntrl}§]"," ");return clean.substring(0,Math.min(256,clean.length()));}
    public static void read(String id){HIGH.removeIf(n->n.id().equals(id));}
    public static void renderLockScreen(net.minecraft.client.gui.GuiGraphics g,net.minecraft.client.gui.Font font,int x,int y,int width,int height){
        long now=System.currentTimeMillis();HIGH.removeIf(n->!enabled(n.app())||badge(n.app())==0||n.expires()>0&&n.expires()<=now);int row=0;
        for(var iterator=HIGH.descendingIterator();iterator.hasNext()&&row<2;row++){
            High notice=iterator.next();int top=y+Math.max(0,height-38*(row+1)-4);g.fill(x+4,top,x+width-4,top+33,PhoneTheme.COLOR_TOAST_BG);
            g.drawString(font,font.plainSubstrByWidth(notice.title(),Math.max(0,width-16)),x+8,top+4,PhoneTheme.FONT_COLOR_TOAST_TITLE,false);
            g.drawString(font,font.plainSubstrByWidth(notice.body(),Math.max(0,width-16)),x+8,top+17,PhoneTheme.FONT_COLOR_TOAST,false);
        }
    }
    public static String text(String app,String key,JsonArray args){Map<String,Map<String,String>> languages=LANG.getOrDefault(app,Map.of());String language=Minecraft.getInstance().getLanguageManager().getSelected();String value=languages.getOrDefault(language,Map.of()).getOrDefault(key,languages.getOrDefault("en_us",Map.of()).getOrDefault(key,key));
        if(Set.of("mcphone:mailbox","mcphone:item_escrow").contains(app))return net.minecraft.network.chat.Component.translatable(key,args==null?new Object[0]:java.util.stream.StreamSupport.stream(args.spliterator(),false).map(JsonElement::getAsString).toArray()).getString();
        if(args!=null){for(int i=0;i<Math.min(4,args.size());i++)value=value.replace("{"+i+"}",args.get(i).getAsString());int at=0;StringBuilder formatted=new StringBuilder();for(int i=0;i<value.length();i++){if(value.charAt(i)=='%'&&i+1<value.length()&&value.charAt(i+1)=='s'&&at<args.size()){formatted.append(args.get(at++).getAsString());i++;}else formatted.append(value.charAt(i));}value=formatted.toString();}
        return value.replace('§',' ');
    }
    private static Path file(){return Minecraft.getInstance().gameDirectory.toPath().resolve("mcphone/notification-preferences.json");}
    private static void load(){if(loading)return;loading=true;Path path=file();if(!Files.exists(path))return;try{if(Files.isSymbolicLink(path)||Files.size(path)>32*1024)throw new IllegalArgumentException("设置超额");String json=Files.readString(path,StandardCharsets.UTF_8);if(JsonScan.check(json,2)!=null)throw new IllegalArgumentException("设置无效");for(JsonElement e:JsonParser.parseString(json).getAsJsonArray()){if(DISABLED.size()>=256)throw new IllegalArgumentException("设置超额");String app=e.getAsString();if(app.length()>64||ResourceLocation.tryParse(app)==null)throw new IllegalArgumentException("App 设置无效");DISABLED.add(app);}}catch(Exception bad){preferencesBroken=true;MCphone.LOGGER.error("[MCphone] 通知设置损坏，已暂停通知；请修复 {}",path,bad);}}
    private static void save(){try{JsonArray values=new JsonArray();DISABLED.forEach(values::add);com.november.mcphone.core.script.server.StoreRepository.atomic(file(),values.toString().getBytes(StandardCharsets.UTF_8));}catch(java.io.IOException bad){preferencesBroken=true;MCphone.LOGGER.error("[MCphone] 通知设置未保存",bad);}}
}
