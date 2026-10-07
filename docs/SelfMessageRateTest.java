package com.november.mcphone.core.script.server;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public final class SelfMessageRateTest {
    public static void main(String[] args){
        AtomicLong now=new AtomicLong();var rate=new SelfMessageRate(now::get);UUID player=UUID.randomUUID();
        if(!rate.allow(player,3)||rate.allow(player,2)||!rate.allow(player,1)||rate.allow(player,1))throw new AssertionError("整批拒绝不消耗余量，跨 App 共享四条上限");
        now.set(9999);if(rate.allow(player,1))throw new AssertionError("窗口未到提前放行");now.set(10000);if(!rate.allow(player,4))throw new AssertionError("窗口届满未恢复");
        if(rate.allow(player,5)||rate.allow(player,-1)||!rate.allow(UUID.randomUUID(),4))throw new AssertionError("批次边界或玩家隔离");
        System.out.println("SelfMessageRateTest: 9 passed");
    }
}
