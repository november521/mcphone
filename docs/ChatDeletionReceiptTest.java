package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatDeletionResult;
import com.november.mcphone.feature.chat.ChatMessage;
import java.util.List;
import java.util.UUID;

/** 实际缓存的删除确认归属；失败、旧会话、旧票据都不能改动新会话。 */
public final class ChatDeletionReceiptTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        UUID a=new UUID(0,1), b=new UUID(0,2), id=new UUID(0,3), request=new UUID(0,4), next=new UUID(0,5);
        ChatClientCache.clear(); ChatClientCache.openConversation(a);
        ChatClientCache.setMessages(a,List.of(ChatMessage.text(a,"保留直到服务端同步",1)));
        var history=ChatClientCache.getMessages(); ChatClientCache.beginDeletion(a,id,request);
        check(ChatClientCache.getMessages() == history, "发起删除不做乐观删记录");
        ChatClientCache.onDeleteResult(b,id,request,ChatDeletionResult.OK);
        check(ChatClientCache.consumeDeleteResult() == null, "错误对端拒收");
        ChatClientCache.onDeleteResult(a,next,request,ChatDeletionResult.OK);
        check(ChatClientCache.consumeDeleteResult() == null, "错误消息拒收");
        ChatClientCache.onDeleteResult(a,id,next,ChatDeletionResult.OK);
        check(ChatClientCache.consumeDeleteResult() == null, "错误票据拒收");
        ChatClientCache.cancelDeletion(next);
        ChatClientCache.onDeleteResult(a,id,request,ChatDeletionResult.BUSY);
        check(ChatClientCache.consumeDeleteResult().result() == ChatDeletionResult.BUSY, "错误取消不影响当前票据，失败结果正常交出");
        check(ChatClientCache.consumeDeleteResult() == null && ChatClientCache.getMessages() == history, "结果只消费一次，失败不改历史");
        ChatClientCache.beginDeletion(a,id,request); ChatClientCache.cancelDeletion(request);
        ChatClientCache.onDeleteResult(a,id,request,ChatDeletionResult.OK);
        check(ChatClientCache.consumeDeleteResult() == null, "超时取消后迟到确认拒收");
        ChatClientCache.beginDeletion(a,id,request); ChatClientCache.openConversation(b);
        ChatClientCache.onDeleteResult(a,id,request,ChatDeletionResult.OK);
        check(ChatClientCache.consumeDeleteResult() == null, "切会话清理旧票据");
        ChatClientCache.openConversation(a); ChatClientCache.beginDeletion(a,id,next);
        ChatClientCache.onDeleteResult(a,id,request,ChatDeletionResult.OK);
        check(ChatClientCache.consumeDeleteResult() == null, "回到同一对端仍不接收旧票据");
        ChatClientCache.setMessages(a,List.of()); ChatClientCache.onDeleteResult(a,id,next,ChatDeletionResult.OK);
        check(ChatClientCache.getMessages().isEmpty() && ChatClientCache.consumeDeleteResult().request().equals(next), "权威历史先同步，再消费成功确认");
        ChatClientCache.beginDeletion(a,id,request); ChatClientCache.closeConversation();
        ChatClientCache.onDeleteResult(a,id,request,ChatDeletionResult.OK);
        check(ChatClientCache.consumeDeleteResult() == null, "关闭页面清理票据");
        ChatClientCache.openConversation(a); ChatClientCache.beginDeletion(a,id,request); ChatClientCache.clear();
        ChatClientCache.onDeleteResult(a,id,request,ChatDeletionResult.OK);
        check(ChatClientCache.consumeDeleteResult() == null, "退出世界清理票据");
        System.out.println("全部通过："+checks+" 条断言");
    }
}
