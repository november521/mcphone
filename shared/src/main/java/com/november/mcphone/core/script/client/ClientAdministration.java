package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.core.script.net.ScriptPush;
import java.nio.charset.StandardCharsets;

/** 仅用于入口显示的服务器角色快照；真正权限每次由服务端重新查。 */
public final class ClientAdministration {
    private static long revision;private static boolean admin;private static int apps=32;
    private static int fileProtocol,compressed=262144,expanded=1048576;
    private static int notificationProtocol;private static boolean onlineMode=true;
    private ClientAdministration(){}
    public static boolean admin(){return ClientHandshake.complete()&&admin;}
    public static int appLimit(){return ClientHandshake.complete()?apps:32;}
    public static boolean fileChannel(){return ClientHandshake.complete()&&fileProtocol==com.november.mcphone.core.script.net.StoreFileRequest.PROTOCOL;}
    public static int packageCompressed(){return compressed;}
    public static int packageExpanded(){return expanded;}
    public static boolean notificationChunks(){return ClientHandshake.complete()&&notificationProtocol==2;}
    public static boolean offlineIdentity(){return ClientHandshake.complete()&&!onlineMode;}
    public static void clear(){revision=0;admin=false;apps=32;fileProtocol=0;notificationProtocol=0;onlineMode=true;compressed=262144;expanded=1048576;}
    public static void push(ScriptPush push){if(!push.isHost()||!push.topic().equals("mcphone:network.policy")||push.revision()<=revision||!ClientHandshake.complete())return;
        try{JsonObject o=JsonParser.parseString(new String(push.data(),StandardCharsets.UTF_8)).getAsJsonObject();if(!ClientHandshake.serverId().toString().equals(o.get("serverId").getAsString())||ClientHandshake.connectionEpoch()!=new java.math.BigDecimal(o.get("epoch").getAsString()).longValueExact())return;
            int limit=o.get("appLimit").getAsBigDecimal().intValueExact();if(limit<8||limit>128||!o.getAsJsonPrimitive("admin").isBoolean())return;
            int protocol=o.has("storeFileProtocol")?o.get("storeFileProtocol").getAsBigDecimal().intValueExact():0;
            int c=o.has("packageCompressed")?o.get("packageCompressed").getAsBigDecimal().intValueExact():262144,e=o.has("packageExpanded")?o.get("packageExpanded").getAsBigDecimal().intValueExact():1048576;
            if(c<65536||c>1048576||e<262144||e>4194304)return;
            if(o.has("onlineMode")&&(!o.get("onlineMode").isJsonPrimitive()||!o.getAsJsonPrimitive("onlineMode").isBoolean()))return;
            notificationProtocol=o.has("notificationProtocol")?o.get("notificationProtocol").getAsBigDecimal().intValueExact():0;
            apps=limit;admin=o.get("admin").getAsBoolean();fileProtocol=protocol;compressed=c;expanded=e;onlineMode=!o.has("onlineMode")||o.get("onlineMode").getAsBoolean();revision=push.revision();
        }catch(RuntimeException ignored){ }
    }
}
