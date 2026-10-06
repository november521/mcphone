package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 礼包模板与逐次前后审计共用一次原子写入；只读服务端物品序列化结果。 */
public final class GiftDefinitions {
    public record Definition(String id,String label,List<String> items,long start,long end,int total,long cooldown,String predicate,String loot) {
        public Definition(String id,String label,List<String> items,long start,long end,int total,long cooldown,String predicate){this(id,label,items,start,end,total,cooldown,predicate,"");}
        public Definition {
            if(loot==null||!loot.isEmpty()&&(!loot.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||loot.length()>128||!items.isEmpty()))throw new IllegalArgumentException("战利品表与物品模板只能选一种");
            if(!id.matches("[a-z0-9_.-]{1,64}") || label.isEmpty() || label.length()>64 || label.contains("|") || label.chars().anyMatch(Character::isISOControl) || items.size()>27
                    || items.stream().anyMatch(s->s.length()>65536 || JsonScan.check(s,16)!=null)
                    || start<0 || end<start || total<1 || cooldown<0 || !predicate.isEmpty() && !predicate.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("礼包定义无效");
            items=List.copyOf(items);
        }
        public ActionGuards guards() {
            List<ActionGuards.Rule> r=new ArrayList<>();
            r.add(new ActionGuards.Rule("window","global",label,start,end,""));
            r.add(new ActionGuards.Rule("limit","global",label,total,0,""));
            r.add(new ActionGuards.Rule(cooldown==0?"once":"cooldown","player",label,cooldown==0?Long.MAX_VALUE:cooldown,0,""));
            if(!predicate.isEmpty()) r.add(new ActionGuards.Rule("predicate","player",label,0,0,predicate));
            return new ActionGuards(r);
        }
    }
    private final Path file;
    private final Map<String,Definition> definitions=new LinkedHashMap<>();
    private JsonArray audit=new JsonArray();
    private AuditLog serverAudit;
    public GiftDefinitions audit(AuditLog log){serverAudit=log;return this;}
    private static final Gson JSON=new Gson();
    public GiftDefinitions(Path file) throws java.io.IOException {
        this.file=file;
        if(Files.exists(file)) {
            if(Files.size(file)>8*1024*1024) throw new IllegalArgumentException("礼包库超额");
            String raw=Files.readString(file); if(JsonScan.check(raw,20)!=null) throw new IllegalArgumentException("礼包库损坏");
            JsonObject root=JsonParser.parseString(raw).getAsJsonObject();
            for(JsonElement row:root.getAsJsonArray("definitions")) {
                Definition d=decode(row.getAsJsonObject()); if(definitions.putIfAbsent(d.id(),d)!=null || definitions.size()>64) throw new IllegalArgumentException("礼包重复或超额");
            }
            audit=root.getAsJsonArray("audit"); if(audit.size()>256) throw new IllegalArgumentException("礼包审计超额");
        }
    }
    public Definition get(String id) { return definitions.get(id); }
    public List<Definition> all() { return List.copyOf(definitions.values()); }
    public void save(Definition definition,UUID editor,String name) throws java.io.IOException {
        if(!definitions.containsKey(definition.id()) && definitions.size()>=64) throw new IllegalArgumentException("礼包数已满");
        Map<String,Definition> next=new LinkedHashMap<>(definitions); next.put(definition.id(),definition);
        JsonArray changed=audit.deepCopy(); JsonObject event=new JsonObject(); event.addProperty("at",System.currentTimeMillis()); event.addProperty("operator",editor.toString()); event.addProperty("name",name);
        event.addProperty("gift",definition.id()); event.add("before",JSON.toJsonTree(definitions.get(definition.id()))); event.add("after",JSON.toJsonTree(definition)); changed.add(event);
        if(serverAudit!=null){serverAudit.append(editor,"gift.save",definition.id(),event.get("before"),event.get("after"));while(changed.size()>1&&(changed.size()>256||changed.toString().getBytes(StandardCharsets.UTF_8).length>4*1024*1024))changed.remove(0);}
        if(changed.size()>256 || changed.toString().getBytes(StandardCharsets.UTF_8).length>4*1024*1024) {
            // 审计不静默丢弃：满批先归档，归档失败则拒绝改模板。
            ScriptStateData.atomicWrite(file.resolveSibling("audit-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".json"),changed.toString().getBytes(StandardCharsets.UTF_8)); changed=new JsonArray();
        }
        JsonObject root=new JsonObject(); root.add("definitions",JSON.toJsonTree(next.values())); root.add("audit",changed);
        byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8); if(bytes.length>8*1024*1024) throw new IllegalArgumentException("礼包库超过 8 MiB");
        ScriptStateData.atomicWrite(file,bytes); definitions.clear(); definitions.putAll(next); audit=changed;
    }
    private static Definition decode(JsonObject o) {
        List<String> items=new ArrayList<>(); for(var i:o.getAsJsonArray("items")) items.add(i.getAsString());
        return new Definition(o.get("id").getAsString(),o.get("label").getAsString(),items,o.get("start").getAsBigDecimal().longValueExact(),o.get("end").getAsBigDecimal().longValueExact(),o.get("total").getAsBigDecimal().intValueExact(),o.get("cooldown").getAsBigDecimal().longValueExact(),o.get("predicate").getAsString(),o.has("loot")?o.get("loot").getAsString():"");
    }
}
