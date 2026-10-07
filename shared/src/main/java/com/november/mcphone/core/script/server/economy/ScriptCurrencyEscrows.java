package com.november.mcphone.core.script.server.economy;

import com.google.gson.*;
import com.november.mcphone.api.economy.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.server.ScriptStateData;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** 脚本托管的持久归属闸；外部钱包也必须经过它，不能用别人的托管号提前放款或退款。 */
public final class ScriptCurrencyEscrows {
    public record Scope(String app, String author, UUID owner,String revision,long version) {
        public Scope(String app,String author,UUID owner){this(app,author,owner,"",0);}
        public Scope {
            if (app == null || !app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || app.length()>64
                    || author == null || author.isEmpty() || author.length()>128 || owner==null||revision==null||!revision.isEmpty()&&!revision.matches("[0-9a-f]{64}")||version<0)
                throw new IllegalArgumentException("货币托管调用身份无效");
        }
        public boolean sameAuthority(Scope other){return app.equals(other.app)&&author.equals(other.author)&&owner.equals(other.owner);}
    }
    public enum State { HELD, SETTLING, UNKNOWN, SETTLED }
    public record Entry(UUID id, String currency, Scope scope, UUID beneficiary, long amount,
                        long createdAt, State state, long settledAt) { }
    public static final int MAX_ENTRIES=4096, MAX_BYTES=4*1024*1024;
    private final Map<UUID,Entry> entries=new LinkedHashMap<>();
    private final Path file;
    private final LongSupplier clock;
    private boolean locked;
    public ScriptCurrencyEscrows(Path file, LongSupplier clock) {
        this.file=file;this.clock=clock;
        if(file!=null&&!Files.notExists(file))try {
            if(Files.isSymbolicLink(file)||Files.size(file)>MAX_BYTES)throw new IllegalArgumentException("货币托管归属账路径或大小无效");
            String raw=Files.readString(file,StandardCharsets.UTF_8);
            if(JsonScan.check(raw,10)!=null)throw new IllegalArgumentException("货币托管归属账结构损坏");
            JsonObject root=JsonParser.parseString(raw).getAsJsonObject();
            if(root.get("version").getAsInt()!=1)throw new IllegalArgumentException("未知货币托管归属账版本");
            for(JsonElement row:root.getAsJsonArray("entries")) {
                JsonObject r=row.getAsJsonObject(),s=r.getAsJsonObject("scope");
                Scope scope=new Scope(s.get("app").getAsString(),s.get("author").getAsString(),uuid(s.get("owner").getAsString()),s.has("revision")?s.get("revision").getAsString():"",s.has("version")?s.get("version").getAsBigDecimal().longValueExact():0);
                State state=State.valueOf(r.get("state").getAsString());
                Entry entry=new Entry(uuid(r.get("id").getAsString()),r.get("currency").getAsString(),scope,
                        uuid(r.get("beneficiary").getAsString()),r.get("amount").getAsBigDecimal().longValueExact(),
                        r.get("createdAt").getAsBigDecimal().longValueExact(),state==State.SETTLING?State.UNKNOWN:state,
                        r.get("settledAt").getAsBigDecimal().longValueExact());
                if(entry.amount()<1||entry.createdAt()<0||!entry.currency().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                        ||entry.currency().length()>64||entries.putIfAbsent(entry.id(),entry)!=null||entries.size()>MAX_ENTRIES)
                    throw new IllegalArgumentException("货币托管归属条目损坏");
            }
            persist();
        }catch(java.io.IOException bad){throw new IllegalStateException("货币托管归属账不可读",bad);}
    }
    private static UUID uuid(String raw){UUID id=UUID.fromString(raw);if(!id.toString().equals(raw))throw new IllegalArgumentException("UUID 不规范");return id;}
    public HoldResult hold(Scope scope,String currency,UUID from,UUID beneficiary,long amount,Supplier<HoldResult> operation) {
        if(!scope.owner().equals(from))return HoldResult.fail(TxnResult.NOT_AUTHORIZED);
        if(beneficiary==null||amount<=0)return HoldResult.fail(TxnResult.INVALID);
        sweep();requireWritable();
        // 在触达钱包前预留最坏条目空间；归属尚未保存的托管号绝不能返回脚本。
        if(entries.size()>=MAX_ENTRIES||encoded().length+1024>MAX_BYTES)return HoldResult.fail(TxnResult.LIMIT);
        HoldResult result=Objects.requireNonNull(operation.get(),"钱包返回空托管结果");
        if(result.result()!=TxnResult.OK)return result;
        UUID id=result.id().value();
        if(entries.containsKey(id))throw new IllegalStateException("钱包重复签发托管号，结果须核对");
        entries.put(id,new Entry(id,currency,scope,beneficiary,amount,clock.getAsLong(),State.HELD,0));
        persist();return result;
    }
    public TxnResult settle(Scope scope,String currency,EscrowId id,Supplier<TxnResult> operation) {
        requireWritable();Entry entry=id==null?null:entries.get(id.value());
        if(entry==null)return TxnResult.UNKNOWN_ESCROW;
        if(!entry.currency().equals(currency)||!entry.scope().sameAuthority(scope))return TxnResult.NOT_AUTHORIZED;
        if(entry.state()==State.SETTLED)return TxnResult.ALREADY_SETTLED;
        if(entry.state()!=State.HELD)throw new IllegalStateException("货币托管结果待核对，禁止自动重试");
        replace(entry,State.SETTLING,0);persist();
        try {
            TxnResult result=Objects.requireNonNull(operation.get(),"钱包返回空结算结果");
            boolean settled=result==TxnResult.OK||result==TxnResult.ALREADY_SETTLED;
            replace(entry,settled?State.SETTLED:State.HELD,settled?clock.getAsLong():0);persist();return result;
        }catch(RuntimeException|Error uncertain) {
            replace(entry,State.UNKNOWN,0);
            try{persist();}catch(RuntimeException bad){uncertain.addSuppressed(bad);}throw uncertain;
        }
    }
    /** 只供原生管理：管理员先核对钱包流水，再明确确认已结算或恢复可结算。 */
    public void resolve(UUID id,boolean settled) {
        requireWritable();Entry entry=entries.get(id);
        if(entry==null||entry.state()!=State.UNKNOWN)throw new IllegalArgumentException("不是待核对的货币托管");
        replace(entry,settled?State.SETTLED:State.HELD,settled?clock.getAsLong():0);persist();
    }
    public List<Entry> entries(){return List.copyOf(entries.values());}
    /** 统一原生核对入口同步自动退款与脚本归属；同一结果不需要分别操作两本账。 */
    public void settlementResolved(UUID id,boolean occurred){
        requireWritable();Entry entry=entries.get(id);if(entry==null)return;
        if(entry.state()!=State.HELD&&entry.state()!=State.UNKNOWN)throw new IllegalArgumentException("货币归属不在待核对状态");
        replace(entry,occurred?State.SETTLED:State.HELD,occurred?clock.getAsLong():0);persist();
    }
    private void replace(Entry e,State state,long at){entries.put(e.id(),new Entry(e.id(),e.currency(),e.scope(),e.beneficiary(),e.amount(),e.createdAt(),state,at));}
    private void sweep(){long now=clock.getAsLong();if(entries.values().removeIf(e->e.state()==State.SETTLED&&now-e.settledAt()>=EscrowLedger.SETTLED_KEEP_MS))persist();}
    private byte[] encoded(){JsonObject root=new JsonObject();root.addProperty("version",1);root.add("entries",new Gson().toJsonTree(entries.values()));return root.toString().getBytes(StandardCharsets.UTF_8);}
    private void requireWritable(){if(locked)throw new IllegalStateException("货币托管归属账已锁住");}
    private void persist(){requireWritable();try{byte[] bytes=encoded();if(bytes.length>MAX_BYTES)throw new IllegalStateException("货币托管归属账已满");if(file!=null)ScriptStateData.atomicWrite(file,bytes);}catch(Exception bad){locked=true;throw new IllegalStateException("货币托管归属保存失败，禁止重试钱包操作",bad);}}
}
