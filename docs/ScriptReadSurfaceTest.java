package com.november.mcphone.core.script.engine;
import java.util.*;
public final class ScriptReadSurfaceTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    public static void main(String[] args){
        List<String> reads=new ArrayList<>();var backend=new CtxBuilder.Backends(null,null,null).withReads((cap,offset)->{reads.add(cap+":"+offset);return switch(cap){
            case "read.self.position"->Map.of("x",12.5,"y",64,"z",-3.25);
            case "read.self.stats"->Map.of("health",17.5);
            case "read.world.time"->"9223372036854775807";
            case "read.world.weather"->Map.of("raining",true,"thundering",false);
            case "read.players.online_count"->2;
            default->Map.of("items",List.of(Map.of("id","minecraft:diamond","count",2)),"total",41);
        };});
        check(ScriptEngineTest.withCtx("ctx.player.position.x+'/'+ctx.player.stats.health+'/'+ctx.world.time+'/'+ctx.world.weather.raining+'/'+ctx.players.onlineCount",backend).equals("12.5/17.5/9223372036854775807/true/2"),"只读数据保留小数，世界时间按十进制字符串返回");
        check(ScriptEngineTest.withCtx("ctx.player.inventory(16).items[0].count+'/'+ctx.players.list(32).total",backend).equals("2/41"),"固定分页偏移传给宿主");
        check(reads.contains("read.self.inventory:16")&&reads.contains("read.players.list:32"),"两种分页分别绑定正确能力");
        int before=reads.size();check(ScriptEngineTest.withCtx("try{ctx.player.inventory(-1)}catch(e){'bad'}",backend).equals("bad")&&reads.size()==before,"负分页在触达宿主前拒绝");
        check(ScriptEngineTest.withCtx("try{ctx.players.list({valueOf:function(){throw 'coerce'}})}catch(e){'bad'}",backend).equals("bad")&&reads.size()==before,"偏移不执行脚本强制转换回调");
        check(ScriptEngineTest.withCtx("var p=ctx.player.position;try{p.x=99}catch(e){};p.x",backend).equals("12.5"),"只读快照不能被改写");
        check(ScriptEngineTest.withCtx("Object.getPrototypeOf(ctx.player.position)===null&&typeof ctx.player.position.getClass==='undefined'",backend).equals("true"),"快照不暴露 Java 对象或原型链");
        int old=reads.size();check(ScriptEngineTest.withCtxGate("try{ctx.world.weather}catch(e){'closed'}",backend,id->{throw HostError.unavailable("closed");}).equals("closed")&&reads.size()==old,"关闭的 plain 读取不会碰宿主");
        System.out.println("ScriptReadSurfaceTest: "+checks+" passed");
    }
}
