package com.november.mcphone.core.script.server;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class StoreTransferTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    private static void reject(Runnable run,String why){boolean no=false;try{run.run();}catch(IllegalArgumentException|IllegalStateException expected){no=true;}check(no,why);}
    public static void main(String[]args){
        AtomicLong time=new AtomicLong(1000);StoreTransfer t=new StoreTransfer(time::get);UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        byte[] raw=new byte[5000];new Random(1).nextBytes(raw);final String id=t.begin(a,8,raw.length,StoreTransfer.sha(raw));
        reject(()->t.begin(a,8,1,StoreTransfer.sha(new byte[1])),"每玩家一个活动传输");
        reject(()->t.chunk(b,8,id,0,new byte[2048]),"其他玩家不能借用会话");
        reject(()->t.chunk(a,9,id,0,new byte[2048]),"重连后旧会话拒绝");
        reject(()->t.chunk(a,8,id,1,new byte[2048]),"乱序不写入");
        byte[] first=Arrays.copyOfRange(raw,0,2048);check(t.chunk(a,8,id,0,first)==1,"第一片确认");
        check(t.chunk(a,8,id,0,first)==1,"同一片重传幂等确认");
        reject(()->t.chunk(a,8,id,0,new byte[2048]),"修改已确认片拒绝");
        reject(()->t.commit(a,8,id),"未完整不能提交");
        t.chunk(a,8,id,1,Arrays.copyOfRange(raw,2048,4096));
        reject(()->t.chunk(a,8,id,2,new byte[2048]),"尾片长度要准确");
        t.chunk(a,8,id,2,Arrays.copyOfRange(raw,4096,5000));check(Arrays.equals(t.commit(a,8,id),raw),"恢复原字节");
        reject(()->t.commit(a,8,id),"提交后不再有内存会话");
        String changed=t.begin(a,8,1,StoreTransfer.sha(new byte[]{1}));t.chunk(a,8,changed,0,new byte[]{2});reject(()->t.commit(a,8,changed),"尾部完整但原始摘要不同仍拒");
        String expired=t.begin(a,8,1,StoreTransfer.sha(new byte[]{1}));time.addAndGet(30001);reject(()->t.chunk(a,8,expired,0,new byte[]{1}),"空闲30秒清理");
        t.begin(a,8,1,StoreTransfer.sha(new byte[]{1}));t.cancel(a);check(t.begin(a,8,1,StoreTransfer.sha(new byte[]{1}))!=null,"断线释放会话");
        reject(()->t.begin(b,8,262145,"0".repeat(64)),"压缩总长有硬限");
        reject(()->t.begin(b,0,1,"0".repeat(64)),"未握手不能创建会话");
        System.out.println("StoreTransferTest: "+checks+" passed");
    }
}
