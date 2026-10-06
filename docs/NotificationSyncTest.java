package com.november.mcphone.core.script.server;
import com.google.gson.*;
import com.november.mcphone.core.script.net.NotificationCounts;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public final class NotificationSyncTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    private static void reject(Runnable action,String why){try{action.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
    private static JsonObject part(String token,int offset,int total){JsonObject o=new JsonObject();o.addProperty("token",token);o.addProperty("offset",offset);o.addProperty("total",total);JsonArray apps=new JsonArray(),disabled=new JsonArray();for(int i=offset;i<Math.min(total,offset+16);i++){apps.add("test:app"+i);if(i%2==0)disabled.add("test:app"+i);}o.add("apps",apps);o.add("disabled",disabled);return o;}
    public static void main(String[]args){
        check(HostControls.ACTIONS.contains("notify.sync.part"),"分块订阅必须能通过真正的宿主路由入口");
        AtomicLong now=new AtomicLong();var sync=new NotificationSync(now::get);UUID owner=UUID.randomUUID(),other=UUID.randomUUID();String token="a".repeat(32);
        NotificationSync.Batch batch=null;for(int offset=0;offset<128;offset+=16){batch=sync.accept(owner,1,part(token,offset,128));if(offset<112)check(batch==null,"半成品不可见");}
        check(batch.apps().size()==128&&batch.disabled().size()==64,"128 App 完整订阅与开关保持");
        sync.accept(owner,2,part(token,0,128));reject(()->sync.accept(other,2,part(token,16,128)),"别的玩家不能续批");reject(()->sync.accept(owner,3,part(token,16,128)),"换服不能续批");reject(()->sync.accept(owner,2,part("b".repeat(32),16,128)),"旧令牌不能续批");reject(()->sync.accept(owner,2,part(token,32,128)),"错序拒绝");
        now.addAndGet(60001);reject(()->sync.accept(owner,2,part(token,16,128)),"过期批次不能续接");
        check(sync.accept(owner,4,part(token,0,0)).apps().isEmpty(),"卸载全部可原子清空");
        for(int offset=0;offset<130;offset+=16)batch=sync.accept(owner,5,part(token,offset,130));check(batch.apps().size()==130,"128 个脚本和两个宿主提醒都能订阅");
        reject(()->sync.accept(owner,5,part(token,0,131)),"总量硬上限拒绝");
        Map<String,Integer> counts=new LinkedHashMap<>();for(int i=0;i<128;i++)counts.put("test:"+"a".repeat(50)+i,128);
        var pieces=NotificationCounts.parts(counts,1);check(pieces.size()==8,"角标按固定上限分块");
        var accumulator=new NotificationCounts.Accumulator();Map<String,Integer> complete=null;
        for(int i=7;i>=0;i--){check(pieces.get(i).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<1536,"最坏 ID 长度也保留通知正文空间");complete=accumulator.accept(pieces.get(i));if(i>0)check(complete==null,"角标片段不能覆盖已有显示");}
        check(complete.equals(counts)&&accumulator.revision()==1,"乱序角标完整恢复");check(accumulator.accept(pieces.get(0))==null,"旧快照不会再次覆盖");
        var newer=NotificationCounts.parts(counts,3);check(accumulator.accept(newer.get(0))==null,"新快照只暂存");check(accumulator.accept(NotificationCounts.parts(Map.of("test:old",1),2).get(0))==null,"暂存期间旧版本忽略");
        JsonObject fork=newer.get(0).deepCopy();fork.getAsJsonObject("unread").addProperty(counts.keySet().iterator().next(),2);reject(()->accumulator.accept(fork),"同快照同片换内容拒绝");
        accumulator.clear();check(accumulator.accept(NotificationCounts.parts(Map.of(),1).get(0)).isEmpty(),"换服清缓存后接受新服低序号");
        Map<String,Integer> oldCounts=new LinkedHashMap<>();for(int i=0;i<32;i++)oldCounts.put("test:old"+i,i);
        var legacy=NotificationCounts.snapshots(oldCounts,2,false);check(legacy.size()==1&&!legacy.get(0).has("countsPart"),"旧客户端仍收到一张快照");
        check(new NotificationCounts.Accumulator().accept(legacy.get(0)).equals(oldCounts),"旧格式保留后 16 个 App 的角标");
        check(NotificationCounts.snapshots(counts,3,true).size()==8,"只有协商后才发送多片快照");
        System.out.println("NotificationSyncTest: "+checks+" passed");
    }
}
