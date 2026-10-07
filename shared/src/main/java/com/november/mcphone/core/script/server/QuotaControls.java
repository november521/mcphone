package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.net.*;
import net.minecraft.server.MinecraftServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** 独立宿主管理通道：当前 OP 3 与文件里的部署审批名单同时满足，脚本能力不能替代。 */
public final class QuotaControls {
    private final MinecraftServer server;private final QuotaManager quotas;private final StorageBudget budget;
    private final Supplier<ScriptRuntimeConfig> runtime;
    public QuotaControls(MinecraftServer server,QuotaManager quotas,StorageBudget budget,Supplier<ScriptRuntimeConfig> runtime){this.server=server;this.quotas=quotas;this.budget=budget;this.runtime=runtime;}
    public boolean allowed(UUID uuid){var player=server.getPlayerList().getPlayer(uuid);return player!=null&&player.hasPermissions(3)&&runtime.get().deploymentApprovers().contains(uuid);}
    public ScriptRpcResult handle(ScriptRpc rpc,PlayerSnapshot player,JsonObject args)throws IOException {
        boolean admin=allowed(player.uuid());JsonObject out=new JsonObject();
        if(rpc.actionId().equals("quota.roles")){if(args.size()!=0)throw new IllegalArgumentException("管理身份参数无效");out.addProperty("admin",admin);}
        else {
            if(!admin)return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.NOT_AUTHORIZED);
            switch(rpc.actionId()) {
                case "quota.show"->{if(!args.keySet().equals(Set.of("offset")))throw new IllegalArgumentException("配额分页无效");int offset=args.get("offset").getAsBigDecimal().intValueExact();if(offset<0||offset>QuotaConfig.SPECS.size())throw new IllegalArgumentException("配额分页无效");JsonArray rows=new JsonArray();for(int i=offset;i<Math.min(QuotaConfig.SPECS.size(),offset+8);i++){var s=QuotaConfig.SPECS.get(i);JsonObject row=new JsonObject();row.addProperty("key",s.key());row.addProperty("value",quotas.current().get(s.key()));row.addProperty("min",s.minimum());row.addProperty("max",s.maximum());row.addProperty("unit",s.unit());rows.add(row);}out.add("items",rows);out.addProperty("total",QuotaConfig.SPECS.size());out.addProperty("dataUsed",budget.total());}
                case "quota.top"->{if(!args.keySet().equals(Set.of("kind")))throw new IllegalArgumentException("配额排行参数无效");String kind=args.get("kind").getAsString();Map<String,Long> values=switch(kind){case "player"->budget.players();case "app"->budget.apps();default->throw new IllegalArgumentException("排行仅支持 player / app");};JsonArray rows=new JsonArray();values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(10).forEach(e->{JsonObject row=new JsonObject();row.addProperty("id",e.getKey());row.addProperty("used",e.getValue());rows.add(row);});out.add("items",rows);}
                case "quota.set"->{if(!args.keySet().equals(Set.of("key","value")))throw new IllegalArgumentException("配额写入字段无效");String key=args.get("key").getAsString();long value=args.get("value").getAsBigDecimal().longValueExact();quotas.set(player.uuid(),key,value);out.addProperty("key",key);out.addProperty("value",value);ScriptHost h=ScriptHost.current();if(h!=null)for(var p:server.getPlayerList().getPlayers())h.pushNetworkPolicy(p);}
                default->throw new IllegalArgumentException("配额操作无效");
            }
        }
        byte[] data=out.toString().getBytes(StandardCharsets.UTF_8);if(data.length>ScriptProtocol.DATA_MAX)throw new IOException("配额响应超额");return ScriptRpcResult.ok(rpc.requestId(),data,0);
    }
}
