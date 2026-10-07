package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.api.sdk.notify.*;
import com.november.mcphone.core.script.JsonScan;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 通知只携带本地化键；时间与 App 身份由宿主填，脚本不能伪造系统来源。 */
public record NotificationMessage(String topic,String titleKey,List<String> titleArgs,String bodyKey,List<String> bodyArgs,
                                  Priority priority,String dedupeKey,long expiresAfterMs) {
    public NotificationMessage {
        key(topic,64);key(titleKey,128);if(bodyKey!=null)key(bodyKey,128);if(dedupeKey!=null)key(dedupeKey,64);
        titleArgs=args(titleArgs);bodyArgs=args(bodyArgs);Objects.requireNonNull(priority);
        if(expiresAfterMs<0||expiresAfterMs>30L*86_400_000)throw new IllegalArgumentException("通知有效期超出 30 天");
    }
    public static NotificationMessage parse(String topic,String json) {
        if(json.getBytes(StandardCharsets.UTF_8).length>1536||JsonScan.check(json,4)!=null)throw new IllegalArgumentException("通知内容超额");
        JsonObject o=JsonParser.parseString(json).getAsJsonObject();
        if(!Set.of("titleKey","titleArgs","bodyKey","bodyArgs","priority","dedupeKey","expiresAfterMs").containsAll(o.keySet()))throw new IllegalArgumentException("通知字段无效");
        return new NotificationMessage(topic,o.get("titleKey").getAsString(),strings(o,"titleArgs"),text(o,"bodyKey"),strings(o,"bodyArgs"),
                o.has("priority")?Priority.valueOf(o.get("priority").getAsString().toUpperCase(Locale.ROOT)):Priority.NORMAL,text(o,"dedupeKey"),
                o.has("expiresAfterMs")?exact(o.get("expiresAfterMs")):0);
    }
    public String json() {
        JsonObject o=new JsonObject();o.addProperty("titleKey",titleKey);o.add("titleArgs",array(titleArgs));
        if(bodyKey!=null)o.addProperty("bodyKey",bodyKey);o.add("bodyArgs",array(bodyArgs));o.addProperty("priority",priority.name());
        if(dedupeKey!=null)o.addProperty("dedupeKey",dedupeKey);o.addProperty("expiresAfterMs",expiresAfterMs);return o.toString();
    }
    private static JsonArray array(List<String> values){JsonArray a=new JsonArray();values.forEach(a::add);return a;}
    private static long exact(JsonElement e){try{return e.getAsBigDecimal().longValueExact();}catch(ArithmeticException bad){throw new IllegalArgumentException("通知有效期必须为整数",bad);}}
    private static String text(JsonObject o,String k){return !o.has(k)||o.get(k).isJsonNull()?null:o.get(k).getAsString();}
    private static List<String> strings(JsonObject o,String key){if(!o.has(key))return List.of();List<String> values=new ArrayList<>();for(JsonElement e:o.getAsJsonArray(key)){if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException("通知参数必须为字符串");values.add(e.getAsString());}return values;}
    private static List<String> args(List<String> values){if(values==null)return List.of();if(values.size()>4)throw new IllegalArgumentException("通知参数最多四个");for(String v:values)if(v==null||v.length()>64||v.codePoints().anyMatch(c->Character.isISOControl(c)||c==0xa7))throw new IllegalArgumentException("通知参数无效");return List.copyOf(values);}
    static void key(String value,int max){if(value==null||value.length()>max||!value.matches("[A-Za-z0-9_.:/-]+"))throw new IllegalArgumentException("通知键无效");}
}
