package com.november.mcphone.core.script.engine;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** UTF-8 单值、替换差额、CAS 占用与重开重算不应漂移。 */
public final class SharedQuotaTest {
    private static int checks;
    private static void check(boolean v,String why) { checks++; if(!v) throw new AssertionError(why); }
    public static void main(String[] args) {
        SharedState state=new SharedState(); state.set("t:app","k","value"); long before=state.bytes("t:app");
        check(!state.compareAndSet("t:app","k","wrong","different"),"CAS 竞争失败");
        check(state.bytes("t:app")==before,"失败 CAS 不计入占用");
        check(state.compareAndSet("t:app","k","value","x"),"替换成功");
        check(state.bytes("t:app")<before,"替换按净差额计量");
        boolean denied=false; try { state.set("t:app","unicode","中".repeat(700)); } catch(HostError e) { denied=true; }
        check(denied && state.get("t:app","unicode")==null,"2 KiB 按 UTF-8 字节判断，拒绝前没有修改");
        state.set("t:app","escaped","\u0000".repeat(100));
        check(state.bytes("t:app")>=600,"转义后真实磁盘字节也计量，不能靠控制字符撑爆快照");
        SharedState restart=new SharedState(); restart.restore(state.snapshot());
        check(restart.totalBytes()==state.totalBytes() && restart.bytes("t:app")==state.bytes("t:app"),"恢复与运行计量一致");
        AtomicInteger writes=new AtomicInteger(); restart.onWrite(app->{ if(writes.incrementAndGet()>1) throw HostError.quota("速率"); });
        restart.set("t:app","one","ok");
        boolean rate=false; try { restart.set("t:app","two","bad"); } catch(HostError e) { rate=true; }
        check(rate && restart.get("t:app","two")==null,"速率门拒绝发生在状态修改之前");
        restart.clear(); check(restart.totalBytes()==0 && restart.snapshot().isEmpty(),"换世界清空计量与值");
        System.out.println("SharedQuotaTest: "+checks+" passed");
    }
}
