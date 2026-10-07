package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.core.script.pkg.AppPackage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 通知服务只在主线程工作；订阅与已读存档不会接受客户端指定的玩家身份。 */
public final class ServerNotifications {
    private final MinecraftServer server;private final NotificationInbox inbox;
    private final Map<String,Set<String>> keys=new LinkedHashMap<>();
    private final Map<UUID,Map<String,Integer>> lastCounts=new HashMap<>();private final Map<String,Long> dropped=new LinkedHashMap<>();
    private final NotificationSync subscriptionSync=new NotificationSync(System::currentTimeMillis);
    private final Set<UUID> chunkClients=new HashSet<>();
    public ServerNotifications(MinecraftServer server)throws IOException {this(server,null);}
    public ServerNotifications(MinecraftServer server,StorageBudget budget)throws IOException {this.server=server;inbox=new NotificationInbox(server.getWorldPath(LevelResource.ROOT).resolve("mcphone/notifications"),System::currentTimeMillis,budget);}
    public ServerNotifications quotas(java.util.function.Supplier<QuotaConfig> config){inbox.quotas(config);return this;}
    public void escrowWarning(UUID player,String id,long remains){String app="mcphone:item_escrow",dedupe="expiry:"+id;if(!inbox.subscribed(player,app)||inbox.hasDedupe(player,app,dedupe))return;keys.put(app,Set.of("mcphone.escrow.expiry_title","mcphone.escrow.expiry_body"));post(player,app,new NotificationMessage("expiry","mcphone.escrow.expiry_title",List.of(),"mcphone.escrow.expiry_body",List.of(),com.november.mcphone.api.sdk.notify.Priority.NORMAL,dedupe,Math.max(1,remains)),false);}
    public void mailboxWarning(UUID player,String id,long remains){String app="mcphone:mailbox",dedupe="expiry:"+id;if(!inbox.subscribed(player,app)||inbox.hasDedupe(player,app,dedupe))return;keys.put(app,Set.of("mcphone.mailbox.expiry_title","mcphone.mailbox.expiry_body"));post(player,app,new NotificationMessage("expiry","mcphone.mailbox.expiry_title",List.of(),"mcphone.mailbox.expiry_body",List.of(),com.november.mcphone.api.sdk.notify.Priority.NORMAL,dedupe,Math.max(1,remains)),false);}
    public void sweep(){Set<UUID> online=new HashSet<>();for(var player:server.getPlayerList().getPlayers()){UUID uuid=player.getUUID();online.add(uuid);Map<String,Integer> count=inbox.unread(uuid);if(!count.equals(lastCounts.get(uuid)))push(uuid,null);}lastCounts.keySet().retainAll(online);}
    private void warn(String app){long now=System.currentTimeMillis();dropped.entrySet().removeIf(e->now-e.getValue()>=60000);if(dropped.containsKey(app)||dropped.size()>=64)return;dropped.put(app,now);MCphone.LOGGER.warn("[MCphone] 通知超出频率或空间限制，已丢弃 app={}（同 App 日志每分钟合并一次）",app);}
    public void register(AppPackage pkg){Set<String> declared=new HashSet<>();for(String path:pkg.paths())if(path.startsWith("lang/")&&path.endsWith(".json")){try{JsonObject lang=JsonParser.parseString(new String(pkg.entry(path),StandardCharsets.UTF_8)).getAsJsonObject();for(String key:lang.keySet())if(lang.get(key).isJsonPrimitive()&&lang.get(key).getAsJsonPrimitive().isString())declared.add(key);}catch(RuntimeException bad){throw new IllegalArgumentException("通知语言文件无效",bad);}}keys.put(pkg.manifest().id(),Set.copyOf(declared));}
    public void validate(String app,NotificationMessage message){Set<String> declared=keys.getOrDefault(app,Set.of());if(!declared.contains(message.titleKey())||message.bodyKey()!=null&&!declared.contains(message.bodyKey()))throw new IllegalArgumentException("通知键必须在这个 App 的 lang 文件中声明");}
    public void post(UUID sender,String app,NotificationMessage message,boolean subscribers){
        validate(app,message);if(subscribers&&!inbox.broadcastAllowed(app)){warn(app);return;}
        var priority=subscribers?inbox.reservePriority(app,message.priority()):null;
        Collection<UUID> targets=subscribers?server.getPlayerList().getPlayers().stream().map(p->p.getUUID()).toList():List.of(sender);
        for(UUID target:targets)try{if(!inbox.subscribed(target,app))continue;NotificationInbox.Notice n=inbox.post(target,app,message,subscribers,priority);if(n!=null)push(target,n);else warn(app);}
        catch(IOException bad){MCphone.LOGGER.warn("[MCphone] 通知投递因存储配额或磁盘故障被丢弃 app={} recipient={}",app,target);}
    }
    public ScriptRpcResult handle(ScriptRpc rpc,PlayerSnapshot player,JsonObject args)throws IOException {
        JsonObject result=new JsonObject();UUID uuid=player.uuid();
        switch(rpc.actionId()) {
            case "notify.sync" -> {if(!args.keySet().equals(Set.of("apps","disabled")))throw new IllegalArgumentException("通知订阅字段无效");inbox.sync(uuid,strings(args,"apps"),strings(args,"disabled"));chunkClients.remove(uuid);counts(result,uuid);}
            case "notify.sync.part" -> {var batch=subscriptionSync.accept(uuid,rpc.connectionEpoch(),args);result.addProperty("complete",batch!=null);result.addProperty("next",args.get("offset").getAsInt()+args.getAsJsonArray("apps").size());if(batch!=null){inbox.sync(uuid,batch.apps(),batch.disabled());chunkClients.add(uuid);counts(result,uuid);}}
            case "notify.list" -> {
                if(!args.keySet().equals(Set.of("offset")))throw new IllegalArgumentException("通知分页字段无效");int offset=args.get("offset").getAsBigDecimal().intValueExact();List<NotificationInbox.Notice> rows=inbox.list(uuid);if(offset<0||offset>4096)throw new IllegalArgumentException("通知分页无效");
                JsonArray items=new JsonArray();result.add("items",items);result.addProperty("total",rows.size());counts(result,uuid);
                // 旧格式的 32 个最长 App ID 加一条通知可能超过 4 KiB。角标另推完整快照，正文页不丢记录。
                if(offset<rows.size()){JsonObject trial=result.deepCopy();trial.getAsJsonArray("items").add(NotificationInbox.json(rows.get(offset)));if(trial.toString().getBytes(StandardCharsets.UTF_8).length>ScriptProtocol.DATA_MAX){push(uuid,null);result.remove("unread");result.remove("countsPart");result.remove("countsTotal");}}
                for(int i=offset;i<Math.min(rows.size(),offset+2);i++){items.add(NotificationInbox.json(rows.get(i)));result.addProperty("next",i+1);if(result.toString().getBytes(StandardCharsets.UTF_8).length>ScriptProtocol.DATA_MAX){items.remove(items.size()-1);break;}}
                result.addProperty("next",offset+items.size());
            }
            case "notify.read" -> {if(!args.keySet().equals(Set.of("id")))throw new IllegalArgumentException("通知标识无效");long id=new java.math.BigDecimal(args.get("id").getAsString()).longValueExact();if(id<1)throw new IllegalArgumentException("通知标识无效");inbox.read(uuid,id);counts(result,uuid);}
            default -> throw new IllegalArgumentException("通知操作无效");
        }
        byte[] data=result.toString().getBytes(StandardCharsets.UTF_8);if(data.length>ScriptProtocol.DATA_MAX)throw new IOException("通知响应超额");return ScriptRpcResult.ok(rpc.requestId(),data,0);
    }
    private void push(UUID uuid,NotificationInbox.Notice notice){Map<String,Integer> values=inbox.unread(uuid);List<JsonObject> parts=NotificationCounts.snapshots(values,Seq.next(),chunkClients.contains(uuid));boolean separate=false;for(int i=0;i<parts.size();i++){JsonObject part=parts.get(i);if(i==0&&notice!=null){part.add("notice",NotificationInbox.json(notice));if(part.toString().getBytes(StandardCharsets.UTF_8).length>ScriptProtocol.DATA_MAX-180){part.remove("notice");separate=true;}}pushPart(uuid,part);}if(separate){JsonObject only=new JsonObject();only.addProperty("revision",Long.toString(Seq.next()));only.add("notice",NotificationInbox.json(notice));pushPart(uuid,only);}lastCounts.put(uuid,values);}
    private void pushPart(UUID uuid,JsonObject data){var player=server.getPlayerList().getPlayer(uuid);ScriptHost host=ScriptHost.current();if(player==null||host==null||host.epoch(uuid)==0)return;data=data.deepCopy();data.addProperty("serverId",host.serverId().toString());data.addProperty("epoch",Long.toString(host.epoch(uuid)));byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>ScriptProtocol.DATA_MAX)throw new IllegalArgumentException("通知分块响应超额");ScriptPushHandler.push(player,new ScriptPush(ScriptProtocol.HOST_APP_ID,"mcphone:notify.notice",bytes,Long.parseLong(data.get("revision").getAsString())));}
    private void counts(JsonObject out,UUID uuid){Map<String,Integer> values=inbox.unread(uuid);List<JsonObject> parts=NotificationCounts.snapshots(values,Seq.next(),chunkClients.contains(uuid));parts.get(0).entrySet().forEach(e->out.add(e.getKey(),e.getValue()));if(parts.size()>1)for(JsonObject part:parts)pushPart(uuid,part);lastCounts.put(uuid,values);}
    public void forget(UUID player){subscriptionSync.forget(player);lastCounts.remove(player);chunkClients.remove(player);}
    private static List<String> strings(JsonObject args,String key){List<String> list=new ArrayList<>();for(JsonElement e:args.getAsJsonArray(key)){if(list.size()>=32)throw new IllegalArgumentException("通知订阅超额");list.add(e.getAsString());}return list;}
}
