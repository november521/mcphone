package com.november.mcphone.core.script.engine;
import java.util.*;
public final class ScriptItemRefsTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    public static void main(String[] args){
        String token="1".repeat(32);List<String> called=new ArrayList<>();ItemLootView view=new ItemLootView(){
            public List<Map<String,Object>> roll(String table){called.add("roll:"+table);return List.of(Map.of("id","minecraft:stick","count",1,"opaque",token));}
            public boolean matches(String h,String tag){called.add(h);return true;}
            public String displayName(String h){called.add(h);return "name";}
            public boolean isDamaged(String h){called.add(h);return false;}
        };var backend=new CtxBuilder.Backends(null,view,null,null,null,null,true);
        var result=ScriptEngineTest.resultOf("var refs=ctx.loot.roll('t:gift');ctx.give(refs)",backend);
        check(result.intents.size()==1&&result.intents.get(0).asRefs().equals(List.of(token)),"roll 返回引用，give 只发放一次且不再重掷");
        var unused=ScriptEngineTest.resultOf("ctx.loot.roll('t:gift')",backend);check(unused.intents.isEmpty(),"仅掷表不会自动给物品");
        var altered=ScriptEngineTest.resultOf("ctx.give([{id:'minecraft:netherite_sword',count:99,opaque:'"+token+"'}])",backend);check(altered.intents.get(0).asRefs().equals(List.of(token)),"传回的 id/count 不进入落地数据，仅取句柄");
        check(ScriptEngineTest.withCtx("var refs=ctx.loot.roll('t:gift');ctx.item.displayName(refs[0])+'/'+ctx.item.matches(refs[0],'#t:giftable')+'/'+ctx.item.isDamaged(refs[0])",backend).equals("name/true/false"),"三个物品谓词接受真实引用");
        int before=called.size();check(ScriptEngineTest.withCtx("var r={};Object.defineProperty(r,'opaque',{get:function(){throw 'getter'}});try{ctx.item.displayName(r)}catch(e){'bad'}",backend).equals("bad")&&called.size()==before,"引用访问器不执行、不触达宿主");
        check(ScriptEngineTest.withCtx("var r=ctx.loot.roll('t:gift');ctx.give(r);try{ctx.give(r)}catch(e){'duplicate'}",backend).equals("duplicate"),"同一动作重复发放同一引用在产出端拒绝");
        check(ScriptEngineTest.withCtx("try{ctx.give([{opaque:'not-handle'}])}catch(e){'bad'}",backend).equals("bad"),"非法句柄形状在产出端拒绝");
        check(ScriptEngineTest.withCtx("ctx.give([]);'empty'",backend).equals("empty"),"空战利品允许不发奖");
        System.out.println("ScriptItemRefsTest: "+checks+" passed");
    }
}
