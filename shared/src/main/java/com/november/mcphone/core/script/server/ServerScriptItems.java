package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.engine.*;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.core.script.server.economy.CurrencyGateway;
import com.november.mcphone.platform.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.function.Supplier;

/** 真实物品只保存在宿主表中；脚本拿到的 id/count 只是显示投影，入站只采信绑定句柄。 */
public final class ServerScriptItems {
    private final MinecraftServer server;
    private final CurrencyGateway gateway;
    private record HeldStack(ItemStack stack,int slot){HeldStack copy(){return new HeldStack(stack.copy(),slot);}}
    private final ItemHandleTable<HeldStack> table=new ItemHandleTable<>(HeldStack::copy,()->System.nanoTime()/1000000);
    public ServerScriptItems(MinecraftServer server,CurrencyGateway gateway){this.server=server;this.gateway=gateway;}
    private static ItemHandleTable.Owner owner(ActionEvaluator.Request r){return new ItemHandleTable.Owner(r.player().uuid(),r.connectionEpoch(),r.appId(),r.deployRev(),r.actionId(),r.requestId());}
    private <T>T call(Supplier<T> read){if(gateway==null)throw HostError.unavailable("mcphone.script.intent_unavailable");return gateway.call(read);}
    private Map<String,Object> issue(ActionEvaluator.Request request,ItemStack stack,String source){
        return issue(request,stack,source,-1);
    }
    private Map<String,Object> issue(ActionEvaluator.Request request,ItemStack stack,String source,int slot){
        if(stack.isEmpty())return Map.of("id","minecraft:air","count",0,"opaque","");
        if(stack.getCount()>99)throw HostError.invalid("物品栈数量超过 ItemRef 上限");
        long bytes=StackCodecs.encode(stack,server.registryAccess()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String token;try{token=table.issue(owner(request),new HeldStack(stack,slot),source,bytes);}catch(IllegalArgumentException bad){throw HostError.invalid(bad.getMessage());}catch(IllegalStateException full){throw HostError.quota(full.getMessage());}
        return Map.of("id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount(),"opaque",token);
    }
    public Map<String,Object> inventory(ActionEvaluator.Request request,int slot,ItemStack stack){Map<String,Object> out=new LinkedHashMap<>(issue(request,stack,"read.self.inventory",slot));out.put("slot",slot);return out;}
    private ItemStack get(ActionEvaluator.Request request,String handle){
        return resolve(request,handle).stack();
    }
    private HeldStack resolve(ActionEvaluator.Request request,String handle){
        ItemHandleTable.Entry<HeldStack> entry;try{entry=table.get(owner(request),handle);}catch(IllegalArgumentException bad){throw HostError.invalid(bad.getMessage());}
        ScriptHost.requireAccess(server,request,entry.source());return entry.value();
    }
    public CtxBuilder.MailboxView mailbox(ActionEvaluator.Request request){return new CtxBuilder.MailboxView(){
        public int count(UUID player){return call(()->{ScriptHost.requireAccess(server,request,null);if(!player.equals(request.player().uuid()))throw HostError.denied(ScriptErrorCode.NOT_AUTHORIZED,"mcphone.script.not_authorized","只能查询自己的收件箱");return ScriptHost.current().mailbox().used(player);});}
        public com.november.mcphone.api.sdk.mailbox.DepositResult deposit(UUID player,List<String> handles,String reason,Runnable beforeEffects){return call(()->{
            ScriptHost.requireAccess(server,request,null);
            if(!player.equals(request.player().uuid()))return com.november.mcphone.api.sdk.mailbox.DepositResult.NOT_AUTHORIZED;
            List<ItemStack> stacks=new ArrayList<>();List<Integer> slots=new ArrayList<>();
            for(String handle:handles){HeldStack value=resolve(request,handle);ScriptHost.requireAccess(server,request,value.slot()<0?"item.give":"item.take.self");
                if(value.slot()<0&&!value.stack().is(TagKey.create(Registries.ITEM,ResourceLocation.fromNamespaceAndPath("mcphone","giftable"))))throw HostError.invalid("物品不在礼包白名单内");
                stacks.add(value.stack());slots.add(value.slot());
            }
            ScriptHost host=ScriptHost.current();
            return host.mailbox().transfer(player,stacks,slots,reason,()->{
                com.google.gson.JsonObject data=new com.google.gson.JsonObject();data.addProperty("version",Long.toString(host.version(request.deployRev())));data.addProperty("package",request.deployRev());data.addProperty("request",Long.toString(request.requestId()));data.addProperty("reason",reason);data.add("slots",new com.google.gson.Gson().toJsonTree(slots));
                com.google.gson.JsonArray items=new com.google.gson.JsonArray();for(ItemStack stack:stacks)items.add(StackCodecs.encode(stack,server.registryAccess()));data.add("items",items);
                try{host.quotas().audit().append(player,"mailbox.deposit",request.appId(),null,data);}catch(java.io.IOException bad){throw HostError.unavailable("mcphone.script.intent_unavailable");}
                request.beginEffects().run();beforeEffects.run();
            });
        });}
    };}
    public ItemLootView view(ActionEvaluator.Request request){return new ItemLootView(){
        public List<Map<String,Object>> roll(String raw){return call(()->{
            var player=ScriptHost.requireAccess(server,request,"loot.roll");ResourceLocation id=ResourceLocation.tryParse(raw);
            if(id==null||raw.length()>64||!LootAccess.exists(player.serverLevel(),id))throw HostError.unavailable(ServerIntentApplier.NO_SUCH_TABLE);
            List<ItemStack> rolled=LootAccess.roll(player.serverLevel(),id,player);List<Map<String,Object>> refs=new ArrayList<>();
            for(ItemStack stack:rolled){if(stack.isEmpty())continue;for(int left=stack.getCount();left>0;){if(refs.size()>=27)throw HostError.quota("一次战利品最多 27 栈");ItemStack part=stack.copy();int count=Math.min(left,Math.min(99,stack.getMaxStackSize()));part.setCount(count);left-=count;refs.add(issue(request,part,"loot.roll"));}}
            return List.copyOf(refs);
        });}
        public boolean matches(String handle,String tag){return call(()->{
            ItemStack stack=get(request,handle);if(tag==null||tag.length()>65||!tag.startsWith("#"))throw HostError.invalid("物品判定须引用 #namespace:tag");ResourceLocation id=ResourceLocation.tryParse(tag.substring(1));if(id==null)throw HostError.invalid("物品标签无效");return stack.is(TagKey.create(Registries.ITEM,id));
        });}
        public String displayName(String handle){return call(()->StoreRepository.clean(get(request,handle).getHoverName().getString(),256));}
        public boolean isDamaged(String handle){return call(()->get(request,handle).isDamaged());}
    };}
    public List<ItemStack> materialize(UUID player,ScriptRpc rpc,List<String> handles){
        var live=server.getPlayerList().getPlayer(player);if(live==null)throw HostError.unavailable("mcphone.script.intent_unavailable");
        var request=new ActionEvaluator.Request(rpc.appId(),rpc.actionId(),new byte[0],new PlayerSnapshot(player,"","","",0),rpc.deployRev(),0,()->{},false,rpc.connectionEpoch(),rpc.requestId());
        ScriptHost.requireAccess(server,request,"item.give");List<ItemStack> stacks=new ArrayList<>();
        for(String handle:handles)stacks.add(get(request,handle));return List.copyOf(stacks);
    }
    public void forget(ActionEvaluator.Request request){table.forget(owner(request));}
    public void forget(UUID player){table.forget(player);}
    public void sweep(){table.sweep();}
    public void clear(){table.clear();}
}
