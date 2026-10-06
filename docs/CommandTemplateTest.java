package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.mojang.brigadier.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.*;

/** 命令槽注入、当前 requires 重解析和逐模板权限均不能靠「返回正数」代替检查。 */
public final class CommandTemplateTest {
    private static int checks;
    private static void check(boolean v,String why) { checks++; if(!v) throw new AssertionError(why); }
    private static JsonObject base() { return JsonParser.parseString("""
        {"id":"gift","reviewed":true,"commands":["give ${self} ${item} ${count}"],
         "arguments":{"self":{"type":"self"},"item":{"type":"resource","tag":"mcphone:giftable"},"count":{"type":"int","min":1,"max":64}},
         "allowed_paths":["/give/targets/item/count"]}
        """).getAsJsonObject(); }
    private static boolean rejected(JsonObject o) { try { CommandTemplate.parse(o,Set.of("lp","luckperms")); return false; } catch(RuntimeException e) { return true; } }
    static final class Source { int level=2; }
    public static void main(String[] args) throws Exception {
        var tpl=CommandTemplate.parse(base(),Set.of("lp")); UUID player=UUID.randomUUID();
        check(tpl.render(Map.of("item","minecraft:diamond","count",1),player,(item,tag)->true).equals(List.of("give @s minecraft:diamond 1")),"self 强制宿主玩家");
        for(Map<String,Object> raw:List.of(Map.<String,Object>of("item","minecraft:diamond","count",0),
                Map.<String,Object>of("item","minecraft:diamond","count",65),Map.<String,Object>of("item","minecraft:diamond","count","1"),
                Map.<String,Object>of("item","minecraft:diamond","count",1,"self","@a"))) {
            boolean no=false; try { tpl.render(raw,player,(i,t)->true); } catch(RuntimeException e) { no=true; } check(no,"边界/类型/self 伪造被拒");
        }
        for(String attack:List.of("minecraft:diamond 64","minecraft:diamond\nexecute run op x","@a","minecraft:diamond{}","minecraft:diamond[]","minecraft:diamond\\x","\"diamond\"")) {
            boolean no=false; try { tpl.render(Map.of("item",attack,"count",1),player,(i,t)->true); } catch(RuntimeException e) { no=true; } check(no,"资源槽不接受注入");
        }
        boolean noTag=false; try { tpl.render(Map.of("item","minecraft:diamond","count",1),player,(i,t)->false); } catch(RuntimeException e) { noTag=true; }
        check(noTag,"资源必须命中服主标签");
        for(String command:List.of("execute run give ${self} ${item} ${count}","minecraft:execute run give ${self} ${item} ${count}",
                "op ${self}","function minecraft:evil","mcphone script approve x","othermod:mcphone script approve x","give @a ${item} ${count}")) {
            var o=base(); o.add("commands",JsonParser.parseString(new Gson().toJson(List.of(command)))); check(rejected(o),"审批前拒绝永久禁令或范围扩大");
        }
        var loose=base(); loose.getAsJsonObject("arguments").getAsJsonObject("item").addProperty("type","string"); check(rejected(loose),"没有 string 类型");
        var permission=JsonParser.parseString("""
            {"id":"fly","reviewed":true,"self_format":"uuid","commands":["lp user ${self} permission settemp example.fly true 1h"],
             "arguments":{"self":{"type":"self"}},"allowed_paths":["/lp/args"]}
            """).getAsJsonObject();
        var p=CommandTemplate.parse(permission,Set.of("lp")); check(p.render(Map.of(),player,(i,t)->false).get(0).contains(player.toString()),"权限插件得到 UUID 而非 Brigadier 选择器");
        permission.getAsJsonObject("arguments").add("node",JsonParser.parseString("{\"type\":\"enum\",\"values\":[\"example.fly\"]}"));
        permission.add("commands",JsonParser.parseString("[\"lp user ${self} permission settemp ${node} true 1h\"]")); check(rejected(permission),"权限节点即使是 enum 也不能留槽");
        var policy=new CapabilityPolicy(CapabilityConfig.defaults());
        check(policy.check("command.template:gift",Set.of("command.template:gift"))==CapabilityPolicy.Verdict.NOT_OPEN,"默认关闭，审批不能直接开启");
        policy.commandTemplates(id->id.equals("command.template:gift"));
        check(policy.check("command.template:gift",Set.of())==CapabilityPolicy.Verdict.NOT_APPROVED,"本地开启后仍需 App 的精确模板审批");
        check(policy.check("command.template:gift",Set.of("command.template:other"))==CapabilityPolicy.Verdict.NOT_APPROVED,"不能借别的模板审批");
        check(policy.check("command.template:gift",Set.of("command.template:gift"))==CapabilityPolicy.Verdict.OK,"两道明确许可才放行");
        check(policy.check("command.affect_others",Set.of("command.affect_others"))==CapabilityPolicy.Verdict.NOT_OPEN,"影响他人总开关单独默认关");
        CommandDispatcher<Source> dispatcher=new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<Source>literal("grant").requires(s->s.level>=2).executes(c->1));
        Source source=new Source(); var cached=dispatcher.parse("grant",source); source.level=0;
        check(dispatcher.execute(cached)==1,"CONFIRMED：缓存解析会绕过后来的 requires 变化");
        var fresh=dispatcher.parse("grant",source); check(!fresh.getExceptions().isEmpty() || fresh.getReader().canRead(),"重新解析会拒绝当前无权限 source");
        System.out.println("CommandTemplateTest: "+checks+" passed");
    }
}
