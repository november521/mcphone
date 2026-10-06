package com.november.mcphone.feature.gifts.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.core.script.client.ScriptCall;
import com.november.mcphone.core.script.net.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;

/** 三行分页；领取仅由点击触发，任何错误都不自动重试。 */
final class GiftPage implements IPhonePage {
    private JsonArray items=new JsonArray();
    private int offset,total,x,y,width,height,rows;
    private long generation;
    private boolean closed,busy,editor;
    private String message="";
    @Override public void onOpen() { closed=false; generation++; load(); }
    @Override public void onClose() { closed=true; generation++; }
    private void load() {
        busy=true; long expected=generation;
        ScriptCall.call(ScriptProtocol.HOST_APP_ID,"gift.list",("{\"offset\":"+offset+"}").getBytes(StandardCharsets.UTF_8),null,result->{
            if(closed || generation!=expected) return; busy=false;
            if(result.code()!=ScriptErrorCode.OK) { message=Component.translatable(result.code().defaultMessageKey()).getString(); return; }
            JsonObject data=JsonParser.parseString(new String(result.data(),StandardCharsets.UTF_8)).getAsJsonObject();
            items=data.getAsJsonArray("items"); total=data.get("total").getAsInt(); editor=data.get("editor").getAsBoolean();
        });
    }
    @Override public void render(PhoneCanvas c) {
        x=c.x(); y=c.y(); width=c.width(); height=c.height(); rows=Math.min(items.size(),Math.max(0,(height-48)/37));
        c.graphics().drawString(c.font(),Component.translatable("mcphone.gift.title"),x+3,y+3,c.style().bodyColor(),false);
        for(int i=0;i<rows;i++) {
            JsonObject row=items.get(i).getAsJsonObject(); int py=y+16+i*37;
            c.graphics().fill(x+2,py,x+width-2,py+35,c.style().buttonColor());
            c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(row.get("label").getAsString(),width-8),x+4,py+2,c.style().bodyColor(),false);
            StringBuilder preview=new StringBuilder(); for(var item:row.getAsJsonArray("preview")) { JsonObject p=item.getAsJsonObject(); preview.append(p.get("id").getAsString().replaceFirst("^[^:]+:","")).append('×').append(p.get("count").getAsInt()).append(' '); }
            c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(preview.toString(),width-8),x+4,py+12,c.style().subtleColor(),false);
            String remaining=row.getAsJsonObject("guard").has("remaining")?row.getAsJsonObject("guard").get("remaining").getAsString():"—";
            c.graphics().drawString(c.font(),Component.translatable("mcphone.gift.collect",remaining),x+4,py+23,c.style().subtleColor(),false);
            if(editor) c.graphics().drawString(c.font(),"⚙",x+width-13,py+23,c.style().bodyColor(),false);
        }
        c.graphics().drawString(c.font(),"◀  "+(offset/3+1)+"  ▶"+(editor?"  +":""),x+3,y+height-26,c.style().bodyColor(),false);
        String shown=busy?Component.translatable("mcphone.mailbox.loading").getString():message;
        c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(shown,Math.max(0,width-6)),x+3,y+height-12,c.style().accentColor(),false);
    }
    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(mx<x || mx>=x+width || my<y || my>=y+height) return false;
        if(busy || button!=0) return true;
        if(my>=y+height-30) {
            if(editor && mx>x+width-18) edit(new JsonObject());
            else { int count=Math.max(1,rows); if(mx<x+width/2 && offset>=count) offset-=count; else if(mx>=x+width/2 && offset+count<total) offset+=count; load(); } return true;
        }
        int index=(int)(my-y-16)/37; if(my<y+16 || index<0 || index>=rows) return true;
        JsonObject row=items.get(index).getAsJsonObject();
        if(editor && mx>x+width-19) { edit(row); return true; }
        busy=true; long expected=generation;
        JsonObject params=new JsonObject(); params.add("id",row.get("id"));
        ScriptCall.call(ScriptProtocol.HOST_APP_ID,"gift.claim",params.toString().getBytes(StandardCharsets.UTF_8),null,result->{
            if(closed || generation!=expected) return;
            message=Component.translatable(result.code()==ScriptErrorCode.OK?"mcphone.gift.mailed":result.code().defaultMessageKey()).getString(); load();
        }); return true;
    }
    private static void edit(JsonObject row) { if(Minecraft.getInstance().screen instanceof PhoneScreen phone) phone.launchApp(new GiftApp(row)); }
}
