package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.client.BackgroundSchedule;
import com.november.mcphone.core.script.pkg.BackgroundDeclaration;
import com.november.mcphone.core.script.engine.*;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.core.script.server.store.KvBackend;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 同时覆盖调度、宿主批次与实际 Rhino 执行；后台拒绝发生在真实写入端之前。 */
public final class BackgroundTest {
    private static int checks;private static final String APP="test:app",REV="a".repeat(64);
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    private static void rejected(String json){try{BackgroundDeclaration.parse(json);throw new AssertionError("非法后台清单被接受");}catch(RuntimeException expected){checks++;}}
    private static PlayerSnapshot player(UUID id){return new PlayerSnapshot(id,"tester","minecraft:overworld","survival",0);}
    private static ActionEvaluator.Outcome run(ExecutorService main,RhinoEvaluator evaluator,String action,boolean background)throws Exception{
        CompletableFuture<ActionEvaluator.Outcome> done=new CompletableFuture<>();main.submit(()->check(evaluator.submit(new ActionEvaluator.Request(APP,action,"{}".getBytes(StandardCharsets.UTF_8),player(new UUID(0,1)),REV,1,()->{},background),done::complete),"实际 worker 接收后台请求")).get(10,TimeUnit.SECONDS);return done.get(10,TimeUnit.SECONDS);
    }
    public static void main(String[]args)throws Exception {
        var tasks=BackgroundDeclaration.parse("{\"actions\":[\"peek\"],\"background\":[{\"id\":\"check\",\"action\":\"peek\",\"interval\":\"1s\"}]}");check(tasks.get(0).intervalMs()==60000,"小于一分钟向上取整");
        rejected("{\"actions\":[],\"background\":[{\"id\":\"x\",\"action\":\"peek\",\"interval\":\"1m\"}]}");
        rejected("{\"actions\":[\"peek\"],\"background\":[{\"id\":\"x\",\"action\":\"peek\",\"interval\":\"25h\"}]}");
        BackgroundSchedule schedule=new BackgroundSchedule(()->.5);Set<String> installed=Set.of(APP);schedule.register(APP,tasks,0);
        check(schedule.due(60000,true,false,installed,installed,Set.of()).isEmpty(),"手机关闭间隔四倍");check(schedule.due(240000,false,false,installed,installed,Set.of()).isEmpty(),"离线完全不跑");
        var due=schedule.due(240000,true,false,installed,installed,Set.of());check(due.size()==1,"关闭手机四分钟到期");check(schedule.due(250000,true,false,installed,installed,Set.of()).isEmpty(),"在飞任务不重复发送");schedule.complete(due.get(0),true,240000,false);
        check(schedule.due(479999,true,false,installed,installed,Set.of()).isEmpty(),"成功后恢复基础关闭间隔");
        long now=480000;for(int i=1;i<=10;i++){var next=schedule.due(now,true,false,installed,installed,Set.of());check(next.size()==1,"失败退避后任务到期");schedule.complete(next.get(0),false,now,false);now+=240000L*(1L<<Math.min(i,4));}
        check(schedule.stopped(APP)&&schedule.due(now,true,false,installed,installed,Set.of()).isEmpty(),"连续失败十次停止");
        long[] clock={1000};BackgroundGate gate=new BackgroundGate(()->clock[0]);UUID p=new UUID(0,1);check(gate.allow(p)&&!gate.allow(p),"每玩家十秒一次");for(int i=2;i<=100;i++)check(gate.allow(new UUID(0,i)),"全服一秒前一百次");check(!gate.allow(new UUID(0,101)),"第一百零一次被拒");clock[0]+=10000;check(gate.allow(p),"十秒后恢复");
        AtomicInteger evaluations=new AtomicInteger(),effects=new AtomicInteger();IdempotencyLedger ledger=new IdempotencyLedger(()->1000);DeploymentView deployed=new DeploymentView(){public boolean deployed(String app){return app.equals(APP);}public boolean hasAction(String app,String action){return action.equals("peek");}public String deployRev(String app){return REV;}};
        ScriptPipeline pipeline=new ScriptPipeline(new UUID(1,1),ledger,new ScriptRateLimiter(()->1000),deployed,(id,app,action)->true,(request,done)->{check(request.background(),"服务端标记后台模式");evaluations.incrementAndGet();done.accept(ActionEvaluator.Outcome.ok("{}".getBytes(StandardCharsets.UTF_8),0,List.of()));done.accept(ActionEvaluator.Outcome.fail(ScriptErrorCode.INTERNAL));return false;});pipeline.installHostControls((rpc,snapshot)->ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT));pipeline.installBackground((app,action)->action.equals("peek"));long epoch=pipeline.newEpoch(p);
        List<ScriptRpcResult> replies=new ArrayList<>();String batch="{\"items\":[{\"app\":\"test:app\",\"action\":\"peek\",\"revision\":\""+REV+"\"}]}";
        pipeline.accept(new ScriptRpc(ScriptProtocol.PROTOCOL,1,epoch,ScriptProtocol.HOST_APP_ID,"","background.run",batch.getBytes(StandardCharsets.UTF_8),""),player(p),replies::add);
        check(replies.size()==1&&replies.get(0).code()==ScriptErrorCode.OK&&evaluations.get()==1,"合并批次恰好一次完成");check(ledger.size(p)==0,"后台查询不挤占奖励账本");
        ExecutorService main=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"background-test-main");t.setDaemon(true);return t;});ScriptWorkers.start();
        try{
            KvBackend kv=new KvBackend(){public String getString(String app,String key){return "own";}public List<String> keys(String app){return List.of("k");}public void setString(String app,String key,String value){effects.incrementAndGet();}public void remove(String app,String key){effects.incrementAndGet();}};
            String source="var actions={peek:function(ctx){ctx.ok({value:ctx.store.getString('k')})},write:function(ctx){ctx.store.setString('k','v');ctx.ok({})},shared:function(ctx){ctx.shared.set('k','v');ctx.ok({})},notify:function(ctx){ctx.notify.self('news',{titleKey:'test.news'});ctx.ok({})},give:function(ctx){ctx.give('minecraft:stone',1);ctx.ok({})},loop:function(ctx){while(true){}}};";
            var app=new AppScope(APP,ScriptBudget.server(),Map.of("server.js",source),Set.of("item.give"));var strikes=main.submit(()->new StrikeTracker(System::currentTimeMillis)).get();SharedState shared=new SharedState();var evaluator=new RhinoEvaluator(Map.of(APP,app),strikes,new CtxBuilder.Backends(shared,null,null,kv,null,null,true),main::execute,new CapabilityPolicy(CapabilityConfig.defaults()));
            run(main,evaluator,"peek",false);run(main,evaluator,"notify",false);
            check(run(main,evaluator,"peek",true).code()==ScriptErrorCode.OK,"实际后台允许读取本人存储");
            check(run(main,evaluator,"write",true).code()==ScriptErrorCode.NOT_AUTHORIZED&&effects.get()==0,"同一 storage.self 能力下写入仍被拒");
            check(run(main,evaluator,"shared",true).code()==ScriptErrorCode.NOT_AUTHORIZED&&shared.get(APP,"k")==null,"后台共享写被拒");
            var notification=run(main,evaluator,"notify",true);check(notification.code()==ScriptErrorCode.OK&&notification.intents().size()==1&&notification.intents().get(0).kind().equals(ActionIntent.NOTIFY_SELF),"后台允许通知本人");
            check(run(main,evaluator,"give",true).code()==ScriptErrorCode.NOT_AUTHORIZED,"前台已审批的发奖在后台也被拒");
            check(run(main,evaluator,"loop",true).code()==ScriptErrorCode.INTERNAL,"后台无限循环被预算终止");app.discard();
        }finally{main.shutdownNow();ScriptWorkers.stop();}
        System.out.println("BackgroundTest: "+checks+" 条通过");
    }
}
