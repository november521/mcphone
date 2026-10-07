package com.november.mcphone.core.script.server;
import com.google.gson.*;
import java.util.*;
import java.util.function.LongSupplier;

/** 订阅分块只在完整批次到齐后替换。玩家与连接分别绑定，旧服碎片不能拼进新连接。 */
public final class NotificationSync {
    // 128 个脚本 App，另留两个宿主收件箱/托管到期提醒的订阅，不能挤掉最后两个脚本。
    public static final int CHUNK=16,MAX_APPS=130;
    public record Batch(List<String> apps,List<String> disabled){}
    private static final class Pending {
        final String token;final long epoch;final int total;final long at;
        final List<String> apps=new ArrayList<>(),disabled=new ArrayList<>();
        Pending(String token,long epoch,int total,long at){this.token=token;this.epoch=epoch;this.total=total;this.at=at;}
    }
    private final Map<UUID,Pending> pending=new LinkedHashMap<>();private final LongSupplier clock;
    public NotificationSync(LongSupplier clock){this.clock=clock;}
    public Batch accept(UUID player,long epoch,JsonObject args){
        long now=clock.getAsLong();pending.entrySet().removeIf(e->now-e.getValue().at>60000);
        if(!args.keySet().equals(Set.of("token","offset","total","apps","disabled"))||epoch==0)throw new IllegalArgumentException("订阅分块字段无效");
        String token=args.get("token").getAsString();int offset=args.get("offset").getAsBigDecimal().intValueExact(),total=args.get("total").getAsBigDecimal().intValueExact();
        List<String> apps=strings(args,"apps"),disabled=strings(args,"disabled");
        if(!token.matches("[0-9a-f]{32}")||total<0||total>MAX_APPS||offset<0||offset>total||apps.size()+offset>total||apps.isEmpty()&&total!=0||!apps.containsAll(disabled))throw new IllegalArgumentException("订阅分块范围无效");
        Pending part=pending.get(player);
        if(offset==0){if(pending.size()>=64&&!pending.containsKey(player))throw new IllegalArgumentException("订阅同步繁忙");part=new Pending(token,epoch,total,now);pending.put(player,part);}
        if(part==null||part.epoch!=epoch||!part.token.equals(token)||part.total!=total||part.apps.size()!=offset)throw new IllegalArgumentException("订阅分块关联无效");
        for(String app:apps)if(part.apps.contains(app))throw new IllegalArgumentException("订阅 App 重复");
        part.apps.addAll(apps);part.disabled.addAll(disabled);
        if(part.apps.size()!=total)return null;
        pending.remove(player);return new Batch(List.copyOf(part.apps),List.copyOf(part.disabled));
    }
    private static List<String> strings(JsonObject args,String key){List<String> out=new ArrayList<>();for(var e:args.getAsJsonArray(key)){String app=e.getAsString();if(out.size()>=CHUNK||app.length()>64||!app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||out.contains(app))throw new IllegalArgumentException("订阅 App 无效");out.add(app);}return out;}
    public void forget(UUID player){pending.remove(player);}
}
