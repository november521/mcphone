package com.november.mcphone.core.script.net;

import com.november.mcphone.core.script.server.*;
import com.november.mcphone.core.script.pkg.FrontendEnvelope;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 实际二进制编解码、完整 1 MiB、连接关联与字节配额。游戏网络加载仍由真服验收。 */
public final class StoreFileTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable run,String why){boolean bad=false;try{run.run();}catch(RuntimeException expected){bad=true;}check(bad,why);}
    public static void main(String[] args){
        AtomicLong time=new AtomicLong(1000);UUID player=UUID.randomUUID(),server=UUID.randomUUID();String token="a".repeat(32),digest="b".repeat(64);
        byte[] raw=new byte[1024*1024];new Random(7).nextBytes(raw);
        StoreTransfer transfers=new StoreTransfer(time::get).quotas(()->QuotaConfig.DEFAULT.with("package.compressed",1048576));
        String id=transfers.beginFile(player,71,raw.length,StoreTransfer.sha(raw));
        reject(()->transfers.chunk(player,71,id,0,new byte[2048]),"旧通道不能拼进新会话");
        for(int index=0;index<64;index++){
            byte[] part=Arrays.copyOfRange(raw,index*StoreFileRequest.CHUNK,(index+1)*StoreFileRequest.CHUNK);
            StoreFileRequest request=new StoreFileRequest(1,71,id,1,"",index,part);part[0]^=1;
            FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
            try{StoreFileRequest.encode(request,buffer);check(buffer.readableBytes()<17000,"一片封顶且低于 vanilla C2S 限制");StoreFileRequest decoded=StoreFileRequest.decode(buffer);check(transfers.fileChunk(player,71,id,index,decoded.bytes())==index+1,"二进制上传片按原字节确认");check(!buffer.isReadable(),"没有尾随字节");}finally{buffer.release();}
            time.addAndGet(250);
        }
        check(time.get()<60_000,"完整 1 MiB 不会被旧的分片间隔拖到超时");check(Arrays.equals(transfers.commit(player,71,id),raw),"摘要校验后恢复完整原包");
        StoreFileRequest request=new StoreFileRequest(1,71,token,2,digest,0,new byte[0]);
        StoreFileReply reply=new StoreFileReply(1,server,71,token,2,0,16384,20000,ScriptErrorCode.OK,Arrays.copyOf(raw,16384));
        FriendlyByteBuf out=new FriendlyByteBuf(Unpooled.buffer());try{StoreFileReply.encode(reply,out);StoreFileReply decoded=StoreFileReply.decode(out);check(StoreFileReplies.matches(server,request,decoded),"回包的完整关联轴");check(StoreFileReplies.downloadPart(decoded,20000),"下载普通片");}finally{out.release();}
        check(!StoreFileReplies.matches(UUID.randomUUID(),request,reply),"旧世界回包不能消费槽位");
        check(!StoreFileReplies.matches(server,new StoreFileRequest(1,72,token,2,digest,0,new byte[0]),reply),"旧连接不能消费槽位");
        check(!StoreFileReplies.matches(server,new StoreFileRequest(1,71,"c".repeat(32),2,digest,0,new byte[0]),reply),"其他随机会话不能消费槽位");
        check(!StoreFileReplies.matches(server,new StoreFileRequest(1,71,token,2,digest,16384,new byte[0]),reply),"旧片确认不能消费下一片");
        StoreFileReply tail=new StoreFileReply(1,server,71,token,2,16384,20000,20000,ScriptErrorCode.OK,new byte[3616]);check(StoreFileReplies.downloadPart(tail,20000),"尾片严格长度");check(!StoreFileReplies.downloadPart(tail,20001),"总长不可中途改变");
        reject(()->new StoreFileRequest(1,71,token,1,"",0,new byte[16385]),"编码前限制大分配");
        reject(()->new StoreFileRequest(1,71,token,2,digest,1,new byte[0]),"不允许任意偏移");
        reject(()->new StoreFileRequest(1,0,token,2,digest,0,new byte[0]),"握手前不发字节包");
        reject(()->new StoreFileReply(1,server,71,token,2,0,1,1,ScriptErrorCode.NOT_AUTHORIZED,new byte[1]),"拒绝包没有资源正文");
        FriendlyByteBuf forged=new FriendlyByteBuf(Unpooled.buffer());try{forged.writeVarInt(1);forged.writeVarLong(71);forged.writeUtf(token);forged.writeVarInt(1);forged.writeUtf("");forged.writeVarInt(0);forged.writeVarInt(Integer.MAX_VALUE);reject(()->StoreFileRequest.decode(forged),"解码先检查声明长度再分配");}finally{forged.release();}
        StoreFileBudget budget=new StoreFileBudget(time::get);check(budget.upload(player,1048576,1048576),"提高包限额允许真实大包");check(budget.upload(player,1048576,1048576),"分钟内第二份大包");check(!budget.upload(player,1,1048576),"重连也不能刷新分钟字节配额");time.addAndGet(60000);check(budget.upload(player,1048576,1048576),"下一窗口恢复");
        check(!budget.upload(UUID.randomUUID(),1048576,262144),"默认包设置不静默放大上传流量");
        check(budget.download(player,FrontendEnvelope.MAX_BYTES),"第一份前端封套");check(budget.download(player,FrontendEnvelope.MAX_BYTES),"第二份前端封套");check(!budget.download(player,1),"下载也有字节上限");
        StoreFileBudget global=new StoreFileBudget(time::get);for(int i=0;i<64;i++)check(global.download(UUID.randomUUID(),1048576),"全服下载累积");check(!global.download(UUID.randomUUID(),1),"全服下载上限不能由多 UUID 绕过");
        check(ScriptProtocol.PARAMS_MAX==4096&&ScriptProtocol.DATA_MAX==4096,"普通 RPC 封顶保持不变");
        System.out.println("StoreFileTest: "+checks+" passed");
    }
}
