package com.november.mcphone.core.script.server.store;

import java.nio.file.*;
import java.util.*;

/** 不启动游戏：原子存储重开、数据隔离、全 App 配额、密文防回滚与不可变数组。 */
public final class DurablePlayerStoreTest {
    private static int checks;
    private static void check(boolean v,String why) { checks++; if(!v) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("mcphone-durable-kv"); UUID player=UUID.randomUUID(), other=UUID.randomUUID(), server=UUID.randomUUID();
        String ns=ScriptKv.namespace(server.toString(),"app:t:app","t:app",1);
        DurablePlayerStore store=new DurablePlayerStore(dir);
        ScriptKv original=ScriptKv.DEFAULT.with(ns,"text","中文😀");
        check(store.kv(player,original).equals(original),"首次导入附件立即落盘");
        check(Files.isRegularFile(dir.resolve(player+".json")),"确认前已经有快照");
        store.writeKv(player,original.with(ns,"text","updated"));
        var restarted=new DurablePlayerStore(dir); check(restarted.kv(player,ScriptKv.DEFAULT).get(ScriptKv.fullKey(ns,"text")).equals("updated"),"异常退出后读取最新确认值");
        check(restarted.kv(other,ScriptKv.DEFAULT).values().isEmpty(),"不能读取其他玩家的数据");
        byte[] salt=new byte[16], nonce=new byte[12], cipher=new byte[512];
        SealedRecord record=new SealedRecord(salt,nonce,cipher,1,1); salt[0]=99; cipher[0]=99;
        check(record.salt()[0]==0 && record.cipher()[0]==0,"构造时复制字节");
        byte[] received=record.cipher(); received[0]=33; check(record.cipher()[0]==0,"取出时复制字节");
        restarted.sealed(player,ns,"token",record);
        var again=new DurablePlayerStore(dir); check(again.sealed(player,ns,"token").recordVersion()==1,"密文版本跨重开保留");
        check(again.sealed(other,ns,"token")==null,"密文也按宿主玩家隔离");
        again.sealed(player,ns,"token",record);
        boolean rollback=false; cipher[0]=1;
        try { again.sealed(player,ns,"token",new SealedRecord(new byte[16],new byte[12],cipher,1,1)); } catch(IllegalArgumentException e) { rollback=true; }
        check(rollback,"同版本换内容拒绝，完全相同的重放可接受");
        for(int i=0;i<6;i++) again.sealed(player,ns,"t"+i,new SealedRecord(new byte[16],new byte[12],new byte[512],1,1));
        boolean full=false; try { again.sealed(player,ns,"eighth",record); } catch(StoreQuota.QuotaExceeded q) { full=true; }
        check(full,"密文和元数据一起计入 4 KiB 配额");
        ScriptKv all=again.kv(player,ScriptKv.DEFAULT);
        boolean aggregate=false;
        for(int i=0;i<80;i++) {
            String another=ScriptKv.namespace(server.toString(),"app:t:a"+i,"t:a"+i,1);
            ScriptKv candidate=all;
            for(int k=0;k<3;k++) candidate=candidate.with(another,"k"+k,"x".repeat(2048));
            try { again.writeKv(player,candidate); all=candidate; } catch(StoreQuota.QuotaExceeded q) { aggregate=true; break; }
        }
        check(aggregate,"每 App 都未满仍受玩家全 App 256 KiB 总闸限制");
        check(again.playerBytes(player)<=256*1024,"拒绝的写入没有增加占用");
        Files.writeString(dir.resolve(other+".json"),"{broken}");
        boolean corrupt=false; try { new DurablePlayerStore(dir); } catch(IllegalArgumentException e) { corrupt=true; }
        check(corrupt,"坏存档拒绝装配，不生成空账");
        System.out.println("DurablePlayerStoreTest: "+checks+" passed");
    }
}
