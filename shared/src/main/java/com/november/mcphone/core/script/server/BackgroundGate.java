package com.november.mcphone.core.script.server;

import java.util.*;
import java.util.function.LongSupplier;

/** 后台批次每玩家十秒一次、全服每秒一百次；拒绝时不增长表。 */
public final class BackgroundGate {
    private final LongSupplier clock;private final Map<UUID,Long> players=new LinkedHashMap<>();private long second=Long.MIN_VALUE;private int count;
    public BackgroundGate(LongSupplier clock){this.clock=clock;}
    public boolean allow(UUID player){long now=clock.getAsLong();players.entrySet().removeIf(e->now-e.getValue()>=10_000);if(players.containsKey(player)||players.size()>=512)return false;long next=now/1000;if(next!=second){second=next;count=0;}if(count>=100)return false;count++;players.put(player,now);return true;}
}
