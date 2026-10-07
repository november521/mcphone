package com.november.mcphone.core.script.engine;

import com.november.mcphone.api.sdk.mailbox.DepositResult;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 真实 Rhino 物品引用与收件箱桥：不执行访问器、不信显示投影，确定未执行与未知结果分开。 */
public final class ScriptMailboxTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    public static void main(String[]args){
        String token="a".repeat(32),uuid=UUID.randomUUID().toString();AtomicInteger effects=new AtomicInteger();AtomicReference<DepositResult> verdict=new AtomicReference<>(DepositResult.OK);AtomicBoolean fail=new AtomicBoolean();
        var mailbox=new CtxBuilder.MailboxView(){
            public int count(UUID player){return 3;}
            public DepositResult deposit(UUID player,List<String> handles,String reason,Runnable before){
                check(handles.equals(List.of(token)),"只传宿主句柄");if(verdict.get()!=DepositResult.OK)return verdict.get();
                before.run();effects.incrementAndGet();if(fail.get())throw new IllegalStateException("保存窗口");return DepositResult.OK;
            }
        };
        var backend=new CtxBuilder.Backends(null,null,null,null,null,null,true).withMailbox(mailbox);
        String deposit="ctx.mailbox.deposit('"+uuid+"',[{id:'minecraft:diamond',count:99,opaque:'"+token+"'}],'roundtrip')";
        check(ScriptEngineTest.withCtx(deposit,backend).equals("OK")&&effects.get()==1,"存入成功");
        check(ScriptEngineTest.withCtx("ctx.mailbox.count('"+uuid+"')",backend).equals("3"),"统一收件箱件数");
        verdict.set(DepositResult.FULL);check(ScriptEngineTest.withCtx(deposit,backend).equals("FULL")&&effects.get()==1,"满箱一件也不进入副作用");
        check(ScriptEngineTest.withCtx("ctx.mailbox.deposit('bad',[], 'x')",backend).equals("INVALID"),"非法 UUID 明确拒绝");
        check(ScriptEngineTest.withCtx("ctx.mailbox.deposit('"+uuid+"',[],'empty')",backend).equals("INVALID"),"空数组拒绝");
        check(ScriptEngineTest.withCtx("var r={};Object.defineProperty(r,'opaque',{get:function(){throw 'getter'}});try{ctx.mailbox.deposit('"+uuid+"',[r],'x')}catch(e){'bad'}",backend).equals("bad"),"访问器不执行");
        verdict.set(DepositResult.OK);check(ScriptEngineTest.withCtx(deposit+";try{ctx.give([{opaque:'"+token+"'}])}catch(e){'used'}",backend).equals("used"),"搬入后同引用不能再发放");
        fail.set(true);check(ScriptEngineTest.thrownBy(deposit,backend) instanceof OutcomeUnknown,"未知结果是不能被脚本吞掉的中断");
        check(effects.get()==3,"不明结果没有内部重试");
        System.out.println("ScriptMailboxTest: "+checks+" passed");
    }
}
