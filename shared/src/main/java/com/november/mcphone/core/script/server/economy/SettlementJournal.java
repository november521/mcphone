package com.november.mcphone.core.script.server.economy;

import com.google.gson.*;
import com.november.mcphone.api.economy.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.server.ScriptStateData;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** 原生、脚本及超时退款共用的落盘闸。外部钱包和世界存档不能组成原子事务，崩溃窗口必须停下来核对。 */
public final class SettlementJournal {
    public enum State { STARTED, UNKNOWN, COMPLETE, CONFIRMED }
    public record Entry(UUID id,String currency,String operation,State state,long at,EscrowLedger.Entry escrow) { }
    public static final int MAX_ENTRIES=4096,MAX_BYTES=4*1024*1024;
    private final Path file;
    private final EscrowLedger ledger;
    private final LongSupplier clock;
    private final Map<UUID,Entry> entries=new LinkedHashMap<>();
    private String problem="";
    public SettlementJournal(Path file,EscrowLedger ledger,LongSupplier clock){
        this.file=file;this.ledger=Objects.requireNonNull(ledger);this.clock=clock;
        if(file!=null&&!Files.notExists(file))try{
            if(Files.isSymbolicLink(file)||Files.size(file)>MAX_BYTES)throw new IllegalArgumentException("结算日志路径或大小无效");
            String raw=Files.readString(file,StandardCharsets.UTF_8);
            if(JsonScan.check(raw,8)!=null)throw new IllegalArgumentException("结算日志结构无效");
            JsonObject root=JsonParser.parseString(raw).getAsJsonObject();
            if(!root.keySet().equals(Set.of("format","entries"))||root.get("format").getAsBigDecimal().intValueExact()!=1)throw new IllegalArgumentException("结算日志版本无效");
            for(JsonElement value:root.getAsJsonArray("entries")){
                String rawId=value.getAsJsonObject().get("id").getAsString();if(!UUID.fromString(rawId).toString().equals(rawId))throw new IllegalArgumentException("结算 UUID 不规范");
                Entry e=new Gson().fromJson(value,Entry.class);validate(e);
                if(entries.putIfAbsent(e.id(),e)!=null||entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("结算日志重复或超额");
                EscrowLedger.Entry loaded=ledger.get(new EscrowId(e.id()));
                // COMPLETE 证明 provider 回过成功，不证明世界存档已保存。两边不一致时禁止把回滚当退款依据。
                if(e.state()==State.STARTED||e.state()==State.COMPLETE&&(loaded==null||!loaded.settled()))replace(e,State.UNKNOWN);
                if(e.state()==State.CONFIRMED&&loaded!=null&&!loaded.settled())ledger.settle(new EscrowId(e.id()));
            }
            // 只在启动时用已读到的存档判断，不用本次运行的 RAM 结清状态提前删除防重放证据。
            entries.values().removeIf(e->{var saved=ledger.get(new EscrowId(e.id()));return (e.state()==State.COMPLETE||e.state()==State.CONFIRMED)&&saved!=null&&saved.settled()&&clock.getAsLong()-saved.settledAt()>=EscrowLedger.SETTLED_KEEP_MS;});
            persist();
        }catch(Exception bad){problem="结算日志不可用："+bad.getMessage();}
    }
    private static void validate(Entry e){
        if(e==null||e.id()==null||e.state()==null||e.at()<0||e.currency()==null||e.currency().length()>64
                ||!e.currency().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!Set.of("release","refund").contains(e.operation()))throw new IllegalArgumentException("结算日志条目无效");
        if(e.escrow()!=null&&(e.escrow().amount()<=0||e.escrow().owner()==null||e.escrow().beneficiary()==null||!e.currency().equals(e.escrow().currencyId())))throw new IllegalArgumentException("结算原账无效");
    }
    public TxnResult settle(String currency,String operation,EscrowId id,Supplier<TxnResult> effect){
        if(id==null)return TxnResult.UNKNOWN_ESCROW;
        if(!problem.isEmpty())return TxnResult.UNAVAILABLE;
        Entry previous=entries.get(id.value());
        if(previous!=null){
            if(!previous.currency().equals(currency))return TxnResult.NOT_AUTHORIZED;
            if(previous.state()==State.COMPLETE||previous.state()==State.CONFIRMED)return TxnResult.ALREADY_SETTLED;
            throw new IllegalStateException("结算结果待核对，重启和超时扫描都不能重试："+id.value());
        }
        EscrowLedger.Entry nativeEntry=ledger.get(id);
        if(nativeEntry!=null){if(!nativeEntry.currencyId().equals(currency))return TxnResult.NOT_AUTHORIZED;if(nativeEntry.settled())return TxnResult.ALREADY_SETTLED;}
        if(entries.size()>=MAX_ENTRIES||bytes().length+768>MAX_BYTES)return TxnResult.LIMIT;
        Entry start=new Entry(id.value(),currency,operation,State.STARTED,clock.getAsLong(),nativeEntry);validate(start);
        entries.put(start.id(),start);persist(); // 落盘成功之前不许进入钱包。
        try{
            TxnResult result=Objects.requireNonNull(effect.get(),"钱包返回空结算结果");
            if(result==TxnResult.OK||result==TxnResult.ALREADY_SETTLED)replace(start,State.COMPLETE);
            else entries.remove(start.id()); // provider 的确定拒绝没有动钱，可以再次尝试。
            persist();return result;
        }catch(RuntimeException|Error uncertain){
            replace(start,State.UNKNOWN);try{persist();}catch(RuntimeException bad){uncertain.addSuppressed(bad);}throw uncertain;
        }
    }
    /** 必须由原生管理员在核对外部钱包与世界账本后调用；不调用 provider，更不偷偷退款。 */
    public void resolve(UUID id,boolean occurred){
        if(!problem.isEmpty())throw new IllegalStateException(problem);
        Entry e=entries.get(id);if(e==null||e.state()!=State.UNKNOWN)throw new IllegalArgumentException("不是待核对的结算");
        if(occurred)replace(e,State.CONFIRMED);else entries.remove(id);
        persist();if(occurred)ledger.settle(new EscrowId(id));
    }
    public List<Entry> entries(){return List.copyOf(entries.values());}
    public String problem(){return problem;}
    private void replace(Entry e,State state){entries.put(e.id(),new Entry(e.id(),e.currency(),e.operation(),state,e.at(),e.escrow()));}
    private byte[] bytes(){JsonObject root=new JsonObject();root.addProperty("format",1);root.add("entries",new Gson().toJsonTree(entries.values()));return root.toString().getBytes(StandardCharsets.UTF_8);}
    private void persist(){
        if(!problem.isEmpty())throw new IllegalStateException(problem);
        try{byte[] raw=bytes();if(raw.length>MAX_BYTES)throw new IllegalStateException("结算日志超额");if(file!=null)ScriptStateData.atomicWrite(file,raw);}
        catch(Exception bad){problem="结算日志保存失败，暂停结算";throw new IllegalStateException(problem,bad);}
    }
}
