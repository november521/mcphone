package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 服主配额快照；包清单不能提高任何值，未知键与小数都拒绝。 */
public record QuotaConfig(Map<String,Long> values) {
    public record Spec(String key,long initial,long minimum,long maximum,String unit) { }
    public static final List<Spec> SPECS=List.of(
        new Spec("escrow.per_player",27,0,108,"slots"),new Spec("escrow.per_app",9,0,108,"slots"),
        new Spec("mailbox.per_player",27,0,54,"slots"),new Spec("kv.per_player_app",8192,0,65536,"bytes"),new Spec("kv.keys",64,0,64,"keys"),
        new Spec("kv.per_app",4194304,0,33554432,"bytes"),new Spec("sealed.per_player_app",4096,0,16384,"bytes"),new Spec("sealed.keys",16,0,16,"keys"),
        new Spec("apps.per_player",32,8,128,"apps"),new Spec("package.compressed",262144,65536,1048576,"bytes"),new Spec("package.expanded",1048576,262144,4194304,"bytes"),
        new Spec("upload.server",4194304,1048576,33554432,"bytes"),new Spec("upload.per_player",1,1,1,"sessions"),
        new Spec("net.cache",67108864,0,536870912,"bytes"),new Spec("net.ttl_hours",6,1,6,"hours"),
        new Spec("audit.per_day",67108864,1048576,67108864,"bytes"),new Spec("audit.days",90,7,365,"days"),
        new Spec("ledger.per_player",256,256,256,"records"),new Spec("ledger.hours",24,1,72,"hours"),
        new Spec("notifications.per_app_player",32,8,128,"records"),new Spec("textures.per_app",16,16,16,"images"),new Spec("textures.per_image",65536,65536,65536,"bytes"),
        new Spec("data.per_player",262144,0,1048576,"bytes"),new Spec("data.server",67108864,0,67108864,"bytes"),new Spec("items.per_player",54,0,216,"slots"));
    public static final QuotaConfig DEFAULT=new QuotaConfig(defaults());
    public QuotaConfig {Map<String,Long> checked=defaults();for(var entry:values.entrySet()){Spec spec=SPECS.stream().filter(s->s.key().equals(entry.getKey())).findFirst().orElseThrow(()->new IllegalArgumentException("未知配额："+entry.getKey()));if(entry.getValue()<spec.minimum()||entry.getValue()>spec.maximum())throw new IllegalArgumentException(spec.key()+" 必须在 "+spec.minimum()+".."+spec.maximum()+" "+spec.unit());checked.put(entry.getKey(),entry.getValue());}values=Map.copyOf(checked);}
    private static Map<String,Long> defaults(){Map<String,Long> values=new LinkedHashMap<>();SPECS.forEach(s->values.put(s.key(),s.initial()));return values;}
    public long get(String key){Long value=values.get(key);if(value==null)throw new IllegalArgumentException("未知配额："+key);return value;}
    public QuotaConfig with(String key,long value){Map<String,Long> next=new LinkedHashMap<>(values);next.put(key,value);return new QuotaConfig(next);}
    public JsonObject json(){JsonObject root=new JsonObject(),limits=new JsonObject();root.addProperty("format",1);SPECS.forEach(s->limits.addProperty(s.key(),get(s.key())));root.add("limits",limits);return root;}
    public static QuotaConfig parse(String json){if(json.getBytes(StandardCharsets.UTF_8).length>16384||JsonScan.check(json,3)!=null)throw new IllegalArgumentException("配额文件无效");JsonObject root=JsonParser.parseString(json).getAsJsonObject();if(!root.keySet().equals(Set.of("format","limits"))||!root.get("format").isJsonPrimitive()||!root.getAsJsonPrimitive("format").isNumber())throw new IllegalArgumentException("配额版本无效");Map<String,Long> values=new LinkedHashMap<>();try{if(root.get("format").getAsBigDecimal().intValueExact()!=1)throw new IllegalArgumentException("配额版本无效");for(var e:root.getAsJsonObject("limits").entrySet()){if(!e.getValue().isJsonPrimitive()||!e.getValue().getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("配额必须为整数");values.put(e.getKey(),e.getValue().getAsBigDecimal().longValueExact());}}catch(ArithmeticException bad){throw new IllegalArgumentException("配额必须为整数",bad);}return new QuotaConfig(values);}
}
