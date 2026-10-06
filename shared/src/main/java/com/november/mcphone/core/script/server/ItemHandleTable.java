package com.november.mcphone.core.script.server;

import com.november.mcphone.api.sdk.item.Handles;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.*;

/** 有界临时物品表；句柄绑定玩家、连接、App、包、动作和请求，输入投影不参与物化。 */
public final class ItemHandleTable<T> {
    public record Owner(UUID player,long epoch,String app,String revision,String action,long request){}
    public record Entry<T>(Owner owner,T value,String source,long bytes,long expires){}
    private final Map<String,Entry<T>> entries=new HashMap<>();
    private final UnaryOperator<T> copy;
    private final LongSupplier clock;
    private final SecureRandom random=new SecureRandom();
    private long total;
    public ItemHandleTable(UnaryOperator<T> copy,LongSupplier clock){this.copy=copy;this.clock=clock;}
    public String issue(Owner owner,T value,String source,long bytes){
        sweep();if(value==null||bytes<1||bytes>65536)throw new IllegalArgumentException("物品附加数据须在 64 KiB 内");
        long count=0,used=0;for(var e:entries.values())if(e.owner().equals(owner)){count++;used+=e.bytes();}
        if(entries.size()>=1024||count>=64||used+bytes>1048576||total+bytes>8388608)throw new IllegalStateException("临时物品句柄预算已满");
        String token;do{token=Handles.format(random.nextLong(),random.nextLong());}while(entries.containsKey(token));
        entries.put(token,new Entry<>(owner,copy.apply(value),source,bytes,clock.getAsLong()+30000));total+=bytes;return token;
    }
    public Entry<T> get(Owner owner,String token){
        sweep();Entry<T> entry=entries.get(token);if(entry==null||!entry.owner().equals(owner))throw new IllegalArgumentException("物品句柄不存在、已失效或不属于此请求");
        return new Entry<>(entry.owner(),copy.apply(entry.value()),entry.source(),entry.bytes(),entry.expires());
    }
    public void forget(Owner owner){remove(e->e.owner().equals(owner));}
    public void forget(UUID player){remove(e->e.owner().player().equals(player));}
    public void sweep(){long now=clock.getAsLong();remove(e->e.expires()<=now);}
    private void remove(Predicate<Entry<T>> predicate){var iterator=entries.values().iterator();while(iterator.hasNext()){var e=iterator.next();if(predicate.test(e)){total-=e.bytes();iterator.remove();}}}
    public void clear(){entries.clear();total=0;}
    public long bytes(){return total;}
}
