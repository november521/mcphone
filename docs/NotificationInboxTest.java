package com.november.mcphone.core.script.server;

import com.november.mcphone.api.sdk.notify.Priority;
import java.nio.file.*;
import java.util.*;

public final class NotificationInboxTest {
    private static int checks;private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    private static void rejected(Runnable r,String why){try{r.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[]args)throws Exception {
        Path dir=Files.createTempDirectory("mcphone-notify-test");long[] now={1000};UUID player=UUID.randomUUID();String app="test:app";
        NotificationInbox inbox=new NotificationInbox(dir,()->now[0]);NotificationMessage message=NotificationMessage.parse("news","{\"titleKey\":\"test.news\",\"dedupeKey\":\"news\",\"priority\":\"high\",\"expiresAfterMs\":100000}");
        check(inbox.post(player,app,message,false)==null,"未订阅不投递");inbox.sync(player,List.of(app),List.of());
        for(int i=0;i<5;i++)check(inbox.post(player,app,message,false)!=null,"同 key 更新通知");
        check(inbox.list(player).size()==1&&inbox.unread(player).get(app)==1,"五次去重只留一条未读");
        check(inbox.list(player).get(0).priority()==Priority.NORMAL,"一小时内再次 high 降级");
        long id=inbox.list(player).get(0).id();inbox.read(player,id);check(inbox.unread(player).isEmpty(),"已读同步角标");
        inbox=new NotificationInbox(dir,()->now[0]);check(inbox.list(player).size()==1&&inbox.list(player).get(0).read(),"订阅和已读跨重启保存");
        now[0]+=100001;check(inbox.list(player).isEmpty(),"到期自动不可见");
        inbox.sync(player,List.of(app),List.of(app));check(inbox.post(player,app,message,false)==null,"关通知由服务端取消订阅");inbox.sync(player,List.of(app),List.of());
        NotificationMessage stock=NotificationMessage.parse("news","{\"titleKey\":\"test.news\"}");
        for(int i=0;i<32;i++){now[0]+=60001;check(inbox.post(player,app,stock,false)!=null,"库存未满接收");}
        now[0]+=60001;check(inbox.post(player,app,stock,false)==null&&inbox.list(player).size()==32,"不能淘汰未读项");
        inbox.read(player,inbox.list(player).get(31).id());check(inbox.post(player,app,stock,false)!=null&&inbox.list(player).size()==32,"存量满只淘汰最旧已读项");
        now[0]+=60001;check(inbox.broadcastAllowed(app)&&inbox.broadcastAllowed(app)&&!inbox.broadcastAllowed(app),"广播每 App 两次一分钟");
        inbox.sync(player,List.of(),List.of());check(inbox.list(player).isEmpty()&&!inbox.subscribed(player,app),"卸载清订阅与通知");
        UUID fresh=UUID.randomUUID();inbox.sync(fresh,List.of(app),List.of());for(int i=0;i<10;i++)check(inbox.post(fresh,app,stock,false)!=null,"self 十次一分钟");check(inbox.post(fresh,app,stock,false)==null,"self 十一次被丢弃");
        rejected(()->NotificationMessage.parse("news","{\"titleKey\":\"fake title with spaces\"}"),"标题不是自由文本");
        rejected(()->NotificationMessage.parse("news","{\"titleKey\":\"test.news\",\"player\":\"other\"}"),"不能指定他人身份");
        rejected(()->NotificationMessage.parse("news","{\"titleKey\":\"test.news\",\"titleArgs\":[\"x\\u00a7\"]}"),"参数不能带格式控制字符");
        rejected(()->NotificationMessage.parse("news","{\"titleKey\":\"test.news\",\"expiresAfterMs\":0.5}"),"有效期必须精确整数");
        Files.writeString(dir.resolve(fresh+".json"),"{}");boolean locked=false;try{new NotificationInbox(dir,()->now[0]);}catch(java.io.IOException expected){locked=true;}check(locked,"损坏文件拒绝变成空账");
        System.out.println("NotificationInboxTest: "+checks+" 条通过");
    }
}
