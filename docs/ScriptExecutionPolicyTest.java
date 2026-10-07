package com.november.mcphone.core.script.net;
import com.november.mcphone.core.script.server.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** 关闭脚本只阻断脚本执行，原生管理仍可工作；排队后关闭也不能落地效果。 */
public final class ScriptExecutionPolicyTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[]args){
        AtomicLong now=new AtomicLong();AtomicBoolean enabled=new AtomicBoolean(false);AtomicInteger evaluations=new AtomicInteger(),effects=new AtomicInteger();AtomicReference<Consumer<ActionEvaluator.Outcome>> done=new AtomicReference<>();
        UUID server=UUID.randomUUID(),player=UUID.randomUUID();var ledger=new IdempotencyLedger(now::get);var policy=new CapabilityPolicy(CapabilityConfig.parse("{}"));
        ScriptPipeline pipeline=new ScriptPipeline(server,ledger,new ScriptRateLimiter(now::get),ScriptRpcTest.allDeployed("rev1"),(a,b,c)->true,(request,callback)->{evaluations.incrementAndGet();done.set(callback);return true;},(uuid,intents)->{effects.addAndGet(intents.size());return IntentApplier.Landed.ok();},policy);
        pipeline.executionEnabled(enabled::get);pipeline.installHostControls((rpc,snapshot)->ScriptRpcResult.ok(rpc.requestId(),new byte[0],0));long epoch=pipeline.newEpoch(player);PlayerSnapshot snapshot=ScriptRpcTest.snap(player);List<ScriptRpcResult> replies=new ArrayList<>();
        pipeline.accept(ScriptRpcTest.rpc(1,epoch,new byte[0]),snapshot,replies::add);check(replies.get(0).code()==ScriptErrorCode.UNAVAILABLE&&evaluations.get()==0,"关闭时根本不进入 worker");
        replies.clear();pipeline.accept(new ScriptRpc(ScriptProtocol.PROTOCOL,2,epoch,ScriptProtocol.HOST_APP_ID,"","admin.list",new byte[0],""),snapshot,replies::add);check(replies.get(0).code()==ScriptErrorCode.OK,"管理员还能使用原生管理通道");
        replies.clear();pipeline.accept(new ScriptRpc(ScriptProtocol.PROTOCOL,3,epoch,ScriptProtocol.HOST_APP_ID,"","background.run",new byte[0],""),snapshot,replies::add);check(replies.get(0).code()==ScriptErrorCode.UNAVAILABLE&&evaluations.get()==0,"后台不能绕开关闭开关");
        enabled.set(true);replies.clear();pipeline.accept(ScriptRpcTest.rpc(4,epoch,new byte[0]),snapshot,replies::add);check(evaluations.get()==1&&replies.isEmpty(),"显式开启后可以排队");
        enabled.set(false);done.get().accept(ActionEvaluator.Outcome.ok(new byte[0],1,List.of(new ActionIntent(ActionIntent.MESSAGE_SELF,new byte[0]))));check(replies.get(0).code()==ScriptErrorCode.UNAVAILABLE&&effects.get()==0,"排队后关闭，意图不能落地");
        enabled.set(true);replies.clear();pipeline.accept(ScriptRpcTest.rpc(5,epoch,new byte[0]),snapshot,replies::add);enabled.set(false);done.get().accept(ActionEvaluator.Outcome.ok(new byte[0],1,List.of()).withMoneyMoved());check(replies.get(0).code()==ScriptErrorCode.UNKNOWN,"已动钱后关闭必须回不明结果");
        check(effects.get()==0,"关闭期间没有真实意图效果");System.out.println("ScriptExecutionPolicyTest: "+checks+" passed");
    }
}
