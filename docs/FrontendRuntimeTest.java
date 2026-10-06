package com.november.mcphone.core.script.engine;

import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.sfc.*;
import java.util.*;
import java.util.function.Consumer;

/** 前端真实函数、延迟回调、关闭清理、同步重入预算及数据/沙箱边界。 */
public final class FrontendRuntimeTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static final class Bridge implements FrontendRuntime.Bridge {
        final List<Consumer<Map<String,Object>>> pending = new ArrayList<>();
        final List<String> actions = new ArrayList<>(), params = new ArrayList<>(), toast = new ArrayList<>();
        String error = "", nav = "";
        boolean synchronous;
        long memoryLimit=Long.MAX_VALUE;
        Runnable beforeRetention=()->{};
        Map<String,Integer> inputLimits=Map.of();
        Map<String,Object> backend = Map.of("available", false, "serverName", "", "actions", List.of());
        @Override public void call(String action, String data, Consumer<Map<String,Object>> callback) {
            actions.add(action); params.add(data);
            if (synchronous) callback.accept(result(true, "OK", Map.of())); else pending.add(callback);
        }
        @Override public Map<String,Object> backend() { return backend; }
        @Override public void fetch(String url,String authorization,int offset,Consumer<Map<String,Object>> callback){callback.accept(Map.of("status","PENDING"));pending.add(callback);}
        @Override public void image(String url,String authorization,Consumer<Map<String,Object>> callback){callback.accept(Map.of("status","PENDING"));pending.add(callback);}
        @Override public void imageBytes(String bytes,Consumer<Map<String,Object>> callback){callback.accept(Map.of("status","INVALID"));}
        @Override public boolean allowRetained(long bytes) { beforeRetention.run();return bytes<=memoryLimit; }
        @Override public int textInputLimit(String key){return inputLimits.getOrDefault(key,0);}
        @Override public void toast(String text) { toast.add(text); }
        @Override public void navigate(String page) { nav = page; }
        @Override public void back() { nav = "back"; }
        @Override public void closePage() { nav = "close"; }
        @Override public void failed(String text) { error = text; }
    }
    private record Fixture(FrontendRuntime runtime, UiState state, Bridge bridge) { }
    private static Map<String,Object> result(boolean ok, String code, Map<String,Object> data) {
        return Map.of("ok", ok, "code", code, "message", code, "data", data);
    }
    private static Fixture fixture(String script) {
        return fixture(script,()->0L);
    }
    private static Fixture fixture(String script,java.util.function.LongSupplier clock) {
        var program = FrontendProgram.parse(script, 7); var state = UiState.of(program.initialState()); var bridge = new Bridge();
        return new Fixture(new FrontendRuntime(program, state, "app.vue", bridge,clock), state, bridge);
    }
    public static void main(String[] args) {
        Fixture daily = fixture("""
                state = {ready:true,nextAt:'0',count:0};
                function claim() {
                    var prior=state.count;
                    phone.call('claim_daily',{count:prior},function(r){
                        if(r.ok){state.ready=false;state.nextAt=r.data.nextAt;state.count=prior+1;toast('领取成功');}
                        else toast(r.message);
                    });
                }
                """);
        daily.runtime.invoke("claim", List.of());
        check(daily.bridge.error.isEmpty(), "正常函数初始化：" + daily.bridge.error);
        check(daily.bridge.actions.equals(List.of("claim_daily")), "phone.call 走宿主且只派发一次");
        check(daily.bridge.params.equals(List.of("{\"count\":0}")), "仅传作者显式对象");
        check(daily.state.getBool("ready") && daily.runtime.pending()==1, "延迟回包前不执行回调");
        daily.bridge.pending.remove(0).accept(result(true, "OK", Map.of("nextAt", "9007199254740993")));
        check(!daily.state.getBool("ready") && daily.state.getInt("count")==1, "回调修改状态并保存局部闭包");
        check(daily.state.getString("nextAt").equals("9007199254740993"), "大时间戳保留十进制字符串精度");
        check(daily.bridge.toast.equals(List.of("领取成功")) && daily.runtime.pending()==0, "回调 toast 与名额回收");

        var page=SfcCompiler.compilePage("entry.vue", "<script>\nstate={count:1}\nfunction plus(n){state.count+=n;}\n</script>\n<template>\n<button text=\"加\" @click=\"plus(count)\"/>\n</template>");
        var template=new TemplateInstance(page.template(),"entry.vue"); var ui=UiState.of(page.template().initialState()); var tree=template.instantiate(ui);
        var click=tree.click(tree.root(),ui,0);
        check(click.handlers().size()==1 && click.handlers().get(0).arguments().equals(List.of(1)), "模板绑定已声明函数和实参");
        var bound=new FrontendRuntime(page.template().program(),ui,"entry.vue",new Bridge(),()->0L); bound.invoke(click.handlers().get(0).name(),click.handlers().get(0).arguments());
        check(ui.getInt("count")==2, "实际点击进入真实函数");
        reject("function f(){} function f(){}", "重复声明");
        reject("function nav(){}", "不能覆盖宿主导航");
        reject("state={x:1}; function f(){}; toast('初始化不能调用');", "顶层不能执行代码");

        var closed=fixture("state={count:0}; function go(){phone.call('a',{},function(r){state.count++;});}");
        closed.runtime.invoke("go",List.of());var late=closed.bridge.pending.get(0);closed.runtime.close();late.accept(result(true,"OK",Map.of()));
        check(closed.state.getInt("count")==0&&closed.runtime.pending()==0,"关闭释放闭包并丢迟到回包");

        var limit=fixture("state={count:0}; function go(){phone.call('a',{},function(r){if(r.code==='IN_PROGRESS')state.count++;});}");
        for(int i=0;i<5;i++)limit.runtime.invoke("go",List.of());
        check(limit.bridge.actions.size()==4 && limit.runtime.pending()==4 && limit.state.getInt("count")==1,"第五次立即 IN_PROGRESS，不出网也不排队："+limit.bridge.error);

        var unknown=fixture("state={count:0}; function go(){phone.call('a',{},function(r){if(r.code==='UNKNOWN')state.count++;});}");
        unknown.runtime.invoke("go",List.of());unknown.bridge.pending.get(0).accept(result(false,"UNKNOWN",Map.of()));
        check(unknown.state.getInt("count")==1&&unknown.bridge.actions.size()==1,"UNKNOWN 交给作者提示，宿主不重试");

        blocked("state={x:0}; function go(){state.x=1;phone.call('a',{},function(){});throw new Error('bad');}","异常前的状态及尚未发出的请求回滚");
        blocked("state={x:0}; function go(){state.x='bad';phone.call('a',{},function(){});}","状态类型错误不发包");
        blocked("state={x:0}; function go(){phone.call('a',{get x(){phone.call('evil',{},function(){});return 1;}},function(){});}","数据 getter 不执行");
        blocked("state={x:0}; function go(){phone.call('a',{x:1.5},function(){});}","小数参数拒绝");
        blocked("state={x:0}; function go(){phone.call('a',{x:'x'.repeat(5000)},function(){});}","参数超过 4 KiB 拒绝");
        blocked("state={x:0}; function go(){eval('phone.call(\"evil\",{},function(){})');}","eval 删除");
        blocked("state={x:0}; function go(){(function(){}).constructor('return 1')();}","函数构造器链删除");
        blocked("state={x:0}; function go(){java.lang.System.exit(0);}","Java 不可访问");
        blocked("state={x:0}; function go(){try{while(true){}}catch(e){state.x=1;}finally{state.x=2;}}","死循环硬中断且不提交 finally");
        blocked("state={x:0}; function go(){var data={};for(var i=0;i<20;i++)data['x'+i]='x'.repeat(60000);phone.call('a',{},function(){toast(data.x0);});}","闭包驻留超过 1 MiB 不派发请求");
        blocked("state={x:0}; function go(){var data=new Map();data.set('a','x');phone.call('a',{},function(){toast(data.get('a'));});}","不保留不可计量 Map 闭包");

        var frozen=fixture("state={x:0}; function go(){phone.call('a',{},function(r){try{r.data.x=9;}catch(e){}state.x=r.data.x;});}");
        frozen.runtime.invoke("go",List.of());frozen.bridge.pending.get(0).accept(result(true,"OK",Map.of("x",3)));
        check(frozen.state.getInt("x")==3,"回包 data 深度只读："+frozen.bridge.error);
        var backend=fixture("state={x:0}; function go(){if(backend.available)state.x++;}");backend.runtime.invoke("go",List.of());backend.bridge.backend=Map.of("available",true);backend.runtime.invoke("go",List.of());
        check(backend.state.getInt("x")==1,"每次函数读取最新握手快照");
        var immediate=fixture("state={x:0}; function go(){phone.call('a',{},function(r){state.x++;});}");immediate.bridge.synchronous=true;immediate.runtime.invoke("go",List.of());
        check(immediate.state.getInt("x")==1&&immediate.bridge.error.isEmpty(),"同步失败/缓存回包不会嵌套 Context 预算");
        var recurse=fixture("state={x:0}; function go(){phone.call('a',{},function(r){go();});}");recurse.bridge.synchronous=true;recurse.runtime.invoke("go",List.of());
        check(recurse.runtime.failed()&&recurse.bridge.actions.size()<=8,"同步回调循环有总预算且不递归 Java 栈");
        var lines=fixture("state={x:0};\nfunction go(){\n throw new Error('行号');\n}");lines.runtime.invoke("go",List.of());
        check(lines.bridge.error.contains("app.vue:9"),"错误行号映射到原文件："+lines.bridge.error);
        var shared=FrontendProgram.parse("state={x:0}; function go(){state.x++;}",1);
        var one=UiState.of(shared.initialState());var two=UiState.of(shared.initialState());
        var first=new FrontendRuntime(shared,one,"one.vue",new Bridge(),()->0L);var second=new FrontendRuntime(shared,two,"two.vue",new Bridge(),()->0L);
        first.invoke("go",List.of());second.invoke("go",List.of());second.invoke("go",List.of());
        check(one.getInt("x")==1&&two.getInt("x")==2,"同一编译产物的两个运行实例作用域隔离");
        var combined=fixture("state={x:0}; function go(){phone.call('a',{},function(){});}");combined.bridge.memoryLimit=0;combined.runtime.invoke("go",List.of());
        check(combined.runtime.failed()&&combined.bridge.actions.isEmpty(),"App 跨页面总量检查在发包前执行");
        var retained=fixture("state={x:0}; function go(){var data=[];for(var i=0;i<20;i++)data.push('x'.repeat(60000));phone.call('a',{},function(){toast(data[0]);});}");retained.runtime.invoke("go",List.of());
        check(retained.runtime.failed()&&retained.bridge.actions.isEmpty()&&retained.bridge.error.contains("RETAINED"),"读取活动作用域，准确计量闭包大字符串："+retained.bridge.error);
        var reconnect=fixture("state={x:0}; function go(){phone.call('a',{},function(r){state.x++;});}");reconnect.runtime.invoke("go",List.of());var old=reconnect.bridge.pending.get(0);reconnect.runtime.discardCallbacks();old.accept(result(true,"OK",Map.of()));reconnect.runtime.invoke("go",List.of());
        check(reconnect.state.getInt("x")==0&&reconnect.runtime.pending()==1,"换服丢弃旧闭包且新连接可继续调用");
        var network=fixture("state={src:''}; function go(){phone.image('https://example.com/a.png',{},function(r){if(r.status==='READY')state.src=r.src;});}");network.runtime.invoke("go",List.of());
        check(network.runtime.pending()==1&&network.state.getString("src").isEmpty(),"PENDING 保留前端闭包等待终态");network.bridge.pending.get(0).accept(Map.of("status","READY","src","private/test.png"));
        check(network.runtime.pending()==0&&network.state.getString("src").equals("private/test.png"),"终态图片回调可写入模板状态");
        var fetch=fixture("state={x:0}; function go(){phone.fetch('https://example.com',{},function(r){if(r.status==='READY')state.x++;});}");fetch.runtime.invoke("go",List.of());fetch.bridge.pending.get(0).accept(Map.of("status","READY"));check(fetch.state.getInt("x")==1&&fetch.runtime.pending()==0,"文字网络请求终态同样保留回调");
        var badImage=fixture("state={x:0}; function go(){phone.imageBytes('bad',function(r){if(r.status==='INVALID')state.x++;});}");badImage.runtime.invoke("go",List.of());check(badImage.state.getInt("x")==1&&!badImage.runtime.failed(),"坏图返回占位状态，不停止页面");
        var input=fixture("state={draft:''}; function go(){phone.call('a',{},function(r){state.draft=r.data.text;});}");input.bridge.inputLimits=Map.of("draft",4096);input.runtime.invoke("go",List.of());input.bridge.pending.get(0).accept(result(true,"OK",Map.of("text","😀汉".repeat(1000))));check(!input.runtime.failed()&&input.state.getString("draft").codePointCount(0,input.state.getString("draft").length())==2000,"前端回调能向声明的长输入框写入完整文本");
        var bounded=fixture("state={draft:''}; function go(){phone.call('a',{},function(r){state.draft=r.data.text;});}");bounded.bridge.inputLimits=Map.of("draft",128);bounded.runtime.invoke("go",List.of());bounded.bridge.pending.get(0).accept(result(true,"OK",Map.of("text","x".repeat(129))));check(bounded.runtime.failed()&&bounded.state.getString("draft").isEmpty(),"回调不能超过输入框自己的上限");
        check(UiState.of(Map.of("x","")).writeProblem("x","x".repeat(65))!=null,"旧式 state 写入仍保留原契约");
        check(ScriptBudget.CLIENT_WALL_NANOS==50_000_000L&&ScriptBudget.CLIENT_INSTRUCTIONS==2_000_000L,"生产预算仍为 50 ms / 两百万指令");
        long[] clock={0};
        var expired=fixture("state={x:0}; function go(){state.x=1;phone.call('a',{},function(){});}",()->clock[0]);
        expired.bridge.beforeRetention=()->clock[0]=ScriptBudget.CLIENT_WALL_NANOS+1;
        expired.runtime.invoke("go",List.of());
        check(expired.runtime.failed()&&expired.bridge.error.contains("WALL_CLOCK")&&expired.state.getInt("x")==0
                &&expired.bridge.actions.isEmpty()&&expired.runtime.pending()==0,"墙钟到期回滚状态、释放闭包、不派发暂存请求");
        check(ScriptBudget.wallLeftNanos()==Long.MAX_VALUE,"超时后线程预算清理");
        var instructions=fixture("state={x:0}; function go(){while(true){}}");instructions.runtime.invoke("go",List.of());
        check(instructions.runtime.failed()&&instructions.bridge.error.contains("INSTRUCTIONS"),"语义测试的固定钟不放宽指令硬闸");
        clock[0]=0;var wallBudget=ScriptBudget.client(()->clock[0]);boolean observerAborted=false;
        try(var cx=wallBudget.enterContext()){
            var sandbox=ScriptSandbox.harden(cx);wallBudget.begin();clock[0]=ScriptBudget.CLIENT_WALL_NANOS+1;
            try{cx.evaluateString(sandbox,"var x=0;while(x<100000){x++;}","wall.vue",1,null);}
            catch(ScriptAbort stop){observerAborted=stop.reason()==ScriptAbort.Reason.WALL_CLOCK;}
        }finally{wallBudget.end();}
        check(observerAborted,"Rhino 指令观察器使用同一单调钟检查超时");
        System.out.println("FrontendRuntimeTest: "+checks+" passed");
    }
    private static void reject(String src,String why) {try{FrontendProgram.parse(src,1);throw new AssertionError(why);}catch(SfcError expected){checks++;}}
    private static void blocked(String src,String why) {var f=fixture(src);f.runtime.invoke("go",List.of());check(f.runtime.failed()&&f.bridge.actions.isEmpty()&&f.state.getInt("x")==0,why+": "+f.bridge.error);}
}
