package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.platform.StackCodecs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;

/** 只接受服务端物化出来的 ItemStack。客户端不能提交 NBT 或用显示 id 创建物品。 */
public final class ServerMailbox {
    private final MinecraftServer server;
    private final MailboxLedger ledger;
    public ServerMailbox(MinecraftServer server) {
        this.server = server;
        ledger = new MailboxLedger(server.getWorldPath(LevelResource.ROOT).resolve("mcphone/mailbox/contents.json"), System::currentTimeMillis);
    }
    public boolean deposit(UUID player, List<ItemStack> stacks, String reason) {
        List<String> saved = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (int left = stack.getCount(); left > 0;) {
                ItemStack part = stack.copy(); int count = Math.min(left, stack.getMaxStackSize()); part.setCount(count); left -= count;
                saved.add(StackCodecs.encode(part, server.registryAccess()).toString());
                if (saved.size() > 54) return false;
            }
        }
        return saved.isEmpty() || ledger.deposit(player, saved, reason);
    }
    public JsonArray list(UUID player) {
        JsonArray out = new JsonArray();
        for (MailboxLedger.Entry e : ledger.entries(player)) {
            ItemStack stack = StackCodecs.decode(JsonParser.parseString(e.stack()), server.registryAccess());
            JsonObject row = new JsonObject(); row.addProperty("id", e.id()); row.addProperty("count", stack.getCount());
            String name = stack.getHoverName().getString(); row.addProperty("name", name.substring(0, Math.min(24, name.length())));
            row.addProperty("state", e.state().name());row.addProperty("incoming",e.incoming()); row.addProperty("expiresAt", e.expiresAt()); out.add(row);
        }
        return out;
    }
    public ServerMailbox quotas(java.util.function.Supplier<QuotaConfig> config,java.util.function.ToIntFunction<UUID> otherSlots){ledger.quotas(config,otherSlots);return this;}
    public int used(UUID player){return ledger.entries(player).size();}
    public Map<UUID,List<MailboxLedger.Entry>> snapshot(){return ledger.snapshot();}
    public com.november.mcphone.api.sdk.mailbox.DepositResult transfer(UUID playerId,List<ItemStack> stacks,List<Integer> slots,String reason,Runnable beforeEffects){
        var player=server.getPlayerList().getPlayer(playerId);
        if(player==null)return com.november.mcphone.api.sdk.mailbox.DepositResult.UNAVAILABLE;
        if(stacks.isEmpty()||stacks.size()>27||stacks.size()!=slots.size())throw new IllegalArgumentException("收件箱转移批次无效");
        Set<Integer> moved=new HashSet<>();List<String> encoded=new ArrayList<>();boolean incoming=false;
        for(int i=0;i<stacks.size();i++){
            ItemStack stack=stacks.get(i);int slot=slots.get(i);String raw=StackCodecs.encode(stack,server.registryAccess()).toString();
            if(slot>=0){
                incoming=true;if(slot>=41||!moved.add(slot)||!raw.equals(StackCodecs.encode(player.getInventory().getItem(slot),server.registryAccess()).toString()))
                    return com.november.mcphone.api.sdk.mailbox.DepositResult.INVALID;
            }
            if(stack.isEmpty()||stack.getCount()>stack.getMaxStackSize())return com.november.mcphone.api.sdk.mailbox.DepositResult.INVALID;
            encoded.add(raw);
        }
        if(!ledger.fits(playerId,encoded))return com.november.mcphone.api.sdk.mailbox.DepositResult.FULL;
        beforeEffects.run();
        List<String> ids=ledger.beginDeposit(playerId,encoded,reason,incoming);
        if(ids.isEmpty())throw new IllegalStateException("收件箱容量在主线程转移中改变");
        if(incoming)try {
            for(int slot:moved)player.getInventory().setItem(slot,ItemStack.EMPTY);
            player.containerMenu.broadcastChanges();server.getPlayerList().saveAll();
            if(!com.november.mcphone.platform.PlayerSaves.inventoryConfirmed(player))throw new IllegalStateException("收件箱搬入后的背包保存未确认");
            ledger.received(playerId,ids);
        }catch(RuntimeException uncertain){for(String id:ids)try{ledger.unknown(playerId,id);}catch(RuntimeException bad){uncertain.addSuppressed(bad);}throw uncertain;}
        return com.november.mcphone.api.sdk.mailbox.DepositResult.OK;
    }
    public ScriptErrorCode claim(UUID playerId, String id) {
        var player = server.getPlayerList().getPlayer(playerId); if (player == null) return ScriptErrorCode.UNAVAILABLE;
        var entry = ledger.entries(playerId).stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        if (entry == null) return ScriptErrorCode.INVALID_ARGUMENT;
        if (entry.state() != MailboxLedger.State.AVAILABLE) return ScriptErrorCode.UNKNOWN;
        ItemStack stack = StackCodecs.decode(JsonParser.parseString(entry.stack()), server.registryAccess());
        // 只算主背包 36 格，盔甲与副手不能替主背包提供容量。
        int empty = 0; for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) empty++;
        if (!InventoryFit.fits(empty, List.of(stack.getCount()), List.of(stack.getMaxStackSize()))) return ScriptErrorCode.INVENTORY_FULL;
        try {
            ledger.begin(playerId, id);
            if (!player.getInventory().add(stack) || !stack.isEmpty()) { ledger.unknown(playerId, id); return ScriptErrorCode.UNKNOWN; }
            player.containerMenu.broadcastChanges();
            // 原版的存档接口只有 saveAll 是公开的；先存玩家，再确认出箱。崩在两者之间转 UNKNOWN。
            server.getPlayerList().saveAll();
            if (!com.november.mcphone.platform.PlayerSaves.inventoryConfirmed(player)) {
                ledger.unknown(playerId, id); return ScriptErrorCode.UNKNOWN;
            }
            ledger.complete(playerId, id); return ScriptErrorCode.OK;
        } catch (RuntimeException failure) {
            try { ledger.unknown(playerId, id); } catch (RuntimeException persistence) { failure.addSuppressed(persistence); }
            MCphone.LOGGER.error("[MCphone] 收件箱领取结果待核对 player={} item={}", playerId, id, failure);
            return ScriptErrorCode.UNKNOWN;
        }
    }
    public void resolve(UUID player,String id,boolean occurred){resolve(null,player,id,occurred);}
    public void resolve(UUID actor,UUID player,String id,boolean occurred){
        var entry=ledger.entries(player).stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();ScriptHost host=ScriptHost.current();
        if(host==null)throw new IllegalStateException("收件箱核对需要当前宿主审计");
        JsonObject data=new Gson().toJsonTree(entry).getAsJsonObject();data.addProperty("version","0");data.addProperty("owner",player.toString());data.addProperty("occurred",occurred);
        try{host.quotas().audit().append(actor,"mailbox.resolve","mcphone:mailbox",null,data);}catch(java.io.IOException bad){throw new IllegalStateException("核对审计未保存",bad);}
        ledger.resolve(player,id,occurred);
    }
    public void expire() {
        ScriptHost host=ScriptHost.current();long now=System.currentTimeMillis();if(host!=null)for(var player:server.getPlayerList().getPlayers())for(var entry:ledger.entries(player.getUUID()))if(entry.state()==MailboxLedger.State.AVAILABLE&&entry.expiresAt()>now&&entry.expiresAt()-now<=3L*86400000)host.notifications().mailboxWarning(player.getUUID(),entry.id(),entry.expiresAt()-now);
        for (var e : ledger.expire(entry->{if(host!=null){JsonObject data=new Gson().toJsonTree(entry.getValue()).getAsJsonObject();data.addProperty("version","0");data.addProperty("owner",entry.getKey().toString());try{host.quotas().audit().append(null,"mailbox.expire","mcphone:mailbox",null,data);}catch(java.io.IOException bad){throw new IllegalStateException("收件箱清理的审计未保存，暂保留物品",bad);}}})) MCphone.LOGGER.warn("[MCphone] 收件箱物品过期销毁 player={} item={} reason={}",
                e.getKey(), e.getValue().id(), e.getValue().reason());
    }
}
