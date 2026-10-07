package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptErrorCode;
import java.nio.file.*;
import java.util.*;

/** 模板更改不重置资格；新活动标签另起资格；管理员名单默认空。 */
public final class GiftDefinitionsTest {
    private static int checks;
    private static void check(boolean value) { checks++; if(!value) throw new AssertionError("礼包 #"+checks); }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("mcphone-gifts-test"); Path file=dir.resolve("definitions.json");
        GiftDefinitions store=new GiftDefinitions(file); UUID editor=UUID.randomUUID(), player=UUID.randomUUID();
        var first=new GiftDefinitions.Definition("weekly","每周礼包",List.of("{\"item\":\"minecraft:diamond\",\"count\":3}"),0,Long.MAX_VALUE,20,0,"");
        store.save(first,editor,"tester"); check(new GiftDefinitions(file).get("weekly").items().equals(first.items()));
        String evidence=Files.readString(file); check(evidence.contains("before") && evidence.contains("after") && evidence.contains(editor.toString()));
        GuardController guards=new GuardController(()->1000L,(predicate,p)->true);
        check(guards.reserve("a",player,"mcphone:gifts","weekly",first.guards()).allowed()); guards.finish("a",ScriptErrorCode.OK);
        var changed=new GiftDefinitions.Definition("weekly","每周礼包",List.of("{\"item\":\"minecraft:netherite_block\",\"count\":3}"),0,Long.MAX_VALUE,20,0,"");
        store.save(changed,editor,"tester"); check(!guards.reserve("b",player,"mcphone:gifts","weekly",changed.guards()).allowed());
        var next=new GiftDefinitions.Definition("weekly","第二周",changed.items(),0,Long.MAX_VALUE,20,0,"");
        check(guards.reserve("c",player,"mcphone:gifts","weekly",next.guards()).allowed());
        var loot=new GiftDefinitions.Definition("random","随机礼包",List.of(),0,Long.MAX_VALUE,20,0,"","minecraft:chests/simple_dungeon");store.save(loot,editor,"tester");check(new GiftDefinitions(file).get("random").loot().equals(loot.loot()));
        boolean conflict=false;try{new GiftDefinitions.Definition("random","a",first.items(),0,2,1,0,"",loot.loot());}catch(IllegalArgumentException bad){conflict=true;}check(conflict);
        String json="{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"}}";
        check(ScriptRuntimeConfig.parse(json).giftEditors().isEmpty()); check(ScriptRuntimeConfig.parse(json).deploymentApprovers().isEmpty());
        var enabled=ScriptRuntimeConfig.parse("{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"admin\":{\"gift_editors\":[\""+editor+"\"]}}");
        check(enabled.giftEditors().contains(editor)); check(!enabled.giftEditors().contains(player));
        int passed=0; for(Runnable invalid:List.<Runnable>of(
                ()->new GiftDefinitions.Definition("../x","a",List.of(),0,2,1,0,""),
                ()->new GiftDefinitions.Definition("x","a|b",List.of(),0,2,1,0,""),
                ()->new GiftDefinitions.Definition("x","a",List.of(),2,1,1,0,""),
                ()->new GiftDefinitions.Definition("x","a",List.of(),0,2,0,0,""))) {
            try { invalid.run(); } catch(IllegalArgumentException expected) { passed++; }
        } check(passed==4);
        System.out.println("礼包定义与守卫断言 "+checks+" 条，全部通过");
    }
}
