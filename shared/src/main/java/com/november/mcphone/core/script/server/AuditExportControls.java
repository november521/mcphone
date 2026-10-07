package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.net.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** 导出在单独 worker 上扫描；每个请求和查询都重查显式管理权限，文件不会发给其他玩家。 */
public final class AuditExportControls implements AutoCloseable {
    private record Job(UUID owner,String state,String file,long rows,long at){}
    private final MinecraftServer server;private final QuotaControls roles;private final Map<String,Job> jobs=new LinkedHashMap<>();private volatile boolean closed;
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"MCphone-audit-export");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public AuditExportControls(MinecraftServer server,QuotaControls roles){this.server=server;this.roles=roles;}
    public ScriptRpcResult handle(ScriptRpc rpc,PlayerSnapshot player,JsonObject args){if(closed)return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);if(!roles.allowed(player.uuid()))return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.NOT_AUTHORIZED);long now=System.currentTimeMillis();jobs.entrySet().removeIf(e->!e.getValue().state().equals("RUNNING")&&now-e.getValue().at()>1800000);JsonObject out=new JsonObject();
        if(rpc.actionId().equals("admin.audit.export")){if(!args.keySet().equals(Set.of("app","minimum","maximum","from","to")))throw new IllegalArgumentException("导出字段无效");AuditExport.Filter filter=new AuditExport.Filter(args.get("app").getAsString(),args.get("minimum").getAsBigDecimal().longValueExact(),args.get("maximum").getAsBigDecimal().longValueExact(),LocalDate.parse(args.get("from").getAsString()),LocalDate.parse(args.get("to").getAsString()));if(jobs.values().stream().anyMatch(j->j.owner().equals(player.uuid())&&j.state().equals("RUNNING"))||jobs.values().stream().filter(j->j.state().equals("RUNNING")).count()>=2||jobs.size()>=16)return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.RATE_LIMITED);String id=UUID.randomUUID().toString().replace("-","");Job job=new Job(player.uuid(),"RUNNING","",0,now);jobs.put(id,job);try{worker.execute(()->{AuditExport.Result result=null;try{result=AuditExport.run(server.getWorldPath(LevelResource.ROOT).resolve("mcphone/audit"),filter);}catch(Exception failure){com.november.mcphone.MCphone.LOGGER.warn("[MCphone] 审计导出未完成：{}",failure.getMessage());}var completed=result;server.execute(()->{if(!closed&&jobs.get(id)==job)jobs.put(id,new Job(player.uuid(),completed==null?"FAILED":"COMPLETE",completed==null?"":completed.file(),completed==null?0:completed.rows(),System.currentTimeMillis()));});});}catch(RejectedExecutionException busy){jobs.remove(id);return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.RATE_LIMITED);}out.addProperty("id",id);out.addProperty("state","RUNNING");}
        else if(rpc.actionId().equals("admin.audit.status")){if(!args.keySet().equals(Set.of("id")))throw new IllegalArgumentException("导出查询无效");Job job=jobs.get(args.get("id").getAsString());if(job==null||!job.owner().equals(player.uuid()))return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.NOT_AUTHORIZED);out.addProperty("state",job.state());out.addProperty("file",job.file());out.addProperty("rows",Long.toString(job.rows()));}else throw new IllegalArgumentException("导出操作无效");return ScriptRpcResult.ok(rpc.requestId(),out.toString().getBytes(StandardCharsets.UTF_8),0);
    }
    @Override public void close(){closed=true;worker.shutdownNow();jobs.clear();}
}
