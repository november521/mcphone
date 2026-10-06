package com.november.mcphone.core.script.server;

import java.util.*;
import java.util.function.LongSupplier;

/** 本人聊天消息跨 App 合并计数；一次只接受整批，十秒最多四条。 */
public final class SelfMessageRate {
    private final Map<UUID,ArrayDeque<Long>> recent=new HashMap<>();
    private final LongSupplier clock;
    public SelfMessageRate(LongSupplier clock){this.clock=clock;}
    public boolean allow(UUID player,int count){
        if(count<0||count>4)return false;if(count==0)return true;long now=clock.getAsLong();
        recent.values().forEach(q->{while(!q.isEmpty()&&now-q.peekFirst()>=10000)q.removeFirst();});recent.entrySet().removeIf(e->e.getValue().isEmpty());
        if(!recent.containsKey(player)&&recent.size()>=1024)return false;
        var q=recent.computeIfAbsent(player,id->new ArrayDeque<>());if(q.size()+count>4)return false;
        for(int i=0;i<count;i++)q.addLast(now);return true;
    }
}
