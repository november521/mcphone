package com.november.mcphone.core.script.server;
import java.util.*;
import java.util.function.LongSupplier;

/** 每位管理员只留一个两分钟确认；随机令牌绑定玩家与连接。调用方在确认时再次检查权限。 */
public final class AdminConfirmations<T> {
    private record Pending<T>(UUID player,long epoch,long at,T value){}
    private final Map<String,Pending<T>> pending=new LinkedHashMap<>();
    private final LongSupplier clock;
    public AdminConfirmations(LongSupplier clock){this.clock=clock;}
    public String issue(UUID player,long epoch,T value){prune();if(player==null||epoch==0||value==null)throw new IllegalArgumentException("管理确认身份无效");pending.values().removeIf(p->p.player().equals(player));if(pending.size()>=64)throw new IllegalArgumentException("管理确认队列已满");String token=UUID.randomUUID().toString().replace("-","");pending.put(token,new Pending<>(player,epoch,clock.getAsLong(),value));return token;}
    public T get(UUID player,long epoch,String token){prune();Pending<T> p=pending.get(token);if(p==null||!p.player().equals(player)||p.epoch()!=epoch)throw new IllegalArgumentException("确认已过期或不属于这个连接");return p.value();}
    public T consume(UUID player,long epoch,String token){T value=get(player,epoch,token);pending.remove(token);return value;}
    public void forget(UUID player){pending.values().removeIf(p->p.player().equals(player));}
    private void prune(){long now=clock.getAsLong();pending.values().removeIf(p->now-p.at()>=120000);}
}
