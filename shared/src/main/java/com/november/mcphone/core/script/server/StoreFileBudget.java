package com.november.mcphone.core.script.server;

import java.util.*;
import java.util.function.LongSupplier;

/** 文件字节单独记账。断线不清配额，提升包上限才相应提升上传预算；下载同样有界。 */
public final class StoreFileBudget {
    private record Window(long start,long bytes){}
    private final LongSupplier clock;
    private Window serverDownload=new Window(0,0);
    private final Map<UUID,Window> upload=new HashMap<>(),download=new HashMap<>();
    public StoreFileBudget(LongSupplier clock){this.clock=clock;}
    public boolean upload(UUID player,long bytes,int packageLimit){return take(upload,player,bytes,Math.max(ScriptRateLimiter.UPLOAD_BYTES_PER_MIN,2L*packageLimit));}
    public boolean download(UUID player,long bytes){long now=clock.getAsLong();if(now-serverDownload.start>=60_000)serverDownload=new Window(now,0);if(bytes<0||bytes>64L*1024*1024-serverDownload.bytes)return false;if(!take(download,player,bytes,2L*com.november.mcphone.core.script.pkg.FrontendEnvelope.MAX_BYTES))return false;serverDownload=new Window(serverDownload.start,serverDownload.bytes+bytes);return true;}
    private boolean take(Map<UUID,Window> windows,UUID player,long bytes,long cap){
        long now=clock.getAsLong();windows.values().removeIf(w->now-w.start>=60_000);
        Window w=windows.get(player);if(w==null){if(windows.size()>=ScriptRateLimiter.MAX_PLAYERS)return false;w=new Window(now,0);}
        if(bytes<0||bytes>cap||w.bytes>cap-bytes)return false;
        windows.put(player,new Window(w.start,w.bytes+bytes));return true;
    }
}
