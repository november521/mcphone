package com.november.mcphone.feature.notifications.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.script.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 点击单条标记已读；作者名称与 App ID 固定显示在标题上方。 */
public final class NotificationsPage implements IPhonePage {
    private JsonArray rows=new JsonArray();private int offset,next,total,x,y,w,h;private final java.util.ArrayDeque<Integer> history=new java.util.ArrayDeque<>();private long generation;private boolean closed,busy;private String error="";
    @Override public void onOpen(){closed=false;generation++;refresh();}
    @Override public void onClose(){closed=true;generation++;}
    private void refresh(){busy=true;long expected=generation;ClientStore.rpc("notify.list",ClientStore.offset(offset),result->{if(closed||generation!=expected)return;busy=false;rows=result.getAsJsonArray("items");total=result.get("total").getAsInt();next=result.has("next")?result.get("next").getAsInt():offset+rows.size();ClientNotifications.counts(result);},message->{if(closed||generation!=expected)return;busy=false;error=message;});}
    @Override public void render(PhoneCanvas c){x=c.x();y=c.y();w=c.width();h=c.height();c.graphics().drawString(c.font(),Component.translatable("mcphone.notify.title"),x+3,y+3,c.style().bodyColor(),false);
        int rowH=Math.max(32,(h-45)/2);for(int i=0;i<rows.size();i++){JsonObject row=rows.get(i).getAsJsonObject();int top=y+17+i*rowH;String id=row.get("app").getAsString();var app=PhoneScreenRegistry.getApp(ResourceLocation.tryParse(id));String name=app==null?id:app.getDisplayName().getString();c.graphics().fill(x+2,top,x+w-2,top+rowH-2,c.style().buttonColor());
            c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(name+" · "+id,w-8),x+4,top+2,c.style().subtleColor(),false);
            String title=ClientNotifications.text(id,row.get("titleKey").getAsString(),row.getAsJsonArray("titleArgs"));c.graphics().drawString(c.font(),c.font().plainSubstrByWidth((row.get("read").getAsBoolean()?"":"● ")+title,w-8),x+4,top+13,c.style().bodyColor(),false);
            if(row.has("bodyKey")){String body=ClientNotifications.text(id,row.get("bodyKey").getAsString(),row.getAsJsonArray("bodyArgs"));int by=top+24;for(var line:c.font().split(Component.literal(body),w-8)){if(by+10>=top+rowH-2)break;c.graphics().drawString(c.font(),line,x+4,by,c.style().subtleColor(),false);by+=10;}}
        }
        c.graphics().drawString(c.font(),"◀  "+Math.min(offset+1,total)+"–"+next+" / "+total+"  ▶",x+3,y+h-24,c.style().bodyColor(),false);String shown=busy?Component.translatable("mcphone.mailbox.loading").getString():error;c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(shown,w-6),x+3,y+h-12,c.style().subtleColor(),false);
    }
    @Override public boolean mouseClicked(double mx,double my,int button){if(mx<x||mx>=x+w||my<y||my>=y+h)return false;if(busy||button!=0)return true;if(my>=y+h-28){if(mx<x+w/2&&!history.isEmpty())offset=history.removeLast();else if(mx>=x+w/2&&next>offset&&next<total){history.addLast(offset);offset=next;}refresh();return true;}int rowH=Math.max(32,(h-45)/2),index=(int)(my-y-17)/rowH;if(my>=y+17&&index>=0&&index<rows.size()){busy=true;long expected=generation;String id=rows.get(index).getAsJsonObject().get("id").getAsString();ClientStore.rpc("notify.read",ClientStore.args("id",id),result->{if(closed||generation!=expected)return;ClientNotifications.read(id);ClientNotifications.counts(result);refresh();},message->{if(closed||generation!=expected)return;busy=false;error=message;});}return true;}
}
