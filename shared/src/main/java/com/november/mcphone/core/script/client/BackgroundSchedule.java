package com.november.mcphone.core.script.client;

import com.november.mcphone.core.script.pkg.BackgroundDeclaration;
import java.util.*;
import java.util.function.DoubleSupplier;

/** 统一调度的纯状态机；抖动、手机关闭降频、退避与停止不依赖游戏线程或真实计时器。 */
public final class BackgroundSchedule {
    public record Due(String app,BackgroundDeclaration.Task task) { }
    private static final class Entry {final String app;final BackgroundDeclaration.Task task;long next;int failures;boolean pending,stopped;Entry(String app,BackgroundDeclaration.Task task,long next){this.app=app;this.task=task;this.next=next;}}
    private final DoubleSupplier random;private final Map<String,Entry> tasks=new LinkedHashMap<>();private long lastBatch=Long.MIN_VALUE;
    private boolean lastPhoneOpen;
    public BackgroundSchedule(DoubleSupplier random){this.random=random;}
    public void register(String app,List<BackgroundDeclaration.Task> declaration,long now){tasks.entrySet().removeIf(e->e.getValue().app.equals(app));for(var task:declaration)tasks.put(app+"/"+task.id(),new Entry(app,task,now+delay(task.intervalMs(),0,!lastPhoneOpen)));}
    public void remove(String app){tasks.entrySet().removeIf(e->e.getValue().app.equals(app));}
    public void reset(){tasks.clear();lastBatch=Long.MIN_VALUE;lastPhoneOpen=false;}
    public List<Due> due(long now,boolean online,boolean phoneOpen,Set<String> installed,Set<String> enabled,Set<String> openApps){
        if(online&&lastPhoneOpen!=phoneOpen){lastPhoneOpen=phoneOpen;for(Entry e:tasks.values())if(!e.pending)e.next=now+delay(e.task.intervalMs(),e.failures,!phoneOpen);}
        if(!online||lastBatch!=Long.MIN_VALUE&&now-lastBatch<10_000)return List.of();List<Due> due=new ArrayList<>();
        for(Entry e:tasks.values()){if(!installed.contains(e.app)||!enabled.contains(e.app)||e.stopped||e.pending)continue;
            if(e.task.requires().equals("app_open")&&!openApps.contains(e.app))continue;
            if(now<e.next)continue;
            e.pending=true;due.add(new Due(e.app,e.task));if(due.size()==8)break;
        }
        if(!due.isEmpty())lastBatch=now;return List.copyOf(due);
    }
    public void complete(Due due,boolean success,long now,boolean phoneOpen){Entry e=tasks.get(due.app()+"/"+due.task().id());if(e==null||!e.pending)return;e.pending=false;e.failures=success?0:e.failures+1;e.stopped=e.failures>=10;e.next=now+delay(e.task.intervalMs(),e.failures,!phoneOpen);}
    public boolean stopped(String app){return tasks.values().stream().anyMatch(e->e.app.equals(app)&&e.stopped);}
    public int failures(Due due){Entry e=tasks.get(due.app()+"/"+due.task().id());return e==null?0:e.failures;}
    private long delay(long interval,int failures,boolean closed){double jitter=.8+Math.max(0,Math.min(1,random.getAsDouble()))*.4;return (long)(interval*(1L<<Math.min(failures,4))*(closed?4:1)*jitter);}
}
