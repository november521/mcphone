package com.november.mcphone.feature.chat;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** 实际业务操作在收到/发出、图片重复引用、跨会话及重载情况下的个人删除语义。 */
public final class ChatDeletionServiceTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
    public static void main(String[] args) {
        UUID a=new UUID(0,1),b=new UUID(0,2),c=new UUID(0,3),image=new UUID(0,4);
        var chat=new ChatData(); var deletions=new ChatDeletionData();
        var received=ChatMessage.text(b,"收到",1); var sent=ChatMessage.text(a,"发出",2);
        var img1=new ChatMessage(b,3,new ImageBody(image,16,16,1,100));
        var img2=new ChatMessage(a,4,new ImageBody(image,16,16,1,100));
        for(var m:java.util.List.of(received,sent,img1,img2))chat.addMessage(a,b,m);
        var foreign=ChatMessage.text(c,"其他会话",5);chat.addMessage(a,c,foreign);
        check(ChatMessageDeletionService.deleteRecord(a,a,received.id(),chat,deletions)==ChatDeletionResult.FORBIDDEN,"不接受自己作为对端");
        check(ChatMessageDeletionService.deleteRecord(a,b,foreign.id(),chat,deletions)==ChatDeletionResult.NOT_FOUND,"不能删除其他会话 ID");
        check(ChatMessageDeletionService.deleteRecord(c,b,received.id(),chat,deletions)==ChatDeletionResult.NOT_FOUND,"第三方不能拿已知 ID 删除他人会话");
        check(ChatMessageDeletionService.deleteRecord(a,b,received.id(),chat,deletions)==ChatDeletionResult.OK,"本人可删除收到的记录");
        check(ChatMessageDeletionService.deleteRecord(a,b,sent.id(),chat,deletions)==ChatDeletionResult.OK,"本人可删除发出的记录");
        check(ChatMessageDeletionService.deleteRecord(a,b,img1.id(),chat,deletions)==ChatDeletionResult.OK,"图片按消息 ID 删除");
        var visible=deletions.visible(a,b,chat.getMessages(a,b));
        check(visible.size()==1&&visible.get(0).id().equals(img2.id()),"相同图片的另一条消息保留");
        check(chat.referencedImages().contains(image)&&chat.getMessages(b,a).size()==4,"原记录及图片引用保留供对方查看");
        check(deletions.visible(b,a,chat.getMessages(b,a)).size()==4,"对方个人记录完全保留");
        check(ChatMessageDeletionService.deleteRecord(a,b,img1.id(),chat,deletions)==ChatDeletionResult.OK,"同一删除重复请求幂等成功");
        var restored=ChatDeletionData.load(deletions.write(new CompoundTag()));
        check(restored.visible(a,b,chat.getMessages(a,b)).equals(visible),"保存重载后个人删除保持");
        var damaged=new CompoundTag();damaged.putString("hidden","损坏");
        check(ChatMessageDeletionService.deleteRecord(a,b,img2.id(),chat,ChatDeletionData.load(damaged))==ChatDeletionResult.STORAGE_ERROR,"损坏删除存储明确拒绝操作");
        System.out.println("全部通过："+checks+" 条断言");
    }
}
