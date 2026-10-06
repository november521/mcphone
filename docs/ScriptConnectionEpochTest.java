package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.engine.HostError;
import com.november.mcphone.core.script.net.*;
import java.util.*;
import java.util.function.Consumer;

/** 玩家 UUID 相同不代表还是原来的连接；排队请求不得跨重连开始或落地。 */
public final class ScriptConnectionEpochTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    public static void main(String[] args){
        UUID player=UUID.randomUUID();var snapshot=new PlayerSnapshot(player,"tester","minecraft:overworld","survival",0);
        for(boolean moved:new boolean[]{false,true}){
            ActionEvaluator.Request[] request={null};List<Consumer<ActionEvaluator.Outcome>> callbacks=new ArrayList<>();int[] landed={0};
            ActionEvaluator evaluator=(r,done)->{request[0]=r;callbacks.add(done);return true;};
            var pipeline=new ScriptPipeline(UUID.randomUUID(),new IdempotencyLedger(System::currentTimeMillis),new ScriptRateLimiter(System::currentTimeMillis),
                IntentLandingTest.deployed("rev",Set.of("item.give")),(p,a,action)->true,evaluator,(p,intents)->{landed[0]++;return IntentApplier.Landed.ok();},new CapabilityPolicy(CapabilityConfig.defaults()));
            long epoch=pipeline.newEpoch(player);var rpc=new ScriptRpc(ScriptProtocol.PROTOCOL,1,epoch,"example:app","rev","act","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),"once");
            List<ScriptRpcResult> results=new ArrayList<>();pipeline.accept(rpc,snapshot,results::add);
            check(request[0].connectionEpoch()==epoch,"求值请求绑定真实连接 epoch");
            check(request[0].requestId()==rpc.requestId(),"物品句柄绑定原始 RPC 请求号而不是日志序号");
            if(moved)request[0].beginEffects().run();
            pipeline.forget(player);check(pipeline.newEpoch(player)!=epoch,"重连使用新 epoch");
            if(!moved)try{request[0].beginEffects().run();throw new AssertionError("旧连接还可开始效果");}catch(HostError expected){checks++;}
            var outcome=ActionEvaluator.Outcome.ok(new byte[0],0,List.of(ActionIntent.itemGive("minecraft:diamond",1,"")));
            callbacks.get(0).accept(moved?outcome.withMoneyMoved():outcome);
            check(landed[0]==0,"旧连接请求不能向新连接发物品");
            check(results.size()==1&&results.get(0).code()==(moved?ScriptErrorCode.UNKNOWN:ScriptErrorCode.NOT_AUTHORIZED),"已动钱按 UNKNOWN，尚未执行按拒绝，且只回一次");
        }
        System.out.println("ScriptConnectionEpochTest: "+checks+" passed");
    }
}
