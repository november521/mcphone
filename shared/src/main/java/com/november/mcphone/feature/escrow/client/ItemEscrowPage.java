package com.november.mcphone.feature.escrow.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.script.client.ClientStore;
import net.minecraft.network.chat.Component;
import java.util.*;

/** 两次明确点击：先查看固定来源、收件人和数量，再确认。没有自动交付或自动重试。 */
public final class ItemEscrowPage implements IPhonePage {
    private record Row(String text,Runnable click){}
    private final List<Row> rows=new ArrayList<>();private JsonArray items=new JsonArray();private JsonObject selected;
    private int x,y,width,height,offset,total,scroll;private boolean closed,busy;private long generation;private String message="";
    private static String text(String key){return Component.translatable("mcphone.escrow."+key).getString();}
    @Override public void onOpen(){closed=false;generation++;refresh();}
    @Override public void onClose(){closed=true;generation++;}
    private void refresh(){busy=true;long expected=generation;ClientStore.rpc("escrow.list",ClientStore.offset(offset),data->{if(closed||expected!=generation)return;items=data.getAsJsonArray("items");total=data.get("total").getAsInt();busy=false;scroll=0;},error->{if(!closed&&expected==generation){message=error;busy=false;}});}
    private void act(String action,JsonObject item){busy=true;long expected=generation;ClientStore.rpc(action,action.equals("escrow.dismiss")?new JsonObject():ClientStore.args("id",item.get("id").getAsString()),data->{if(closed||expected!=generation)return;selected=null;message=text("done");refresh();},error->{if(!closed&&expected==generation){message=error;selected=null;refresh();}});}
    private void build(){rows.clear();if(selected==null){rows.add(new Row(text("refresh"),this::refresh));for(JsonElement e:items){JsonObject item=e.getAsJsonObject();rows.add(new Row(item.get("name").getAsString()+" ×"+item.get("count").getAsInt(),()->{selected=item;scroll=0;}));rows.add(new Row(item.get("state").getAsString(),null));}}else{rows.add(new Row(text("details"),null));rows.add(new Row(selected.get("name").getAsString()+" ×"+selected.get("count").getAsInt(),null));rows.add(new Row(text("source"),null));rows.add(new Row(selected.get("app").getAsString(),null));rows.add(new Row(text("recipient"),null));String recipient=selected.get("recipient").getAsString();for(int i=0;i<recipient.length();i+=18)rows.add(new Row(recipient.substring(i,Math.min(recipient.length(),i+18)),null));boolean proposal=selected.get("proposal").getAsBoolean();if(proposal){rows.add(new Row(text("confirm"),()->act("escrow.confirm",selected)));rows.add(new Row(text("dismiss"),()->act("escrow.dismiss",selected)));}else if(selected.get("state").getAsString().equals("HELD")){boolean owner=selected.get("owner").getAsBoolean();rows.add(new Row(text(owner?"return":"accept"),()->act(owner?"escrow.cancel":"escrow.accept",selected)));}else rows.add(new Row(text("review"),null));rows.add(new Row(text("back"),()->{selected=null;scroll=0;}));}}
    @Override public void render(PhoneCanvas c){x=c.x();y=c.y();width=c.width();height=c.height();build();int count=Math.max(1,(height-34)/14);scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-count)));for(int i=0;i<count&&scroll+i<rows.size();i++){Row row=rows.get(scroll+i);int py=y+2+i*14;if(row.click()!=null)c.graphics().fill(x+1,py,x+width-1,py+13,c.style().buttonColor());c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(row.text(),width-6),x+3,py+2,c.style().bodyColor(),false);}c.graphics().drawString(c.font(),"◀   "+(offset/5+1)+"   ▶",x+3,y+height-27,c.style().subtleColor(),false);c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(busy?text("loading"):message,width-6),x+3,y+height-12,c.style().bodyColor(),false);}
    @Override public boolean mouseClicked(double mx,double my,int button){if(mx<x||mx>=x+width||my<y||my>=y+height)return false;if(button!=0||busy)return true;if(my>=y+height-30){if(selected==null){if(mx<x+width/2&&offset>=5)offset-=5;else if(mx>=x+width/2&&offset+5<total)offset+=5;refresh();}return true;}int at=scroll+(int)(my-y-2)/14;if(at>=0&&at<rows.size()&&rows.get(at).click()!=null)rows.get(at).click().run();return true;}
    @Override public boolean mouseScrolled(double mx,double my,double amount){scroll+=amount>0?-2:2;return true;}
    @Override public boolean onBack(){if(selected==null)return false;selected=null;scroll=0;return true;}
}
