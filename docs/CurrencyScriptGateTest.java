package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.*;
import com.november.mcphone.api.economy.Currency;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 主线程落地前重查授权；拒绝不进入钱包，流水 App 身份不能依赖脚本传参。 */
public final class CurrencyScriptGateTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[]args)throws Exception {
        var coin=new Currency(net.minecraft.resources.ResourceLocation.parse("t:coin"),net.minecraft.network.chat.Component.literal("coin"),"C",0,null);
        List<String> calls=new ArrayList<>();AtomicLong balance=new AtomicLong();AtomicReference<Runnable> nested=new AtomicReference<>();
        ICurrencyProvider inner=(ICurrencyProvider)java.lang.reflect.Proxy.newProxyInstance(CurrencyScriptGateTest.class.getClassLoader(),new Class<?>[]{ICurrencyProvider.class},(proxy,method,a)->switch(method.getName()){
            case "currency"->coin;case "allowNegative"->false;case "maxBalance"->Long.MAX_VALUE;case "isAvailable"->true;case "unavailableReasonKey"->"";
            case "balance"->{calls.add("balance:"+CallingApp.current());yield balance.get();}
            case "mint"->{calls.add("mint:"+CallingApp.current());balance.addAndGet((Long)a[1]);if(nested.get()!=null)nested.get().run();yield TxnResult.OK;}
            case "burn"->{calls.add("burn:"+CallingApp.current());balance.addAndGet(-(Long)a[1]);yield TxnResult.OK;}
            default->throw new AssertionError(method.getName());
        });
        var gateway=new CurrencyGateway(Runnable::run,()->true);gateway.open();var registry=new CurrencyRegistry(gateway);registry.register(inner,true);
        AtomicReference<TxnResult> policy=new AtomicReference<>(TxnResult.OK);var scoped=registry.forScript("t:app",op->policy.get());UUID owner=UUID.randomUUID();var reason=new TxnReason("test","a");
        CallingApp.enter("outside");check(scoped.get("t:coin").mint(owner,5,reason)==TxnResult.OK,"批准后调用钱包");
        check(balance.get()==5&&calls.equals(List.of("mint:t:app")),"实际流水 App 是宿主绑定的身份");check(CallingApp.current().equals("outside"),"完成恢复外层 App 标签");
        policy.set(TxnResult.NOT_AUTHORIZED);int before=calls.size();
        try{scoped.get("t:coin").mint(owner,5,reason);throw new AssertionError("撤销后仍铸币");}catch(GatedCurrencyProvider.AuthorizationRefused expected){checks++;}
        check(calls.size()==before&&balance.get()==5,"确定拒绝不进入 provider，不改变余额");
        try{scoped.get("t:coin").balance(owner);throw new AssertionError("撤销后还读余额");}catch(GatedCurrencyProvider.AuthorizationRefused expected){checks++;}
        policy.set(TxnResult.UNAVAILABLE);try{scoped.get("t:coin").burn(owner,1,reason);throw new AssertionError("功能关闭仍扣钱");}catch(GatedCurrencyProvider.AuthorizationRefused expected){check(expected.error().resultCode()==com.november.mcphone.core.script.net.ScriptErrorCode.UNAVAILABLE,"关闭与未审批区分");}
        policy.set(TxnResult.OK);check(scoped.get("t:coin").burn(owner,1,reason)==TxnResult.OK&&balance.get()==4,"恢复许可后正常销毁");
        var unbound=new CurrencyRegistry();unbound.register(inner,true);try{unbound.forScript("t:app",op->TxnResult.OK);throw new AssertionError("脚本注册表可绕开主线程网关");}catch(IllegalStateException expected){checks++;}

        Thread main=Thread.currentThread();BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();var queued=new CurrencyGateway(queue::add,()->Thread.currentThread()==main);queued.open();var second=new CurrencyRegistry(queued);second.register(inner,true);AtomicBoolean allowed=new AtomicBoolean(true);var bound=second.forScript("t:queued",op->allowed.get()?TxnResult.OK:TxnResult.NOT_AUTHORIZED);
        int oldCalls=calls.size();var future=CompletableFuture.supplyAsync(()->bound.get("t:coin").mint(owner,100,reason));Runnable task=queue.poll(1,TimeUnit.SECONDS);check(task!=null,"钱包调用排到服务器主线程");allowed.set(false);task.run();
        try{future.get(1,TimeUnit.SECONDS);throw new AssertionError("排队期间撤销未生效");}catch(ExecutionException expected){check(expected.getCause() instanceof GatedCurrencyProvider.AuthorizationRefused,"主线程取出任务时重新拒绝");}
        check(calls.size()==oldCalls&&balance.get()==4,"排队撤销窗口没有触达钱包");
        var denied=registry.forScript("t:inner",op->TxnResult.NOT_AUTHORIZED);nested.set(()->denied.get("t:coin").mint(owner,1,reason));
        try{scoped.get("t:coin").mint(owner,2,reason);throw new AssertionError("钱包嵌套拒绝被吞掉");}catch(IllegalStateException expected){checks++;}
        check(balance.get()==6,"钱包进入后可能已动钱，嵌套拒绝不能被当作确定未执行");
        queued.close();gateway.close();CallingApp.leave();System.out.println("CurrencyScriptGateTest: "+checks+" passed");
    }
}
