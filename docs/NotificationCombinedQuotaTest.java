package com.november.mcphone.core.script.server;
import com.november.mcphone.core.script.server.store.*;
import com.november.mcphone.api.sdk.notify.Priority;
import java.nio.file.*;
import java.util.*;
public final class NotificationCombinedQuotaTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    public static void main(String[]args)throws Exception {
        Path root=Files.createTempDirectory("mcphone-notify-combined");var config=QuotaConfig.DEFAULT.with("data.per_player",700);var budget=new StorageBudget(()->config);
        UUID owner=UUID.randomUUID();String app="test:app",ns=ScriptKv.namespace(UUID.randomUUID().toString(),"app:"+app,app,1);
        var store=new DurablePlayerStore(root.resolve("kv"),()->config,budget);ScriptKv kv=ScriptKv.DEFAULT.with(ns,"k","x".repeat(300));store.writeKv(owner,kv);
        var inbox=new NotificationInbox(root.resolve("notice"),()->1000,budget);inbox.sync(owner,List.of(app),List.of());long before=budget.total();
        var message=new NotificationMessage("topic","test.title",List.of("x".repeat(64),"y".repeat(64),"z".repeat(64),"w".repeat(64)),null,List.of(),Priority.NORMAL,null,0);
        try{inbox.post(owner,app,message,false);throw new AssertionError("合并配额未限制通知");}catch(java.io.IOException expected){checks++;}
        check(inbox.list(owner).isEmpty()&&budget.total()==before,"配额拒绝不改变通知与合并计量");
        store.writeKv(owner,kv.without(ns,"k"));check(inbox.post(owner,app,message,false)!=null,"释放 KV 后通知可入账");
        check(budget.players().get(owner.toString())==budget.total(),"玩家排行包含通知空间");
        var restored=new StorageBudget(()->config);new DurablePlayerStore(root.resolve("kv"),()->config,restored);var again=new NotificationInbox(root.resolve("notice"),()->1000,restored);
        check(restored.total()==budget.total()&&again.list(owner).size()==1,"重启计量与通知一致");
        inbox.sync(owner,List.of(),List.of());check(budget.total()<restored.total(),"卸载释放通知占用");
        System.out.println("NotificationCombinedQuotaTest: "+checks+" passed");
    }
}
