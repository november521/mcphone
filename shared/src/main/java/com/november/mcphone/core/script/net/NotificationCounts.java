package com.november.mcphone.core.script.net;
import com.google.gson.*;
import java.util.*;

/** 角标快照固定每片 16 个 App，128 个脚本另加两个宿主提醒；到齐才换显示。 */
public final class NotificationCounts {
    private NotificationCounts(){}
    /** 旧客户端只认整张快照；未协商分块时仍保持旧的最多 32 项格式。 */
    public static List<JsonObject> snapshots(Map<String,Integer> values,long revision,boolean chunks){
        if(chunks)return parts(values,revision);
        if(revision<1)throw new IllegalArgumentException("角标序号无效");
        JsonObject out=new JsonObject(),unread=new JsonObject();out.addProperty("revision",Long.toString(revision));
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(32).forEach(e->unread.addProperty(e.getKey(),e.getValue()));
        out.add("unread",unread);return List.of(out);
    }
    public static List<JsonObject> parts(Map<String,Integer> values,long revision){
        if(values.size()>130||revision<1)throw new IllegalArgumentException("角标快照超额");
        List<Map.Entry<String,Integer>> rows=new ArrayList<>(values.entrySet());rows.sort(Map.Entry.comparingByKey());
        int count=Math.max(1,(rows.size()+15)/16);List<JsonObject> out=new ArrayList<>();
        for(int i=0;i<count;i++){JsonObject part=new JsonObject(),unread=new JsonObject();part.addProperty("revision",Long.toString(revision));part.addProperty("countsPart",i);part.addProperty("countsTotal",count);for(int j=i*16;j<Math.min(rows.size(),i*16+16);j++)unread.addProperty(rows.get(j).getKey(),rows.get(j).getValue());part.add("unread",unread);out.add(part);}return List.copyOf(out);
    }
    public static final class Accumulator {
        private long complete,pending;
        private int total;
        private final Map<Integer,Map<String,Integer>> pieces=new HashMap<>();
        public void clear(){complete=0;pending=0;pieces.clear();}
        public long revision(){return complete;}
        public Map<String,Integer> accept(JsonObject data){
            if(!data.has("unread"))return null;
            long revision=data.get("revision").getAsBigDecimal().longValueExact();if(revision<=complete)return null;
            int part=data.has("countsPart")?data.get("countsPart").getAsBigDecimal().intValueExact():0;
            int count=data.has("countsTotal")?data.get("countsTotal").getAsBigDecimal().intValueExact():1;
            JsonObject rows=data.getAsJsonObject("unread");
            if(revision<1||count<1||count>9||part<0||part>=count||rows.size()>(data.has("countsPart")?16:32))throw new IllegalArgumentException("角标分块范围无效");
            if(revision<pending)return null;
            Map<String,Integer> values=new LinkedHashMap<>();for(String app:rows.keySet()){int n=rows.get(app).getAsBigDecimal().intValueExact();if(app.length()>64||!app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||n<0||n>128)throw new IllegalArgumentException("通知角标无效");values.put(app,n);}
            if(revision>pending){pieces.clear();pending=revision;total=count;}
            if(total!=count)throw new IllegalArgumentException("角标总片数冲突");
            Map<String,Integer> previous=pieces.putIfAbsent(part,Map.copyOf(values));if(previous!=null&&!previous.equals(values))throw new IllegalArgumentException("角标同片内容冲突");
            if(pieces.size()!=total)return null;
            Map<String,Integer> merged=new LinkedHashMap<>();for(int i=0;i<total;i++)for(var row:pieces.get(i).entrySet())if(merged.putIfAbsent(row.getKey(),row.getValue())!=null)throw new IllegalArgumentException("角标跨片 App 重复");
            if(merged.size()>130)throw new IllegalArgumentException("角标 App 总量超额");complete=revision;pieces.clear();return Map.copyOf(merged);
        }
    }
}
