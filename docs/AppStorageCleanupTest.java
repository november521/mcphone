package com.november.mcphone.core.script.server.store;

import com.november.mcphone.core.script.server.*;
import java.nio.file.*;
import java.util.*;

/** 清理释放真实持久预算且不复活旧附件，其他玩家、App 与密文均保留。 */
public final class AppStorageCleanupTest {
    private static int checks;private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("mcphone-storage-cleanup");UUID player=UUID.randomUUID(),other=UUID.randomUUID();String server=UUID.randomUUID().toString();String ns=ScriptKv.namespace(server,"app:t:a","t:a",1),old=ScriptKv.namespace(server,"app:t:a","t:a",2),nsOther=ScriptKv.namespace(server,"app:t:b","t:b",1);
        DurablePlayerStore store=new DurablePlayerStore(root);ScriptKv kv=ScriptKv.DEFAULT.with(ns,"note","中文").with(old,"old","历史").with(nsOther,"keep","保留");store.writeKv(player,kv);store.writeKv(other,kv);SealedRecord secret=new SealedRecord(new byte[16],new byte[12],new byte[256],1,1);store.sealed(player,ns,"secret",secret);
        var usage=store.appUsage(player,"t:a");check(usage.keys()==2&&usage.kvBytes()>0&&usage.sealedBytes()>0,"统计所有此 App 命名空间，不返回明文");long before=store.playerBytes(player);store.clearAppKv(player,"t:a");check(store.appUsage(player,"t:a").keys()==0&&store.playerBytes(player)<before,"删除普通 KV 后真实预算下降");check(store.sealed(player,ns,"secret").equals(secret),"保险箱密文原样保留");check(store.kv(player,kv).get(ScriptKv.fullKey(nsOther,"keep")).equals("保留"),"其他 App 不受影响");check(store.appUsage(other,"t:a").keys()==2,"其他玩家不受影响");
        store=new DurablePlayerStore(root);check(store.kv(player,kv).get(ScriptKv.fullKey(ns,"note"))==null,"重启后旧附件不能复活已删除 KV");check(store.appUsage(player,"t:a").sealedBytes()==usage.sealedBytes(),"重启保留密文和占用");store.clearAppKv(player,"t:a");check(store.appUsage(player,"t:a").kvBytes()==0,"重复清理不损坏存档");check(HostControls.ACTIONS.containsAll(Set.of("storage.show","storage.clear")),"独立宿主路由已经登记");System.out.println("AppStorageCleanupTest: "+checks+" passed");
    }
}
