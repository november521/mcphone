package com.november.mcphone.core.script.net;

import java.util.UUID;

/** 在释放在飞槽位之前核对所有关联轴，迟到或混入的回包不消费当前请求。 */
public final class StoreFileReplies {
    private StoreFileReplies(){}
    public static boolean matches(UUID server,StoreFileRequest request,StoreFileReply reply){return server.equals(reply.serverId())&&request.epoch()==reply.epoch()&&request.token().equals(reply.token())&&request.kind()==reply.kind()&&request.point()==reply.point();}
    public static boolean downloadPart(StoreFileReply reply,int total){int expected=Math.min(StoreFileRequest.CHUNK,total-reply.point());return expected>0&&reply.size()==total&&reply.bytes().length==expected&&reply.next()==reply.point()+expected;}
}
