package com.november.mcphone.feature.gifts.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.core.script.client.ScriptCall;
import com.november.mcphone.core.script.layout.TextInputBuffer;
import com.november.mcphone.core.script.net.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.nio.charset.StandardCharsets;

/** 只编辑展示与守卫元数据，真实物品由服务器容器复制。时间以毫秒时间戳输入。 */
final class GiftSettingsPage implements IPhonePage {
    private static final String[] KEYS={"id","label","total","startAt","endAt","cooldownMs","predicate","loot"};
    private final TextInputBuffer[] fields=new TextInputBuffer[KEYS.length];
    private int focus=-1,scroll,x,y,width,height;
    private long generation;
    private boolean closed,busy;
    private String message="";
    GiftSettingsPage(JsonObject initial) {
        String[] defaults={"weekly","每周礼包","20","0",String.valueOf(Long.MAX_VALUE),"0","",""};
        for(int i=0;i<KEYS.length;i++) fields[i]=new TextInputBuffer(initial.has(KEYS[i])?initial.get(KEYS[i]).getAsString():defaults[i],i>=6?128:64);
    }
    @Override public void onOpen() { closed=false; generation++; }
    @Override public void onClose() { closed=true; generation++; focus=-1; }
    @Override public boolean capturesKeyboard() { return focus>=0; }
    @Override public void render(PhoneCanvas c) {
        x=c.x(); y=c.y(); width=c.width(); height=c.height();
        c.clipped(x,y,width,Math.max(0,height-35),()->{
            for(int i=0;i<fields.length;i++) {
                int py=y+i*28-scroll;
                c.graphics().drawString(c.font(),Component.translatable("mcphone.gift.field."+KEYS[i]),x+3,py+1,c.style().subtleColor(),false);
                c.graphics().fill(x+2,py+11,x+width-2,py+26,c.style().buttonColor());
                String shown=fields[i].text()+(i==focus && System.currentTimeMillis()/500%2==0?"|":"");
                c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(shown,Math.max(0,width-8)),x+4,py+13,c.style().bodyColor(),false);
            }
        });
        c.graphics().drawString(c.font(),Component.translatable("mcphone.gift.save"),x+3,y+height-30,c.style().bodyColor(),false);
        c.graphics().drawString(c.font(),Component.translatable("mcphone.gift.items"),x+width/2,y+height-30,c.style().bodyColor(),false);
        c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(message,Math.max(0,width-6)),x+3,y+height-12,c.style().accentColor(),false);
    }
    @Override public boolean mouseScrolled(double mx,double my,double amount) { scroll=Math.max(0,Math.min(Math.max(0,KEYS.length*28-height+35),scroll-(int)(amount*28))); return true; }
    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(mx<x || mx>=x+width || my<y || my>=y+height) { focus=-1; return false; }
        if(busy || button!=0) return true;
        if(my<y+height-35) { focus=Math.min(KEYS.length-1,(int)(my-y+scroll)/28); return true; }
        focus=-1; if(my>=y+height-16) return true;
        if(mx<x+width/2) save(false); else save(true); return true;
    }
    private void save(boolean openItems) {
        JsonObject args=new JsonObject();
        try {
            for(int i=0;i<KEYS.length;i++) {
                if(i>=2 && i<=5) args.addProperty(KEYS[i],new java.math.BigDecimal(fields[i].text()).longValueExact());
                else args.addProperty(KEYS[i],fields[i].text());
            }
        } catch(RuntimeException bad) { message=Component.translatable("mcphone.gift.invalid_number").getString(); return; }
        busy=true; long expected=generation;
        ScriptCall.call(ScriptProtocol.HOST_APP_ID,"gift.configure",args.toString().getBytes(StandardCharsets.UTF_8),null,result->{
            if(closed || generation!=expected) return; busy=false;
            message=Component.translatable(result.code().defaultMessageKey()).getString();
            if(openItems && result.code()==ScriptErrorCode.OK) {
                JsonObject edit=new JsonObject(); edit.addProperty("id",fields[0].text()); busy=true;
                ScriptCall.call(ScriptProtocol.HOST_APP_ID,"gift.edit",edit.toString().getBytes(StandardCharsets.UTF_8),null,opened->{ if(closed || generation!=expected) return; busy=false; message=Component.translatable(opened.code().defaultMessageKey()).getString(); });
            }
        });
    }
    @Override public boolean charTyped(char cp,int modifiers) { if(focus<0) return false; fields[focus].type(cp); return true; }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(focus<0) return false; var input=fields[focus]; boolean shift=Screen.hasShiftDown();
        if(key==GLFW.GLFW_KEY_TAB) { focus=(focus+(shift?KEYS.length-1:1))%KEYS.length; scroll=Math.max(0,Math.min(Math.max(0,KEYS.length*28-height+35),focus*28)); }
        else if(key==GLFW.GLFW_KEY_ENTER || key==GLFW.GLFW_KEY_KP_ENTER) focus=-1;
        else if(Screen.hasControlDown() && key==GLFW.GLFW_KEY_A) input.selectAll();
        else if(Screen.hasControlDown() && key==GLFW.GLFW_KEY_V) input.replace(Minecraft.getInstance().keyboardHandler.getClipboard());
        else if(Screen.hasControlDown() && (key==GLFW.GLFW_KEY_C || key==GLFW.GLFW_KEY_X)) { Minecraft.getInstance().keyboardHandler.setClipboard(input.selected()); if(key==GLFW.GLFW_KEY_X) input.replace(""); }
        else if(key==GLFW.GLFW_KEY_BACKSPACE) input.delete(true);
        else if(key==GLFW.GLFW_KEY_DELETE) input.delete(false);
        else if(key==GLFW.GLFW_KEY_LEFT) input.move(-1,shift);
        else if(key==GLFW.GLFW_KEY_RIGHT) input.move(1,shift);
        else if(key==GLFW.GLFW_KEY_HOME) input.moveTo(0,shift);
        else if(key==GLFW.GLFW_KEY_END) input.moveTo(input.text().length(),shift);
        return true;
    }
    @Override public boolean onBack() { if(focus>=0) {focus=-1;return true;} if(Minecraft.getInstance().screen instanceof PhoneScreen phone) { phone.launchApp(new GiftApp()); return true; } return false; }
}
