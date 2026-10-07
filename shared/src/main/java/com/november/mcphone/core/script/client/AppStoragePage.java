package com.november.mcphone.core.script.client;

import com.google.gson.JsonObject;
import com.november.mcphone.api.client.ui.*;
import java.util.*;

/** 本人数据由宿主通道操作；显示占用后再确认，不向脚本暴露玩家任意命名空间。 */
public final class AppStoragePage implements IPhonePage {
    private final String app;private final List<String> rows=new ArrayList<>();
    private int x,y,w,h,scroll;private boolean busy,closed,armed;private long generation;private String message="";private JsonObject usage;
    public AppStoragePage(String app){this.app=app;}
    @Override public void onOpen(){closed=false;generation++;request(false);}
    @Override public void onClose(){closed=true;generation++;armed=false;usage=null;}
    private void request(boolean clear){busy=true;long expected=generation,epoch=ClientHandshake.connectionEpoch();ClientStore.rpc(clear?"storage.clear":"storage.show",ClientStore.args("app",app),value->{if(closed||expected!=generation||epoch!=ClientHandshake.connectionEpoch())return;busy=false;armed=false;usage=value;message=clear?"已清理本服此 App 的普通 KV":"";},error->{if(!closed&&expected==generation&&epoch==ClientHandshake.connectionEpoch()){busy=false;armed=false;message=error;}});}
    @Override public void render(PhoneCanvas c){x=c.x();y=c.y();w=c.width();h=c.height();rows.clear();rows.add("本人应用存储");rows.add(app);rows.add("打开统一收件箱");rows.add("查看本人托管");if(usage!=null){rows.add("普通键："+usage.get("keys").getAsString());rows.add("KV 字节："+usage.get("kvBytes").getAsString());rows.add("密文字节："+usage.get("sealedBytes").getAsString());}rows.add("清理会删除此 App 的普通 KV");rows.add("加密保险箱单独保留");rows.add("领取资格、余额和托管继续保留");rows.add(armed?"再次点击确认清理":"清理本人普通 KV");rows.add("刷新占用");int visible=Math.max(1,(h-24)/12);scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-visible)));for(int i=0;i<visible&&scroll+i<rows.size();i++)c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(rows.get(scroll+i),w-8),x+4,y+3+i*12,c.style().bodyColor(),false);c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(busy?"处理中…":message,w-8),x+4,y+h-16,c.style().bodyColor(),false);}
    @Override public boolean mouseClicked(double mx,double my,int button){if(button!=0||busy||closed||mx<x||mx>=x+w||my<y+3||my>=y+h-24)return true;int index=scroll+(int)(my-y-3)/12;if(index==2){open(new com.november.mcphone.feature.mailbox.client.MailboxApp().openPage());}else if(index==3){open(new com.november.mcphone.feature.escrow.client.ItemEscrowApp().openPage());}else if(index==rows.size()-2&&usage!=null){if(armed)request(true);else{armed=true;message="请核对删除范围；此操作不能撤销";}}else if(index==rows.size()-1)request(false);return true;}
    private void open(IPhonePage page){armed=false;if(net.minecraft.client.Minecraft.getInstance().screen instanceof com.november.mcphone.core.client.PhoneScreen phone)phone.openAddonPage(page);}
    @Override public boolean mouseScrolled(double mx,double my,double amount){armed=false;scroll+=amount>0?-2:2;return true;}
    @Override public boolean onBack(){if(armed){armed=false;message="已取消清理";return true;}return false;}
}
