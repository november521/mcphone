package com.november.mcphone.core.script.client;

import com.november.mcphone.api.client.ui.*;
import net.minecraft.network.chat.Component;

/** 强制版本同步期间不创建旧脚本运行时；失败可返回，重试仅由玩家点击。 */
public final class ServerUpdatePage implements IPhonePage {
    private final ScriptApp app;
    private Component message=Component.translatable("mcphone.server_update.checking");
    private boolean closed,busy;private long generation;private int x,y,w,h;
    public ServerUpdatePage(ScriptApp app){this.app=app;}
    @Override public void onOpen(){closed=false;generation++;update();}
    @Override public void onClose(){closed=true;generation++;}
    private void update(){busy=true;long expected=generation;ClientServerUpdates.check(app,true,result->{if(!closed&&expected==generation){busy=false;message=result;}});}
    private ScriptAppAdapter updated(){var current=com.november.mcphone.core.client.PhoneScreenRegistry.getApp(app.id());return current instanceof ScriptAppAdapter script&&script.script()!=app?script:null;}
    @Override public void render(PhoneCanvas c){x=c.x();y=c.y();w=c.width();h=c.height();int py=y+5;c.graphics().drawString(c.font(),Component.translatable(ClientServerUpdates.forced(app.id().toString())?"mcphone.server_update.required":"mcphone.server_update.title"),x+3,py,c.style().bodyColor(),false);py+=17;for(var line:c.font().split(message,Math.max(1,w-8))){if(py+c.font().lineHeight>=y+h-30)break;c.graphics().drawString(c.font(),line,x+4,py,c.style().bodyColor(),false);py+=c.font().lineHeight+2;}if(!busy)c.graphics().drawString(c.font(),Component.translatable(updated()==null?"mcphone.server_update.retry":"mcphone.server_update.reopen"),x+4,y+h-20,c.style().bodyColor(),false);}
    @Override public boolean mouseClicked(double mx,double my,int button){if(button==0&&!busy&&mx>=x&&mx<x+w&&my>=y+h-27&&my<y+h){ScriptAppAdapter fresh=updated();if(fresh!=null&&net.minecraft.client.Minecraft.getInstance().screen instanceof com.november.mcphone.core.client.PhoneScreen phone)phone.launchApp(fresh);else update();}return true;}
}
