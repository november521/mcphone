package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.platform.StackCodecs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** 礼包奖励全程在服务器；模板编辑、领取守卫与幂等状态不由客户端决定。 */
public final class ServerGifts {
    public static final String APP="mcphone:gifts", TOPIC="mcphone:gifts/";
    private final MinecraftServer server;
    private final UUID serverId;
    private final GiftDefinitions definitions;
    private final Supplier<ScriptRuntimeConfig> config;
    private final GuardController guards;
    private final IdempotencyLedger ledger;
    private final ServerMailbox mailbox;
    private AuditLog audit;
    public ServerGifts audit(AuditLog log){audit=log;definitions.audit(log);return this;}
    public ServerGifts(MinecraftServer server,UUID serverId,Supplier<ScriptRuntimeConfig> config,
                       GuardController guards,IdempotencyLedger ledger,ServerMailbox mailbox) {
        this.server=server; this.serverId=serverId; this.config=config; this.guards=guards; this.ledger=ledger; this.mailbox=mailbox;
        try { definitions=new GiftDefinitions(server.getWorldPath(LevelResource.ROOT).resolve("mcphone/gifts/"+serverId+"/definitions.json")); }
        catch(java.io.IOException failure) { throw new IllegalStateException("礼包库无法读取",failure); }
    }
    private boolean editor(UUID player) { return config.get().giftEditors().contains(player); }
    private List<ItemStack> stacks(GiftDefinitions.Definition d) {
        return d.items().stream().map(s->StackCodecs.decode(JsonParser.parseString(s),server.registryAccess())).toList();
    }
    private JsonObject display(GiftDefinitions.Definition d,UUID player) {
        JsonObject row=new JsonObject(); row.addProperty("id",d.id()); row.addProperty("label",d.label());
        row.addProperty("startAt",d.start()); row.addProperty("endAt",d.end());
        JsonObject state=guards.describe(player,APP,Map.of(d.id(),d.guards())).getAsJsonObject("actions").getAsJsonObject(d.id());
        row.add("guard",state); JsonArray preview=new JsonArray();
        for(ItemStack stack:stacks(d).stream().limit(6).toList()) { JsonObject item=new JsonObject(); item.addProperty("id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); item.addProperty("count",stack.getCount()); preview.add(item); }
        row.add("preview",preview); return row;
    }
    public ScriptRpcResult handle(ScriptRpc rpc,PlayerSnapshot snapshot,JsonObject args) {
        UUID player=snapshot.uuid(); ServerPlayer actual=server.getPlayerList().getPlayer(player);
        if(actual==null) return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);
        if(rpc.actionId().equals("gift.list")) {
            int offset=args.has("offset")?args.get("offset").getAsBigDecimal().intValueExact():0;
            if(offset<0 || offset>64 || args.size()>1) throw new IllegalArgumentException("礼包页码无效");
            JsonObject response=new JsonObject(); JsonArray rows=new JsonArray(); var all=definitions.all();
            for(int i=offset;i<Math.min(all.size(),offset+3);i++) {
                var definition=all.get(i); JsonObject row=display(definition,player);
                if(editor(player)) { row.addProperty("total",definition.total()); row.addProperty("cooldownMs",definition.cooldown()); row.addProperty("predicate",definition.predicate());row.addProperty("loot",definition.loot()); }
                rows.add(row);
            }
            response.add("items",rows); response.addProperty("total",all.size()); response.addProperty("editor",editor(player)); response.addProperty("serverNow",System.currentTimeMillis());
            return result(rpc,response);
        }
        String id=args.get("id").getAsString(); if(!id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("礼包 id 无效");
        GiftDefinitions.Definition d=definitions.get(id);
        if(rpc.actionId().equals("gift.claim")) {
            if(args.size()!=1 || d==null || d.items().isEmpty()&&d.loot().isEmpty()) return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT);
            return claim(rpc,player,d);
        }
        if(!editor(player)) return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.NOT_AUTHORIZED);
        if(rpc.actionId().equals("gift.configure")) {
            if(!args.keySet().equals(Set.of("id","label","startAt","endAt","total","cooldownMs","predicate"))&&!args.keySet().equals(Set.of("id","label","startAt","endAt","total","cooldownMs","predicate","loot"))) throw new IllegalArgumentException("礼包配置字段数无效");
            String loot=args.has("loot")?args.get("loot").getAsString():d==null?"":d.loot();
            if(!loot.isEmpty()&&!com.november.mcphone.platform.LootAccess.exists(actual.serverLevel(),net.minecraft.resources.ResourceLocation.parse(loot)))throw new IllegalArgumentException("当前服务器没有这张战利品表");
            GiftDefinitions.Definition next=new GiftDefinitions.Definition(id,args.get("label").getAsString(),loot.isEmpty()&&d!=null?d.items():List.of(),args.get("startAt").getAsBigDecimal().longValueExact(),args.get("endAt").getAsBigDecimal().longValueExact(),args.get("total").getAsBigDecimal().intValueExact(),args.get("cooldownMs").getAsBigDecimal().longValueExact(),args.get("predicate").getAsString(),loot);
            save(next,actual); return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.OK);
        }
        if(args.size()!=1 || d==null) return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT);
        GiftDefinitions.Definition opened=d;
        actual.openMenu(new SimpleMenuProvider((container,inventory,p)->new GiftEditorMenu(container,inventory,stacks(opened),()->editor(player),items->{
            // 会话期间同一个定义若被别人改了，就拒绝覆盖。
            if(definitions.get(id)!=opened) { actual.displayClientMessage(Component.translatable("mcphone.gift.changed"),false); return; }
            List<String> encoded=items.stream().map(s->StackCodecs.encode(s,server.registryAccess()).toString()).toList();
            try { save(new GiftDefinitions.Definition(id,opened.label(),encoded,opened.start(),opened.end(),opened.total(),opened.cooldown(),opened.predicate()),actual); }
            catch(RuntimeException failure) { MCphone.LOGGER.error("[MCphone] 保存礼包模板失败 {}",id,failure); actual.displayClientMessage(Component.translatable("mcphone.gift.save_failed"),false); }
        }),Component.translatable("mcphone.gift.editor_title")));
        return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.OK);
    }
    private void save(GiftDefinitions.Definition d,ServerPlayer editor) {
        try { definitions.save(d,editor.getUUID(),editor.getGameProfile().getName()); }
        catch(java.io.IOException failure) { throw new IllegalStateException("礼包无法保存",failure); }
        MCphone.LOGGER.info("[MCphone] 礼包已编辑 gift={} editor={} label={} entries={}",d.id(),editor.getUUID(),d.label(),d.items().size());
        for(ServerPlayer player:server.getPlayerList().getPlayers()) pushTo(player);
    }
    private ScriptRpcResult claim(ScriptRpc rpc,UUID player,GiftDefinitions.Definition d) {
        byte[] key=IdempotencyKey.of(serverId,player,APP,"gift",d.id(),rpc.requestId()), digest=IdempotencyKey.digestOf(rpc.params());
        var seen=ledger.check(player,key,digest);
        if(seen instanceof IdempotencyLedger.Verdict.Replay replay) return new ScriptRpcResult(rpc.requestId(),replay.code(),replay.data(),"",List.of(),replay.retryAfterMs(),replay.stateRevision());
        if(seen instanceof IdempotencyLedger.Verdict.ParamsChanged) return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT);
        if(!(seen instanceof IdempotencyLedger.Verdict.Fresh)) return ScriptRpcResult.fail(rpc.requestId(),seen instanceof IdempotencyLedger.Verdict.Full?ScriptErrorCode.RATE_LIMITED:ScriptErrorCode.IN_PROGRESS);
        String hex=IdempotencyKey.hex(key); var decision=guards.reserve(hex,player,APP,d.id(),d.guards());
        if(!decision.allowed()) return new ScriptRpcResult(rpc.requestId(),decision.code(),new byte[0],"",List.of(),Math.max(0,decision.at()-decision.serverNow()),guards.revision());
        ScriptErrorCode code;
        try {
            ledger.reserve(player,key,digest);
            List<ItemStack> reward=stacks(d);if(!d.loot().isEmpty()){var actual=server.getPlayerList().getPlayer(player);var table=net.minecraft.resources.ResourceLocation.parse(d.loot());if(actual==null||!com.november.mcphone.platform.LootAccess.exists(actual.serverLevel(),table)){guards.finish(hex,ScriptErrorCode.UNAVAILABLE);ledger.settle(player,key,ScriptErrorCode.UNAVAILABLE,new byte[0],0,guards.revision());return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);}reward=com.november.mcphone.platform.LootAccess.roll(actual.serverLevel(),table,actual);}
            ledger.effectStarted(player,key);
            if(audit!=null){JsonObject output=new JsonObject();output.addProperty("version","0");output.addProperty("gift",d.id());output.addProperty("request",Long.toString(rpc.requestId()));JsonArray items=new JsonArray();for(ItemStack stack:reward)items.add(StackCodecs.encode(stack,server.registryAccess()));output.add("items",items);try{audit.append(player,"gift.reward",APP,null,output);}catch(java.io.IOException unavailable){guards.finish(hex,ScriptErrorCode.UNAVAILABLE);ledger.settle(player,key,ScriptErrorCode.UNAVAILABLE,new byte[0],0,guards.revision());return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);}}
            // 奖励先进入持久化收件箱。两次日记写之间崩溃只锁成 UNKNOWN，不重发。
            code=mailbox.deposit(player,reward,"gift:"+d.id()+":"+d.label())?ScriptErrorCode.OK:ScriptErrorCode.INVENTORY_FULL;
            guards.finish(hex,code); ledger.settle(player,key,code,new byte[0],0,guards.revision());
        } catch(RuntimeException failure) {
            MCphone.LOGGER.error("[MCphone] 礼包领取待核对 player={} gift={} key={}",player,d.id(),hex,failure);
            code=ScriptErrorCode.UNKNOWN;
            try { ledger.settle(player,key,code,new byte[0],0,guards.revision()); } catch(RuntimeException locked) { failure.addSuppressed(locked); }
        }
        for(ServerPlayer recipient:server.getPlayerList().getPlayers()) {
            try { pushTo(recipient); }
            catch(RuntimeException failure) { MCphone.LOGGER.warn("[MCphone] 礼包状态推送失败 player={}；领取结果已经落账",recipient.getUUID(),failure); }
        }
        return ScriptRpcResult.fail(rpc.requestId(),code);
    }
    private static ScriptRpcResult result(ScriptRpc rpc,JsonObject data) {
        byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8); if(bytes.length>ScriptProtocol.DATA_MAX) throw new IllegalStateException("礼包响应超额");
        return new ScriptRpcResult(rpc.requestId(),ScriptErrorCode.OK,bytes,"",List.of(),0,0);
    }
    public void pushTo(ServerPlayer player) {
        ScriptHost host=ScriptHost.current(); if(host==null) return;
        long epoch=host.pipeline().epochOf(player.getUUID()); if(epoch==0) return;
        var all=definitions.all(); long batch=Seq.next(); JsonObject header=new JsonObject(); header.addProperty("serverId",serverId.toString()); header.addProperty("epoch",epoch); header.addProperty("batch",batch); header.addProperty("count",all.size()); header.addProperty("serverNow",System.currentTimeMillis());
        push(player,"begin",header,batch);
        for(var d:all) { JsonObject row=display(d,player.getUUID()); row.addProperty("batch",batch);
            row.addProperty("serverId",serverId.toString()); row.addProperty("epoch",epoch); push(player,"item",row,batch); }
        push(player,"end",header,batch);
    }
    private static void push(ServerPlayer player,String suffix,JsonObject data,long revision) {
        ScriptPushHandler.push(player,new ScriptPush(ScriptProtocol.HOST_APP_ID,TOPIC+suffix,data.toString().getBytes(StandardCharsets.UTF_8),revision));
    }
}
