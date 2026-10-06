package com.november.mcphone.feature.gifts.client;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.client.ClientHandshake;
import com.november.mcphone.core.script.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 角标只读推送缓存；冷却自然到期在本地计算，不发送请求。 */
public final class GiftCache {
    private static Map<String,JsonObject> gifts=Map.of();
    private static Map<String,JsonObject> pending;
    private static long batch, applied=Long.MIN_VALUE, clockDelta;
    private static int expected;
    private GiftCache() {}
    public static void clear() { gifts=Map.of(); pending=null; applied=Long.MIN_VALUE; }
    public static void push(ScriptPush push) {
        if(!push.isHost() || !push.topic().startsWith("mcphone:gifts/") || ClientHandshake.connectionEpoch()==0) return;
        try {
            String raw=new String(push.data(),StandardCharsets.UTF_8); if(JsonScan.check(raw,6)!=null) return;
            JsonObject row=JsonParser.parseString(raw).getAsJsonObject(); long incoming=row.get("batch").getAsBigDecimal().longValueExact();
            if(push.revision()!=incoming || row.get("epoch").getAsBigDecimal().longValueExact()!=ClientHandshake.connectionEpoch()
                    || !row.get("serverId").getAsString().equals(String.valueOf(ClientHandshake.serverId()))) return;
            switch(push.topic()) {
                case "mcphone:gifts/begin" -> {
                    if(incoming<=applied || row.get("epoch").getAsLong()!=ClientHandshake.connectionEpoch()
                            || !row.get("serverId").getAsString().equals(String.valueOf(ClientHandshake.serverId()))) return;
                    int count=row.get("count").getAsBigDecimal().intValueExact(); if(count<0 || count>64) return;
                    batch=incoming; expected=count; pending=new LinkedHashMap<>(); clockDelta=row.get("serverNow").getAsLong()-System.currentTimeMillis();
                }
                case "mcphone:gifts/item" -> {
                    if(pending==null || incoming!=batch || pending.size()>=expected) return;
                    String id=row.get("id").getAsString(); if(!id.matches("[a-z0-9_.-]{1,64}") || pending.containsKey(id)) { pending=null; return; }
                    pending.put(id,row);
                }
                case "mcphone:gifts/end" -> {
                    if(pending==null || incoming!=batch || row.get("epoch").getAsLong()!=ClientHandshake.connectionEpoch()
                            || !row.get("serverId").getAsString().equals(String.valueOf(ClientHandshake.serverId()))) return;
                    if(pending.size()==expected) { gifts=Map.copyOf(pending); applied=batch; } pending=null;
                }
            }
        } catch(RuntimeException bad) { pending=null; }
    }
    public static int badge() {
        long now=System.currentTimeMillis()+clockDelta; int count=0;
        for(JsonObject row:gifts.values()) {
            JsonObject g=row.getAsJsonObject("guard");
            if(now<row.get("startAt").getAsLong() || now>row.get("endAt").getAsLong()
                    || g.has("remaining") && g.get("remaining").getAsLong()==0 || g.has("pending") && g.get("pending").getAsBoolean()
                    || g.has("nextAt") && g.get("nextAt").getAsLong()>now || row.getAsJsonArray("preview").isEmpty()) continue;
            count++;
        }
        return count;
    }
}
