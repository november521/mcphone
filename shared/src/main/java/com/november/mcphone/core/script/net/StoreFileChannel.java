package com.november.mcphone.core.script.net;

import com.november.mcphone.core.script.server.ScriptHost;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.*;

/** 平台只登记与切线程，业务留在宿主；专服加载此类不会触达客户端类型。 */
public final class StoreFileChannel {
    private StoreFileChannel(){}
    private static volatile Consumer<StoreFileRequest> toServer;
    private static volatile BiConsumer<ServerPlayer,StoreFileReply> toClient;
    private static volatile Consumer<StoreFileReply> receiver;
    public static void install(Consumer<StoreFileRequest> outgoing,BiConsumer<ServerPlayer,StoreFileReply> reply){toServer=outgoing;toClient=reply;}
    public static void receiveWith(Consumer<StoreFileReply> incoming){receiver=incoming;}
    public static void send(StoreFileRequest request){Consumer<StoreFileRequest> s=toServer;if(s==null)throw new IllegalStateException("文件通道不可用");s.accept(request);}
    public static void reply(StoreFileReply reply){Consumer<StoreFileReply> r=receiver;if(r!=null)r.accept(reply);}
    public static void handle(StoreFileRequest request,ServerPlayer player){
        ScriptHost host=ScriptHost.current();BiConsumer<ServerPlayer,StoreFileReply> s=toClient;
        if(s==null||host==null)return;
        // 当前在线实例也必须一致；同 UUID 重连不能用旧 ServerPlayer 发回包。
        if(player.level().getServer()==null||player.level().getServer().getPlayerList().getPlayer(player.getUUID())!=player)return;
        StoreFileReply reply;
        if(request.protocol()!=StoreFileRequest.PROTOCOL)reply=failed(host,request,ScriptErrorCode.VERSION_MISMATCH);
        else if(host.epoch(player.getUUID())!=request.epoch())reply=failed(host,request,ScriptErrorCode.NOT_AUTHORIZED);
        else if(host.store()==null)reply=failed(host,request,ScriptErrorCode.UNAVAILABLE);
        else reply=host.store().file(request,player.getUUID());
        s.accept(player,reply);
    }
    public static StoreFileReply failed(ScriptHost host,StoreFileRequest request,ScriptErrorCode code){return new StoreFileReply(StoreFileRequest.PROTOCOL,host.serverId(),request.epoch(),request.token(),request.kind(),request.point(),0,0,code,new byte[0]);}
}
