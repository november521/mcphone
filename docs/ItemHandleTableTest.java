package com.november.mcphone.core.script.server;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public final class ItemHandleTableTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    private static void denied(Runnable action,String why){try{action.run();throw new AssertionError(why);}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    public static void main(String[] args){
        AtomicLong now=new AtomicLong();var table=new ItemHandleTable<List<String>>(ArrayList::new,now::get);UUID player=UUID.randomUUID();var owner=new ItemHandleTable.Owner(player,1,"a:app","rev","give",1);
        List<String> original=new ArrayList<>(List.of("original NBT"));String token=table.issue(owner,original,"loot.roll",1024);original.clear();
        check(com.november.mcphone.api.sdk.item.Handles.isHandle(token),"128 位随机句柄");check(table.get(owner,token).value().equals(List.of("original NBT")),"发出时保存独立副本");table.get(owner,token).value().clear();check(!table.get(owner,token).value().isEmpty(),"读引用不会泄露可变实体");
        for(var alien:List.of(new ItemHandleTable.Owner(UUID.randomUUID(),1,"a:app","rev","give",1),new ItemHandleTable.Owner(player,2,"a:app","rev","give",1),new ItemHandleTable.Owner(player,1,"b:app","rev","give",1),new ItemHandleTable.Owner(player,1,"a:app","rev2","give",1),new ItemHandleTable.Owner(player,1,"a:app","rev","other",1),new ItemHandleTable.Owner(player,1,"a:app","rev","give",2)))denied(()->table.get(alien,token),"引用跨身份边界");
        denied(()->table.get(owner,"0".repeat(32)),"伪造句柄");now.set(30000);denied(()->table.get(owner,token),"超时引用");check(table.bytes()==0,"过期释放完整预算");
        now.set(0);for(int i=0;i<16;i++)table.issue(owner,List.of("data"),"loot.roll",65536);denied(()->table.issue(owner,List.of("data"),"loot.roll",1),"单请求 1 MiB 上限");table.forget(owner);check(table.bytes()==0,"完成释放请求实体");
        for(int i=0;i<64;i++)table.issue(owner,List.of("data"),"loot.roll",1);denied(()->table.issue(owner,List.of("data"),"loot.roll",1),"单请求 64 句柄上限");table.forget(player);check(table.bytes()==0,"断线释放玩家实体");
        System.out.println("ItemHandleTableTest: "+checks+" passed");
    }
}
