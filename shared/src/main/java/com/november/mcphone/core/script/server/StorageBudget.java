package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.server.store.StoreQuota;
import java.util.*;
import java.util.function.Supplier;

/** 共享 KV 与离线玩家数据共用一把容量锁，避免两个写入各自通过检查后越过全服上限。 */
public final class StorageBudget {
    private final Supplier<QuotaConfig> config;private final Map<String,Map<String,Long>> scopes=new LinkedHashMap<>();private final Map<String,Long> apps=new LinkedHashMap<>();private long total;
    public StorageBudget(Supplier<QuotaConfig> config){this.config=config;}
    public synchronized void replace(String scope,Map<String,Long> next,Runnable write){replace(scope,next,write,false);}
    public synchronized void restore(String scope,Map<String,Long> next){replace(scope,next,()->{},true);}
    private void replace(String scope,Map<String,Long> next,Runnable write,boolean restoring){Map<String,Long> previous=scopes.getOrDefault(scope,Map.of());long before=previous.values().stream().mapToLong(Long::longValue).sum(),after=next.values().stream().mapToLong(Long::longValue).sum();long all=Math.addExact(total-before,after);long limit=restoring?64L*1024*1024:config.get().get("data.server");
        if(all>limit&&(restoring||all>total))throw new StoreQuota.QuotaExceeded("明文与保险箱合计达到全服配额");
        String player=playerOf(scope);
        if(player!=null){long used=0;for(var row:scopes.entrySet())if(player.equals(playerOf(row.getKey())))used+=row.getValue().values().stream().mapToLong(Long::longValue).sum();long combined=Math.addExact(used-before,after),maximum=restoring?1048576:config.get().get("data.per_player");if(combined>maximum&&(restoring||combined>used))throw new StoreQuota.QuotaExceeded("玩家 KV、保险箱与通知合计达到配额");}
        for(var e:next.entrySet()){if(e.getValue()<0)throw new IllegalArgumentException("负数空间占用");long old=apps.getOrDefault(e.getKey(),0L),value=old-previous.getOrDefault(e.getKey(),0L)+e.getValue();long max=restoring?32L*1024*1024:config.get().get("kv.per_app");if(value>max&&(restoring||value>old))throw new StoreQuota.QuotaExceeded("此 App 全服合计存储配额已满");}
        write.run();previous.forEach((app,bytes)->apps.merge(app,-bytes,Long::sum));next.forEach((app,bytes)->apps.merge(app,bytes,Long::sum));apps.entrySet().removeIf(e->e.getValue()==0);if(after==0)scopes.remove(scope);else scopes.put(scope,Map.copyOf(next));total=all;
    }
    public synchronized long total(){return total;}
    public synchronized long app(String app){return apps.getOrDefault(app,0L);}
    public synchronized Map<String,Long> apps(){return Map.copyOf(apps);}
    private static String playerOf(String scope){if(scope.startsWith("player:"))return scope.substring(7);if(scope.startsWith("notification:"))return scope.substring(13);return null;}
    public synchronized Map<String,Long> players(){Map<String,Long> players=new LinkedHashMap<>();scopes.forEach((scope,rows)->{String player=playerOf(scope);if(player!=null)players.merge(player,rows.values().stream().mapToLong(Long::longValue).sum(),Long::sum);});return Map.copyOf(players);}
}
