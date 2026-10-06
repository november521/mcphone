package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.engine.*;
import com.november.mcphone.core.script.server.store.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 真正穿过存储、审计和文件重开，覆盖总量跨机制竞争与降低配额。 */
public final class QuotaTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void rejects(Runnable operation,String why){boolean bad=false;try{operation.run();}catch(IllegalArgumentException|HostError|StoreQuota.QuotaExceeded error){bad=true;}check(bad,why);}
    public static void main(String[] args)throws Exception {
        for(var spec:QuotaConfig.SPECS){check(QuotaConfig.DEFAULT.get(spec.key())==spec.initial(),"默认 "+spec.key());check(QuotaConfig.DEFAULT.with(spec.key(),spec.minimum()).get(spec.key())==spec.minimum(),"下限");check(QuotaConfig.DEFAULT.with(spec.key(),spec.maximum()).get(spec.key())==spec.maximum(),"上限");rejects(()->QuotaConfig.DEFAULT.with(spec.key(),spec.minimum()-1),"下界不可逃逸");rejects(()->QuotaConfig.DEFAULT.with(spec.key(),spec.maximum()+1),"上界不可逃逸");}
        rejects(()->QuotaConfig.parse("{\"format\":1,\"limits\":{\"kv.keys\":1.5}}"),"拒绝小数");rejects(()->QuotaConfig.DEFAULT.with("unknown",5),"未知机制");
        Path world=Files.createTempDirectory("mcphone-quotas");QuotaManager manager=new QuotaManager(world);manager.set(null,"kv.per_player_app",0);check(new QuotaManager(world).current().get("kv.per_player_app")==0,"确认前配额已落盘");
        Path audit=world.resolve("mcphone/audit");try(var stream=Files.list(audit)){String row=Files.readString(stream.findFirst().orElseThrow());check(row.contains("\"before\":8192")&&row.contains("\"after\":0")&&row.contains("\"actor\":\"console\""),"前后数值和控制台身份审计");}
        Files.writeString(world.resolve(QuotaManager.FILE),"{bad}");rejects(()->{try{manager.reload();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}},"坏配置被拒");check(manager.current().get("kv.per_player_app")==0,"坏重载保留旧快照");
        AtomicReference<QuotaConfig> q=new AtomicReference<>(QuotaConfig.DEFAULT.with("data.server",300).with("kv.per_app",300));StorageBudget budget=new StorageBudget(q::get);SharedState shared=new SharedState();shared.quotas(q::get,budget);UUID p=UUID.randomUUID(),server=UUID.randomUUID();String ns=ScriptKv.namespace(server.toString(),"app:t:app","t:app",1);
        Path dir=world.resolve("players");DurablePlayerStore store=new DurablePlayerStore(dir,q::get,budget);shared.set("t:app","shared","x".repeat(100));ScriptKv kv=ScriptKv.DEFAULT.with(ns,"k","v");store.writeKv(p,kv);check(budget.total()==shared.totalBytes()+store.playerBytes(p),"共享与玩家空间统一计量");
        rejects(()->store.writeKv(p,kv.with(ns,"k","x".repeat(200))),"两个机制合计突破 300 字节被拒");check(store.kv(p,ScriptKv.DEFAULT).get(ScriptKv.fullKey(ns,"k")).equals("v"),"拒绝不改内存");check(new DurablePlayerStore(dir).kv(p,ScriptKv.DEFAULT).get(ScriptKv.fullKey(ns,"k")).equals("v"),"拒绝不改磁盘");
        q.set(q.get().with("data.server",0).with("kv.per_app",0).with("data.per_player",0));rejects(()->shared.set("t:app","new","x"),"降低至零拒绝新增");check(shared.get("t:app","shared").length()==100,"降低保留旧值");shared.set("t:app","shared","x");store.writeKv(p,kv.without(ns,"k"));check(budget.total()==shared.totalBytes(),"超额状态仍可释放空间");
        q.set(QuotaConfig.DEFAULT.with("data.server",100).with("kv.per_app",100));StorageBudget concurrent=new StorageBudget(q::get);AtomicInteger successes=new AtomicInteger();Thread one=new Thread(()->{try{concurrent.replace("one",Map.of("t:app",60L),successes::incrementAndGet);}catch(StoreQuota.QuotaExceeded ignored){}}),two=new Thread(()->{try{concurrent.replace("two",Map.of("t:app",60L),successes::incrementAndGet);}catch(StoreQuota.QuotaExceeded ignored){}});one.start();two.start();one.join();two.join();check(successes.get()==1&&concurrent.total()==60,"并发写入只有一个能占用总量");
        MailboxLedger mailbox=new MailboxLedger(world.resolve("mailbox.json"),()->0).quotas(q::get,uuid->0);q.set(QuotaConfig.DEFAULT.with("mailbox.per_player",54));check(mailbox.deposit(p,Collections.nCopies(40,"{}"),"test"),"提高容量可存 40 格");q.set(q.get().with("mailbox.per_player",0));check(!mailbox.deposit(p,List.of("{}"),"test"),"关闭只拒绝新增");MailboxLedger restart=new MailboxLedger(world.resolve("mailbox.json"),()->0);check(restart.entries(p).size()==40,"超过当前默认仍可重开不丢东西");
        System.out.println("QuotaTest: "+checks+" passed");
    }
}
