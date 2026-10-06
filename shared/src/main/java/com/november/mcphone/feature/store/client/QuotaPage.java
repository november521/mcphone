package com.november.mcphone.feature.store.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.script.client.*;
import com.november.mcphone.core.script.layout.TextInputBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** 配额页：精确整数编辑与滑杆共用同一个待确认值；写入前不修改服务器。 */
public final class QuotaPage implements IPhonePage {
    private record Row(String text,Runnable click){}
    private final List<Row> rows=new ArrayList<>();private final TextInputBuffer input=new TextInputBuffer("",12);
    private JsonArray items=new JsonArray();private JsonObject selected;private boolean busy,closed,editing;private String message="",kind="limits";
    private int offset,total,scroll,x,y,width,height;private long generation;
    @Override public void onOpen(){closed=false;generation++;show(0);}
    @Override public void onClose(){closed=true;generation++;}
    @Override public boolean capturesKeyboard(){return editing;}
    private void value(String text){input.selectAll();input.replace(text);}
    private static String label(JsonObject value){return net.minecraft.network.chat.Component.translatable("mcphone.quota."+value.get("key").getAsString()).getString();}
    private void error(String error){if(!closed){busy=false;message=error;}}
    private void show(int start){kind="limits";offset=start;busy=true;selected=null;long expected=generation;ClientStore.rpc("quota.show",ClientStore.offset(start),data->{if(closed||generation!=expected)return;busy=false;items=data.getAsJsonArray("items");total=data.get("total").getAsInt();message="全服数据 "+data.get("dataUsed").getAsLong()+" B";scroll=0;},this::error);}
    private void top(String mode){kind=mode;selected=null;busy=true;long expected=generation;ClientStore.rpc("quota.top",ClientStore.args("kind",mode),data->{if(closed||generation!=expected)return;busy=false;items=data.getAsJsonArray("items");scroll=0;},this::error);}
    private void save(){try{long value=new java.math.BigDecimal(input.text()).longValueExact();if(value<selected.get("min").getAsLong()||value>selected.get("max").getAsLong())throw new IllegalArgumentException();JsonObject args=ClientStore.args("key",selected.get("key").getAsString());args.addProperty("value",value);busy=true;long expected=generation;ClientStore.rpc("quota.set",args,data->{if(closed||generation!=expected)return;busy=false;show(offset);},this::error);}catch(RuntimeException invalid){message="请输入范围内的整数";}}
    private void build(){rows.clear();rows.add(new Row("配额  | 玩家占用 | App 占用",null));rows.add(new Row("打开作品与审批队列",()->{var mc=Minecraft.getInstance();if(mc.screen instanceof com.november.mcphone.core.client.PhoneScreen phone)phone.openAddonPage(new ServerManagementPage());}));rows.add(new Row("按应用和版本导出审计",()->{if(Minecraft.getInstance().screen instanceof com.november.mcphone.core.client.PhoneScreen phone)phone.openAddonPage(new AuditExportPage());}));
        if(selected!=null){rows.add(new Row(label(selected),null));rows.add(new Row("现值："+selected.get("value").getAsLong()+" "+selected.get("unit").getAsString(),null));rows.add(new Row("范围："+selected.get("min").getAsLong()+"–"+selected.get("max").getAsLong(),null));rows.add(new Row("调整为："+input.text(),()->editing=true));rows.add(new Row("确认保存并记录审计",this::save));rows.add(new Row("取消修改",()->{selected=null;editing=false;}));}
        else for(JsonElement element:items){JsonObject item=element.getAsJsonObject();if(kind.equals("limits"))rows.add(new Row(label(item)+" = "+item.get("value").getAsLong(),()->{selected=item;value(item.get("value").getAsString());scroll=0;}));else rows.add(new Row(item.get("id").getAsString()+" · "+item.get("used").getAsLong()+" B",()->Minecraft.getInstance().keyboardHandler.setClipboard(item.get("id").getAsString())));}
    }
    @Override public void render(PhoneCanvas c){x=c.x();y=c.y();width=c.width();height=c.height();if(!ClientAdministration.admin()){c.graphics().drawString(c.font(),"管理权限已失效",x+3,y+12,c.style().bodyColor(),false);return;}build();int count=Math.max(1,(height-53)/13);scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-count)));for(int i=0;i<count&&scroll+i<rows.size();i++){Row row=rows.get(scroll+i);int py=y+2+i*13;if(row.click()!=null)c.graphics().fill(x+1,py,x+width-1,py+12,c.style().buttonColor());c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(row.text(),Math.max(0,width-6)),x+3,py+2,c.style().bodyColor(),false);}if(selected!=null){c.graphics().fill(x+4,y+height-45,x+width-4,y+height-37,c.style().buttonColor());try{long min=selected.get("min").getAsLong(),max=selected.get("max").getAsLong();double ratio=max==min?1:(Long.parseLong(input.text())-min)/(double)(max-min);int dx=(int)(Math.max(0,Math.min(1,ratio))*(width-8));c.graphics().fill(x+4,y+height-45,x+4+dx,y+height-37,0xFF67A258);}catch(RuntimeException ignored){}}
        c.graphics().drawString(c.font(),"◀ 上一页       下一页 ▶",x+3,y+height-28,c.style().subtleColor(),false);c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(busy?"处理中…":message,Math.max(0,width-6)),x+3,y+height-12,c.style().bodyColor(),false);}
    @Override public boolean mouseClicked(double mx,double my,int button){if(mx<x||mx>=x+width||my<y||my>=y+height)return false;if(button!=0||busy||!ClientAdministration.admin())return true;if(my<y+15){if(mx<x+width/3)show(0);else top(mx<x+width*2/3?"player":"app");return true;}if(selected!=null&&my>=y+height-45&&my<y+height-35){long min=selected.get("min").getAsLong(),max=selected.get("max").getAsLong();value(Long.toString(min+Math.round(Math.max(0,Math.min(1,(mx-x-4)/(width-8)))*(max-min))));return true;}if(my>=y+height-30){if(kind.equals("limits")&&selected==null){int next=mx<x+width/2?Math.max(0,offset-8):Math.min(Math.max(0,total-1),offset+8);show(next);}return true;}int index=scroll+(int)(my-y-2)/13;if(index>=0&&index<rows.size()&&rows.get(index).click()!=null)rows.get(index).click().run();return true;}
    @Override public boolean mouseScrolled(double mx,double my,double amount){scroll+=amount>0?-2:2;return true;}
    @Override public boolean charTyped(char value,int mods){if(!editing||!Character.isDigit(value))return false;input.type(value);return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(!editing)return false;if(key==GLFW.GLFW_KEY_BACKSPACE)input.delete(true);else if(key==GLFW.GLFW_KEY_DELETE)input.delete(false);else if(key==GLFW.GLFW_KEY_ENTER)editing=false;else if(key==GLFW.GLFW_KEY_A&&(mods&GLFW.GLFW_MOD_CONTROL)!=0)input.selectAll();else return false;return true;}
    @Override public boolean onBack(){if(selected==null)return false;selected=null;editing=false;return true;}
}
