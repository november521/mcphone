package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.api.sdk.notify.Priority;
import com.november.mcphone.core.script.JsonScan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** 主线程通知账本。先原子保存后推送；坏文件拒绝启动，绝不以空列表覆盖。 */
public final class NotificationInbox {
    public static final int PLAYER_BYTES=256*1024,GLOBAL_BYTES=64*1024*1024,MAX_APPS=NotificationSync.MAX_APPS;
    public record Notice(long id,String app,NotificationMessage message,long created,long expires,Priority priority,boolean read) { }
    private static final class Player {
        long next;final Set<String> subscriptions=new LinkedHashSet<>();final List<Notice> notices=new ArrayList<>();
        Player copy(){Player p=new Player();p.next=next;p.subscriptions.addAll(subscriptions);p.notices.addAll(notices);return p;}
    }
    private final Path dir;private final LongSupplier clock;
    private final StorageBudget budget;
    private java.util.function.Supplier<QuotaConfig> quotas=()->QuotaConfig.DEFAULT;
    public NotificationInbox quotas(java.util.function.Supplier<QuotaConfig> config){quotas=config;return this;}
    private final Map<UUID,Player> players=new LinkedHashMap<>();private final Map<UUID,Integer> sizes=new HashMap<>();private int total;
    private final Map<String,ArrayDeque<Long>> rates=new LinkedHashMap<>();private final Map<String,Long> high=new LinkedHashMap<>();
    public NotificationInbox(Path dir,LongSupplier clock)throws IOException {
        this(dir,clock,null);
    }
    public NotificationInbox(Path dir,LongSupplier clock,StorageBudget budget)throws IOException {
        this.dir=dir;this.clock=clock;this.budget=budget;Files.createDirectories(dir);
        try(var files=Files.newDirectoryStream(dir,"*.json")){for(Path file:files){
            if(players.size()>=4096||Files.isSymbolicLink(file)||Files.size(file)>PLAYER_BYTES)throw new IOException("通知文件超额");
            try {String name=file.getFileName().toString();UUID id=UUID.fromString(name.substring(0,name.length()-5));
                if(!name.equals(id+".json"))throw new IllegalArgumentException("文件名无效");byte[] bytes=Files.readAllBytes(file);String json=new String(bytes,StandardCharsets.UTF_8);
                if(JsonScan.check(json,8)!=null)throw new IllegalArgumentException("通知存档格式无效");Player p=decode(JsonParser.parseString(json).getAsJsonObject());
                if((long)total+bytes.length>GLOBAL_BYTES)throw new IllegalArgumentException("通知全局超额");if(budget!=null)budget.restore("notification:"+id,usage(p));players.put(id,p);sizes.put(id,bytes.length);total+=bytes.length;
            }catch(RuntimeException bad){throw new IOException("通知存档损坏："+file,bad);}
        }}
    }
    public void sync(UUID id,Collection<String> installed,Collection<String> disabled)throws IOException {
        if(installed.size()>MAX_APPS||disabled.size()>MAX_APPS)throw new IllegalArgumentException("订阅超出 128 个脚本与两个内建提醒的上限");
        Set<String> apps=new LinkedHashSet<>(installed);for(String app:apps)appId(app);if(!apps.containsAll(disabled))throw new IllegalArgumentException("未安装的通知开关");
        Player p=player(id).copy();p.subscriptions.clear();p.subscriptions.addAll(apps);p.subscriptions.removeAll(disabled);
        p.notices.removeIf(n->!apps.contains(n.app())||n.expires()>0&&n.expires()<=clock.getAsLong());commit(id,p);
    }
    public boolean subscribed(UUID id,String app){return player(id).subscriptions.contains(app);}
    public boolean hasDedupe(UUID id,String app,String key){return list(id).stream().anyMatch(n->n.app().equals(app)&&key.equals(n.message().dedupeKey()));}
    public boolean broadcastAllowed(String app){return rate("broadcast:"+app,2,60_000);}
    public Notice post(UUID id,String app,NotificationMessage message,boolean broadcast)throws IOException {
        return post(id,app,message,broadcast,null);
    }
    public Priority reservePriority(String app,Priority requested){long now=clock.getAsLong();if(requested!=Priority.HIGH)return requested;high.entrySet().removeIf(e->now-e.getValue()>=3_600_000);if(high.containsKey(app)||high.size()>=512)return Priority.NORMAL;high.put(app,now);return Priority.HIGH;}
    public Notice post(UUID id,String app,NotificationMessage message,boolean broadcast,Priority broadcastPriority)throws IOException {
        appId(app);Player old=player(id);if(!old.subscriptions.contains(app))return null;
        if(!broadcast&&!rate("self:"+id+":"+app,10,60_000)||!rate("receive:"+id,30,60_000))return null;
        Player p=old.copy();long now=clock.getAsLong();p.notices.removeIf(n->n.expires()>0&&n.expires()<=now);
        if(message.dedupeKey()!=null)p.notices.removeIf(n->n.app().equals(app)&&message.dedupeKey().equals(n.message().dedupeKey()));
        while(p.notices.stream().filter(n->n.app().equals(app)).count()>=quotas.get().get("notifications.per_app_player")){Notice victim=p.notices.stream().filter(n->n.app().equals(app)&&n.read()).findFirst().orElse(null);if(victim==null)return null;p.notices.remove(victim);}
        if(p.notices.stream().map(Notice::app).distinct().filter(a->!a.equals(app)).count()>=MAX_APPS)return null;
        Priority priority=broadcastPriority==null?reservePriority(app,message.priority()):broadcastPriority;
        long seq=Math.addExact(p.next,1);p.next=seq;Notice n=new Notice(seq,app,message,now,message.expiresAfterMs()==0?0:Math.addExact(now,message.expiresAfterMs()),priority,false);p.notices.add(n);commit(id,p);return n;
    }
    public List<Notice> list(UUID id){long now=clock.getAsLong();return player(id).notices.stream().filter(n->n.expires()==0||n.expires()>now).sorted(Comparator.comparingLong(Notice::id).reversed()).toList();}
    public Map<String,Integer> unread(UUID id){Map<String,Integer> count=new LinkedHashMap<>();for(Notice n:list(id))if(!n.read())count.merge(n.app(),1,Integer::sum);return Collections.unmodifiableMap(count);}
    public void read(UUID id,long number)throws IOException {Player p=player(id).copy();boolean changed=false;for(int i=0;i<p.notices.size();i++){Notice n=p.notices.get(i);if(n.id()==number&&!n.read()){p.notices.set(i,new Notice(n.id(),n.app(),n.message(),n.created(),n.expires(),n.priority(),true));changed=true;}}if(changed)commit(id,p);}
    public static JsonObject json(Notice n){JsonObject o=JsonParser.parseString(n.message().json()).getAsJsonObject();o.addProperty("topic",n.message().topic());o.addProperty("id",Long.toString(n.id()));o.addProperty("app",n.app());o.addProperty("created",Long.toString(n.created()));o.addProperty("expires",Long.toString(n.expires()));o.addProperty("priority",n.priority().name());o.addProperty("read",n.read());return o;}
    private boolean rate(String key,int maximum,long window){long now=clock.getAsLong();rates.entrySet().removeIf(e->e.getValue().isEmpty()||now-e.getValue().peekLast()>=window);if(!rates.containsKey(key)&&rates.size()>=4096)return false;ArrayDeque<Long> q=rates.computeIfAbsent(key,k->new ArrayDeque<>());while(!q.isEmpty()&&now-q.peekFirst()>=window)q.removeFirst();if(q.size()>=maximum)return false;q.addLast(now);return true;}
    private Player player(UUID id){return players.getOrDefault(id,new Player());}
    private void commit(UUID id,Player p)throws IOException {byte[] bytes=encode(p).toString().getBytes(StandardCharsets.UTF_8);int previous=sizes.getOrDefault(id,0);
        if(bytes.length>PLAYER_BYTES||(long)total-previous+bytes.length>GLOBAL_BYTES||!players.containsKey(id)&&players.size()>=4096)throw new IOException("通知存储配额已满");
        if(budget==null){StoreRepository.atomic(dir.resolve(id+".json"),bytes);install(id,p,bytes.length,previous);}
        else try{budget.replace("notification:"+id,usage(p),()->{try{StoreRepository.atomic(dir.resolve(id+".json"),bytes);}catch(IOException bad){throw new java.io.UncheckedIOException(bad);}install(id,p,bytes.length,previous);});}
        catch(java.io.UncheckedIOException bad){throw bad.getCause();}catch(com.november.mcphone.core.script.server.store.StoreQuota.QuotaExceeded full){throw new IOException("通知和玩家数据的合并配额已满",full);}
    }
    private void install(UUID id,Player p,int bytes,int previous){players.put(id,p);sizes.put(id,bytes);total=total-previous+bytes;}
    private static Map<String,Long> usage(Player p){Map<String,Long> out=new LinkedHashMap<>();for(Notice n:p.notices)out.merge(n.app(),(long)json(n).toString().getBytes(StandardCharsets.UTF_8).length,Long::sum);out.merge("mcphone:notifications",(long)encode(p).getAsJsonArray("subscriptions").toString().getBytes(StandardCharsets.UTF_8).length+64,Long::sum);return Map.copyOf(out);}
    private static JsonObject encode(Player p){JsonObject o=new JsonObject();o.addProperty("format",1);o.addProperty("next",Long.toString(p.next));JsonArray subscriptions=new JsonArray();p.subscriptions.forEach(subscriptions::add);o.add("subscriptions",subscriptions);JsonArray rows=new JsonArray();p.notices.forEach(n->rows.add(json(n)));o.add("notices",rows);return o;}
    private static Player decode(JsonObject o){if(!o.keySet().equals(Set.of("format","next","subscriptions","notices"))||o.get("format").getAsInt()!=1)throw new IllegalArgumentException("通知存档版本无效");Player p=new Player();p.next=new java.math.BigDecimal(o.get("next").getAsString()).longValueExact();if(p.next<0)throw new IllegalArgumentException("通知序号无效");for(JsonElement e:o.getAsJsonArray("subscriptions")){String app=e.getAsString();appId(app);if(!p.subscriptions.add(app)||p.subscriptions.size()>MAX_APPS)throw new IllegalArgumentException("通知订阅无效");}
        Set<Long> ids=new HashSet<>();Map<String,Integer> counts=new HashMap<>();for(JsonElement e:o.getAsJsonArray("notices")){JsonObject n=e.getAsJsonObject();String app=n.get("app").getAsString();appId(app);long id=number(n,"id"),created=number(n,"created"),expires=number(n,"expires");if(id<1||id>p.next||!ids.add(id)||created<0||expires<0||counts.merge(app,1,Integer::sum)>128||counts.size()>MAX_APPS)throw new IllegalArgumentException("通知条目无效");JsonObject m=n.deepCopy();for(String key:List.of("id","app","created","expires","read","topic"))m.remove(key);NotificationMessage message=NotificationMessage.parse(n.get("topic").getAsString(),m.toString());p.notices.add(new Notice(id,app,message,created,expires,Priority.valueOf(n.get("priority").getAsString()),n.get("read").getAsBoolean()));}return p;
    }
    private static long number(JsonObject o,String k){return new java.math.BigDecimal(o.get(k).getAsString()).longValueExact();}
    private static void appId(String app){if(app.length()>64||!app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("通知 App 无效");}
}
