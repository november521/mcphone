package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 后台需求是签名清单的一部分，客户端与服务端复用同一套解析上限。 */
public final class BackgroundDeclaration {
    public record Task(String id,String action,long intervalMs,String requires) { }
    private BackgroundDeclaration() { }
    public static List<Task> of(AppPackage pkg){return pkg==null?List.of():parse(new String(pkg.entry("manifest.json"),StandardCharsets.UTF_8));}
    public static List<Task> parse(String json){if(JsonScan.check(json,16)!=null)throw new IllegalArgumentException("后台清单格式无效");JsonObject m=JsonParser.parseString(json).getAsJsonObject();if(!m.has("background"))return List.of();JsonArray rows=m.getAsJsonArray("background");if(rows.size()>2)throw new IllegalArgumentException("每 App 最多两个后台任务");
        Set<String> actions=new HashSet<>();if(m.has("actions"))for(JsonElement e:m.getAsJsonArray("actions"))actions.add(e.isJsonPrimitive()?e.getAsString():e.getAsJsonObject().get("id").getAsString());List<Task> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(JsonElement e:rows){JsonObject o=e.getAsJsonObject();if(!Set.of("id","action","interval","requires","wifi").containsAll(o.keySet()))throw new IllegalArgumentException("后台字段无效");String id=o.get("id").getAsString(),action=o.get("action").getAsString();if(!id.matches("[A-Za-z0-9_.-]{1,64}")||!ids.add(id)||!actions.contains(action))throw new IllegalArgumentException("后台动作未声明或任务重复");String requires=o.has("requires")?o.get("requires").getAsString():"online";if(!Set.of("online","app_installed","app_open").contains(requires))throw new IllegalArgumentException("后台可见性无效");if(o.has("wifi")&&(!o.get("wifi").isJsonPrimitive()||!o.getAsJsonPrimitive("wifi").isBoolean()||o.get("wifi").getAsBoolean()))throw new IllegalArgumentException("本版不识别联网介质，wifi 必须为 false");String interval=o.get("interval").getAsString();if(!interval.matches("[0-9]{1,6}[smh]"))throw new IllegalArgumentException("后台间隔应为秒、分钟或小时");long value=Long.parseLong(interval.substring(0,interval.length()-1));long scale=switch(interval.charAt(interval.length()-1)){case 's'->1000;case 'm'->60000;default->3600000;};long ms=value*scale;if(ms>86_400_000)throw new IllegalArgumentException("后台间隔超过 24 小时");result.add(new Task(id,action,Math.max(60_000,ms),requires));}
        return List.copyOf(result);
    }
}
