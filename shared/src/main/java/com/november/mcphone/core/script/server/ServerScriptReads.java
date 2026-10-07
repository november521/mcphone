package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.engine.CtxBuilder;
import com.november.mcphone.core.script.engine.HostError;
import com.november.mcphone.core.script.server.economy.CurrencyGateway;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** 每次读都回主线程检查原连接、当前部署和能力；只返回有界原语快照。 */
public final class ServerScriptReads implements CtxBuilder.ReadView {
    private final MinecraftServer server;
    private final CurrencyGateway gateway;
    private final ActionEvaluator.Request request;
    private final ServerScriptItems itemHandles;
    public ServerScriptReads(MinecraftServer server,CurrencyGateway gateway,ActionEvaluator.Request request,ServerScriptItems itemHandles){this.server=server;this.gateway=gateway;this.request=request;this.itemHandles=itemHandles;}
    public Object read(String capability,int offset){
        return gateway.call(()->{
            var player=ScriptHost.requireAccess(server,request,capability);var level=player.serverLevel();
            return switch(capability){
                // RPC 值域只接受整数；坐标、旋转及属性的小数必须以十进制字符串返回。
                case "read.self.position"->Map.of("x",Double.toString(player.getX()),"y",Double.toString(player.getY()),"z",Double.toString(player.getZ()),"yaw",Float.toString(player.getYRot()),"pitch",Float.toString(player.getXRot()),"dimension",level.dimension().location().toString());
                case "read.self.stats"->Map.of("health",Float.toString(player.getHealth()),"maxHealth",Float.toString(player.getMaxHealth()),"food",player.getFoodData().getFoodLevel(),"saturation",Float.toString(player.getFoodData().getSaturationLevel()),"level",player.experienceLevel,"xpProgress",Float.toString(player.experienceProgress),"totalExperience",player.totalExperience,"air",player.getAirSupply());
                case "read.self.inventory"->{List<Map<String,Object>> items=new ArrayList<>();var inventory=player.getInventory();int total=inventory.getContainerSize();
                    for(int slot=offset;slot<Math.min(total,offset+16);slot++){var stack=inventory.getItem(slot);items.add(itemHandles.inventory(request,slot,stack));}
                    yield Map.of("items",items,"total",total);
                }
                case "read.world.time"->Long.toString(level.getDayTime());
                case "read.world.weather"->Map.of("raining",level.isRaining(),"thundering",level.isThundering());
                case "read.players.online_count"->server.getPlayerList().getPlayerCount();
                case "read.players.list"->{var online=server.getPlayerList().getPlayers();List<String> names=new ArrayList<>();for(int i=offset;i<Math.min(online.size(),offset+16);i++)names.add(StoreRepository.clean(online.get(i).getGameProfile().getName(),32));yield Map.of("items",names,"total",online.size());}
                default->throw HostError.invalid("未知只读能力");
            };
        });
    }
}
