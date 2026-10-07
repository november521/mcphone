package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** 托管物品先登记再扣除；跨玩家存档与收件箱的崩溃窗口只允许人工核对。 */
public final class ItemEscrowLedger {
    public enum State{CAPTURING,HELD,DELIVERING,UNKNOWN_CAPTURE,UNKNOWN_DELIVERY}
    public record Entry(String id,UUID owner,UUID recipient,String app,String dimension,String stack,long expiresAt,State state,UUID target,String revision,long version,String author){
        public Entry{revision=revision==null?"":revision;author=author==null?"":author;}
        public Entry(String id,UUID owner,UUID recipient,String app,String dimension,String stack,long expiresAt,State state,UUID target){this(id,owner,recipient,app,dimension,stack,expiresAt,state,target,"",0,"");}
    }
    private final Path file;private final LongSupplier clock;private final Supplier<QuotaConfig> quotas;private final ToIntFunction<UUID> mailboxSlots;
    private final Map<String,Entry> entries=new LinkedHashMap<>();private boolean locked;
    public ItemEscrowLedger(Path file,LongSupplier clock,Supplier<QuotaConfig> quotas,ToIntFunction<UUID> mailboxSlots){this.file=file;this.clock=clock;this.quotas=quotas;this.mailboxSlots=mailboxSlots;if(file!=null&&!Files.notExists(file))try{
        if(Files.isSymbolicLink(file)||Files.size(file)>64*1024*1024)throw new IllegalArgumentException("托管文件超额或是链接");String raw=Files.readString(file,StandardCharsets.UTF_8);if(JsonScan.check(raw,6)!=null)throw new IllegalArgumentException("托管结构损坏");JsonArray rows=JsonParser.parseString(raw).getAsJsonArray();if(rows.size()>4096)throw new IllegalArgumentException("托管条目超额");Gson gson=new Gson();for(JsonElement item:rows){Entry e=gson.fromJson(item,Entry.class);validate(e);State state=e.state()==State.CAPTURING?State.UNKNOWN_CAPTURE:e.state()==State.DELIVERING?State.UNKNOWN_DELIVERY:e.state();Entry recovered=new Entry(e.id(),e.owner(),e.recipient(),e.app(),e.dimension(),e.stack(),e.expiresAt(),state,e.target(),e.revision(),e.version(),e.author());if(entries.putIfAbsent(e.id(),recovered)!=null)throw new IllegalArgumentException("重复托管");}persist();
    }catch(java.io.IOException bad){throw new IllegalStateException("托管无法读取",bad);}}
    private static void validate(Entry e){if(e==null||!e.id().matches("[0-9a-f]{32}")||e.owner()==null||e.recipient()==null||!e.app().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||e.app().length()>64||e.dimension().length()>128||e.stack().isEmpty()||e.stack().length()>65536||e.expiresAt()<0||e.state()==null||e.target()!=null&&!e.target().equals(e.owner())&&!e.target().equals(e.recipient())||e.version()<0||!e.revision().isEmpty()&&!e.revision().matches("[0-9a-f]{64}")||e.author().length()>128)throw new IllegalArgumentException("托管条目损坏");}
    public List<Entry> entries(){return List.copyOf(entries.values());}
    public Entry entry(String id){Entry e=entries.get(id);if(e==null)throw new IllegalArgumentException("没有此托管记录");return e;}
    public int used(UUID owner){return (int)entries.values().stream().filter(e->e.owner().equals(owner)).count();}
    public int mailboxCount(UUID owner){return (int)entries.values().stream().filter(e->e.owner().equals(owner)&&!(e.state()==State.DELIVERING&&owner.equals(e.target()))).count();}
    public boolean canCapture(UUID owner,String app){long appUsed=entries.values().stream().filter(e->e.owner().equals(owner)&&e.app().equals(app)).count();return !locked&&entries.size()<4096&&used(owner)<quotas.get().get("escrow.per_player")&&appUsed<quotas.get().get("escrow.per_app")&&used(owner)+mailboxSlots.applyAsInt(owner)<quotas.get().get("items.per_player");}
    public Entry capture(UUID owner,UUID recipient,String app,String dimension,String stack){return capture(owner,recipient,app,dimension,stack,false);}
    public Entry capture(UUID owner,UUID recipient,String app,String dimension,String stack,boolean destroy){return capture(owner,recipient,app,dimension,stack,destroy,"",0,"");}
    public Entry capture(UUID owner,UUID recipient,String app,String dimension,String stack,boolean destroy,String revision,long version,String author){if(destroy?locked||entries.size()>=4096||!owner.equals(recipient):!canCapture(owner,app))throw new com.november.mcphone.core.script.server.store.StoreQuota.QuotaExceeded("物品托管已满");Entry e=new Entry(UUID.randomUUID().toString().replace("-",""),owner,recipient,app,dimension,stack,Math.addExact(clock.getAsLong(),30L*86400000),State.CAPTURING,null,revision,version,author);validate(e);entries.put(e.id(),e);persist();return e;}
    public void held(String id){Entry e=entry(id);if(e.state()!=State.CAPTURING)throw new IllegalStateException("扣除状态不符");replace(e,State.HELD,null);}
    public Entry delivery(String id,UUID target){Entry e=entry(id);if(e.state()!=State.HELD||!target.equals(e.owner())&&!target.equals(e.recipient()))throw new IllegalStateException("交付状态不符");replace(e,State.DELIVERING,target);return entry(id);}
    public void refused(String id){Entry e=entry(id);if(e.state()!=State.DELIVERING)throw new IllegalStateException("交付状态不符");replace(e,State.HELD,null);}
    public void complete(String id){Entry e=entry(id);if(e.state()!=State.DELIVERING)throw new IllegalStateException("交付状态不符");entries.remove(id);persist();}
    public void unknown(String id){Entry e=entry(id);State state=switch(e.state()){case CAPTURING->State.UNKNOWN_CAPTURE;case DELIVERING->State.UNKNOWN_DELIVERY;default->throw new IllegalStateException("没有执行中的托管");};replace(e,state,e.target());}
    public void resolve(String id,boolean effectOccurred){Entry e=entry(id);if(e.state()==State.UNKNOWN_CAPTURE){if(effectOccurred)replace(e,State.HELD,null);else{entries.remove(id);persist();}}else if(e.state()==State.UNKNOWN_DELIVERY){if(effectOccurred){entries.remove(id);persist();}else replace(e,State.HELD,null);}else throw new IllegalStateException("不是待核对托管");}
    private void replace(Entry e,State state,UUID target){entries.put(e.id(),new Entry(e.id(),e.owner(),e.recipient(),e.app(),e.dimension(),e.stack(),e.expiresAt(),state,target,e.revision(),e.version(),e.author()));persist();}
    private void persist(){if(locked)throw new IllegalStateException("托管存档已锁住");try{byte[] bytes=new Gson().toJson(entries.values()).getBytes(StandardCharsets.UTF_8);if(bytes.length>64*1024*1024)throw new IllegalStateException("托管全服空间已满");if(file!=null){if(Files.isSymbolicLink(file))throw new IllegalStateException("托管文件不能是链接");ScriptStateData.atomicWrite(file,bytes);}}catch(Exception bad){locked=true;throw new IllegalStateException("托管保存失败，停止扣除与交付",bad);}}
}
