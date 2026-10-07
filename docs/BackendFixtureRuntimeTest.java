package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.*;
import java.nio.file.*;
import java.util.*;
import com.november.mcphone.core.script.server.*;
import com.november.mcphone.core.script.net.ScriptErrorCode;

/** 实际验收脚本必须能够装载并注册动作，静态语法通过不能代替运行期初始化。 */
public final class BackendFixtureRuntimeTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("demo-apps/completion")))root=root.getParent();
        if(root==null)throw new AssertionError("缺少验收脚本源码");
        String source=Files.readString(root.resolve("demo-apps/completion/server/server.js"));
        var app=new AppScope("completion:server",ScriptBudget.server(),Map.of("server.js",source));
        try(Context cx=app.budget().enterContext()){
            var invocation=app.beginInvocation(cx);
            check(invocation.actions()!=null,"实际入口成功初始化动作表");
            for(String name:List.of("read","wallet","claim","loot","mail","kv","notify","hold","refund","release","mint","burn","offer","resource","fetch","background"))
                check(ScriptableObject.getProperty(invocation.actions(),name) instanceof Function,"注册动作："+name);
            // 使用真实 ctx getter、周期和结果编码；只有世界快照数据由夹具提供。
            var player=new PlayerSnapshot(new UUID(0,1),"tester","minecraft:overworld","creative",0);
            var backends=new CtxBuilder.Backends(new SharedState(),null,
                new CtxBuilder.Cycle(java.time.ZoneId.of("UTC"),java.time.LocalTime.MIDNIGHT)).withReads((cap,offset)->switch(cap){
                    case "read.self.position"->Map.of("x","1.5","y","64","z","-2","dimension","minecraft:overworld");
                    case "read.world.time"->"24000";
                    case "read.players.online_count"->1;
                    default->throw new AssertionError("意外读取："+cap);
                });
            var result=new CtxBuilder.Result();
            var ctx=CtxBuilder.build(cx,invocation.callScope(),app.appId(),player,backends,result);
            ((Function)ScriptableObject.getProperty(invocation.actions(),"read")).call(cx,invocation.callScope(),invocation.actions(),new Object[]{ctx});
            var data=com.november.mcphone.core.script.JsonValues.object(result.dataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            check(result.code==ScriptErrorCode.OK&&data.get("uuid").equals(player.uuid().toString()),"实际 ctx 读取函数返回可编码的身份数据");
            check(data.get("position") instanceof Map<?,?>&&data.get("players").equals(1)&&data.get("cycle") instanceof String,"位置、人数和周期返回完整");
            var deniedResult=new CtxBuilder.Result();
            var denied=CtxBuilder.build(cx,invocation.callScope(),app.appId(),player,backends,deniedResult,new MoneyLedger(),cap->{throw HostError.denied(ScriptErrorCode.NOT_AUTHORIZED,"test.denied","关闭读取");});
            boolean blocked=false;
            try{((Function)ScriptableObject.getProperty(invocation.actions(),"read")).call(cx,invocation.callScope(),invocation.actions(),new Object[]{denied});}
            catch(HostError expected){blocked=true;}
            check(blocked&&deniedResult.code==null,"初始化动作表不能绕过真实 ctx 能力门");
            check(app.actions(cx)==invocation.actions(),"动作表跨调用保持同一实例");
            var other=new AppScope("test:other",ScriptBudget.server(),Map.of("server.js","actions.only=function(){};"));
            check(!ScriptableObject.hasProperty(other.actions(cx),"read"),"默认动作表按 App 隔离");
            var declared=new AppScope("test:declared",ScriptBudget.server(),Map.of("server.js","var actions={legacy:function(){return 7;}};"));
            check(ScriptableObject.getProperty(declared.actions(cx),"legacy") instanceof Function,"兼容显式声明动作表");
        }
        System.out.println("BackendFixtureRuntimeTest: "+checks+" passed");
    }
}
