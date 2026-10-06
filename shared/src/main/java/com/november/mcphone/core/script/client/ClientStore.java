package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.core.script.pkg.*;
import com.november.mcphone.core.script.server.StoreTransfer;
import net.minecraft.client.Minecraft;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 原生商店传输。请求单片串行、每片间隔 250ms；所有回调在主线程，断线与超时只结束 UI，不重试审批。 */
public final class ClientStore {
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"mcphone-store-timer");t.setDaemon(true);return t;});
    private static int active;
    private static final Set<Operation> operations=new HashSet<>();
    private record PendingFile(Operation op,StoreFileRequest request,Consumer<StoreFileReply> callback){}
    private static final Map<String,PendingFile> pendingFiles=new HashMap<>();
    private ClientStore() { }
    public static void rpc(String action,JsonObject args,Consumer<JsonObject> success,Consumer<String> error) {
        UUID server=ClientHandshake.serverId();long epoch=ClientHandshake.connectionEpoch();
        if(epoch==0) {error.accept("服务器商店不可用");return;}
        ScriptCall.call(ScriptProtocol.HOST_APP_ID,action,args.toString().getBytes(StandardCharsets.UTF_8),null,r->{
            if(epoch!=ClientHandshake.connectionEpoch()||!Objects.equals(server,ClientHandshake.serverId())) {error.accept("连接已改变");return;}
            if(r.code()!=ScriptErrorCode.OK) {error.accept(net.minecraft.network.chat.Component.translatable(r.code().defaultMessageKey()).getString());return;}
            try {success.accept(JsonParser.parseString(new String(r.data(),StandardCharsets.UTF_8)).getAsJsonObject());}
            catch(RuntimeException bad) {error.accept("商店响应格式无效");}
        });
    }
    public static JsonObject args(String key,String value) {JsonObject args=new JsonObject();args.addProperty(key,value);return args;}
    public static JsonObject offset(int value) {JsonObject args=new JsonObject();args.addProperty("offset",value);return args;}
    private static final class Operation {
        final long epoch=ClientHandshake.connectionEpoch();final UUID server=ClientHandshake.serverId();
        final boolean fileChannel=ClientAdministration.fileChannel();
        final String downloadToken=UUID.randomUUID().toString().replace("-","");
        final Consumer<String> error;boolean done;
        ScheduledFuture<?> timeout;
        Operation(Consumer<String> error) {this.error=error;}
        boolean valid() {if(done)return false;if(epoch!=ClientHandshake.connectionEpoch()||!Objects.equals(server,ClientHandshake.serverId())){fail("连接已改变，传输取消");return false;}return true;}
        void release(){done=true;active--;operations.remove(this);pendingFiles.values().removeIf(p->p.op==this);if(timeout!=null)timeout.cancel(false);}
        void fail(String reason) {if(done)return;release();error.accept(reason);}
        void finish(Runnable run) {if(!valid())return;release();run.run();}
        void later(Runnable run) {TIMER.schedule(()->Minecraft.getInstance().execute(()->{if(valid())run.run();}),250,TimeUnit.MILLISECONDS);}
    }
    private static Operation start(Consumer<String> error) {
        if(!ClientHandshake.complete()){error.accept("服务器商店不可用");return null;}
        if(active>=2) {error.accept("已有两个商店传输正在进行");return null;}
        StoreFileChannel.receiveWith(ClientStore::onFile);
        Operation op=new Operation(error);operations.add(op);active++;op.timeout=TIMER.schedule(()->Minecraft.getInstance().execute(()->op.fail("传输超时，请查看提交记录后再决定是否重试")),60,TimeUnit.SECONDS);return op;
    }
    public static void clear(){for(Operation op:List.copyOf(operations))op.fail("连接已结束，传输取消");pendingFiles.clear();}
    private static void file(Operation op,StoreFileRequest request,Consumer<StoreFileReply> callback){
        if(!op.valid())return;if(pendingFiles.containsKey(request.token())){op.fail("文件请求仍在等待确认");return;}
        pendingFiles.put(request.token(),new PendingFile(op,request,callback));
        try{StoreFileChannel.send(request);}catch(RuntimeException unavailable){pendingFiles.remove(request.token());op.fail("文件通道不可用");}
    }
    private static void onFile(StoreFileReply reply){
        PendingFile p=pendingFiles.get(reply.token());if(p==null||!StoreFileReplies.matches(p.op.server,p.request,reply))return;
        pendingFiles.remove(reply.token());if(!p.op.valid())return;
        if(reply.protocol()!=StoreFileRequest.PROTOCOL){p.op.fail("文件协议不兼容");return;}
        if(reply.code()!=ScriptErrorCode.OK){p.op.fail(net.minecraft.network.chat.Component.translatable(reply.code().defaultMessageKey()).getString());return;}
        try{p.callback.accept(reply);}catch(RuntimeException invalid){p.op.fail("文件回包无效");}
    }
    public static void upload(Path path,Consumer<JsonObject> success,Consumer<String> error) {
        Operation op=start(error);if(op==null)return;
        try {
            int compressed=op.fileChannel?ClientAdministration.packageCompressed():PackageReader.MAX_COMPRESSED;
            int expanded=op.fileChannel?ClientAdministration.packageExpanded():PackageReader.MAX_TOTAL_INFLATED;
            if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>compressed)throw new IllegalArgumentException("包路径或大小超出服务器配额");
            byte[] raw=Files.readAllBytes(path);AppPackage p=PackageReader.read(raw,compressed,expanded);
            if(!p.signed()||!SigManifest.parse(p.signature()).verify(p.digest()))throw new IllegalArgumentException("提交上架需要有效作者签名");
            JsonObject begin=args("sha",StoreTransfer.sha(raw));begin.addProperty("size",raw.length);
            rpc(op.fileChannel?"store.file.begin":"store.begin",begin,result->{if(op.valid())uploadChunk(op,result.get("id").getAsString(),raw,0,success);},op::fail);
        } catch(IOException|RuntimeException bad) {op.fail("无法提交："+bad.getMessage());}
    }
    private static void uploadChunk(Operation op,String id,byte[] raw,int index,Consumer<JsonObject> success) {
        int chunk=op.fileChannel?StoreFileRequest.CHUNK:StoreTransfer.CHUNK,offset=index*chunk;
        if(offset>=raw.length) {op.later(()->rpc("store.commit",args("id",id),r->op.finish(()->success.accept(r)),op::fail));return;}
        if(op.fileChannel){
            byte[] bytes=Arrays.copyOfRange(raw,offset,Math.min(raw.length,offset+chunk));
            op.later(()->file(op,new StoreFileRequest(StoreFileRequest.PROTOCOL,op.epoch,id,StoreFileRequest.UPLOAD,"",index,bytes),r->{
                if(r.next()!=index+1||r.size()!=0||r.bytes().length!=0){op.fail("上传片确认无效");return;}uploadChunk(op,id,raw,index+1,success);
            }));return;
        }
        JsonObject part=args("id",id);part.addProperty("index",index);part.addProperty("bytes",Base64.getEncoder().encodeToString(Arrays.copyOfRange(raw,offset,Math.min(raw.length,offset+StoreTransfer.CHUNK))));
        op.later(()->rpc("store.chunk",part,r->{if(!op.valid())return;if(r.get("next").getAsInt()!=index+1){op.fail("分片确认不一致");return;}uploadChunk(op,id,raw,index+1,success);},op::fail));
    }
    public static void download(String digest,Consumer<byte[]> success,Consumer<String> error) {
        Operation op=start(error);if(op==null)return;
        rpc("store.meta",args("digest",digest),meta->{
            if(!op.valid())return;int size=meta.get("size").getAsInt();String sha=meta.get("sha").getAsString();
            if(size<1||size>FrontendEnvelope.MAX_BYTES||!sha.matches("[0-9a-f]{64}")){op.fail("商店文件大小或摘要无效");return;}
            if(!op.fileChannel&&size>StoreTransfer.CHUNK*200){op.fail("此文件需要服务器支持新的商店文件通道");return;}
            read(op,digest,size,sha,new ByteArrayOutputStream(),success);
        },op::fail);
    }
    private static void read(Operation op,String digest,int size,String sha,ByteArrayOutputStream bytes,Consumer<byte[]> success) {
        int offset=bytes.size();JsonObject args=args("digest",digest);args.addProperty("offset",offset);
        if(op.fileChannel){
            op.later(()->file(op,new StoreFileRequest(StoreFileRequest.PROTOCOL,op.epoch,op.downloadToken,StoreFileRequest.DOWNLOAD,digest,offset,new byte[0]),r->{
                if(!StoreFileReplies.downloadPart(r,size)){op.fail("下载分片无效");return;}
                bytes.writeBytes(r.bytes());if(bytes.size()==size)finishDownload(op,sha,bytes,success);else read(op,digest,size,sha,bytes,success);
            }));return;
        }
        op.later(()->rpc("store.read",args,result->{
            if(!op.valid())return;byte[] part=Base64.getDecoder().decode(result.get("bytes").getAsString());int expected=Math.min(StoreTransfer.CHUNK,size-offset);
            if(part.length!=expected||result.get("next").getAsInt()!=offset+expected||result.get("size").getAsInt()!=size||result.get("eof").getAsBoolean()!=(offset+expected==size)){op.fail("下载分片无效");return;}
            bytes.writeBytes(part);if(bytes.size()==size)finishDownload(op,sha,bytes,success);
            else read(op,digest,size,sha,bytes,success);
        },op::fail));
    }
    private static void finishDownload(Operation op,String sha,ByteArrayOutputStream bytes,Consumer<byte[]> success){byte[] raw=bytes.toByteArray();if(!StoreTransfer.sha(raw).equals(sha)){op.fail("下载摘要不匹配");return;}op.finish(()->success.accept(raw));}
    public static void later(Runnable run) {TIMER.schedule(()->Minecraft.getInstance().execute(run),250,TimeUnit.MILLISECONDS);}
}
