package com.november.mcphone.feature.store.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.script.client.*;
import com.november.mcphone.core.script.layout.TextInputBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** 明确过滤范围后再提交；查询由点击刷新触发，关闭页面不取消已在服务器生成的报告。 */
public final class AuditExportPage implements IPhonePage {
    private record Row(String text,Runnable click){}
    private final List<Row> rows=new ArrayList<>();private final TextInputBuffer[] fields={new TextInputBuffer("example:market",64),new TextInputBuffer("0",19),new TextInputBuffer(Long.toString(Long.MAX_VALUE),19),new TextInputBuffer(LocalDate.now(ZoneOffset.UTC).minusDays(7).toString(),10),new TextInputBuffer(LocalDate.now(ZoneOffset.UTC).toString(),10)};
    private static final String[] LABELS={"应用 ID","版本下限（含）","版本上限（含）","UTC 开始日期","UTC 结束日期"};private int x,y,width,height,scroll,focus=-1;private boolean closed,busy,confirm;private long generation;private String id="",message="",file="";
    @Override public void onOpen(){closed=false;generation++;}
    @Override public void onClose(){closed=true;generation++;}
    @Override public boolean capturesKeyboard(){return focus>=0;}
    private void send(){JsonObject args=new JsonObject();args.addProperty("app",fields[0].text());args.addProperty("minimum",fields[1].text());args.addProperty("maximum",fields[2].text());args.addProperty("from",fields[3].text());args.addProperty("to",fields[4].text());busy=true;long expected=generation;ClientStore.rpc("admin.audit.export",args,data->{if(closed||generation!=expected)return;busy=false;confirm=false;id=data.get("id").getAsString();message="导出已排队，点击刷新查看结果";},error->{if(!closed&&generation==expected){busy=false;message=error;}});}
    private void status(){busy=true;long expected=generation;ClientStore.rpc("admin.audit.status",ClientStore.args("id",id),data->{if(closed||generation!=expected)return;busy=false;file=data.get("file").getAsString();message=switch(data.get("state").getAsString()){case "COMPLETE"->"已导出 "+data.get("rows").getAsString()+" 条";case "FAILED"->"导出失败，请缩小范围并查看服务端日志";default->"服务器正在生成报告";};},error->{if(!closed&&generation==expected){busy=false;message=error;}});}
    private void build(){rows.clear();for(int i=0;i<fields.length;i++){int at=i;rows.add(new Row(LABELS[i]+"：",()->{focus=at;fields[at].selectAll();confirm=false;}));rows.add(new Row(fields[i].text(),()->{focus=at;fields[at].selectAll();confirm=false;}));}rows.add(new Row(confirm?"确认以上范围并导出":"核对过滤范围",()->{focus=-1;if(confirm)send();else confirm=true;}));if(!id.isEmpty())rows.add(new Row("刷新导出状态",this::status));if(!file.isEmpty()){rows.add(new Row("复制服务器报告路径",()->Minecraft.getInstance().keyboardHandler.setClipboard(file)));rows.add(new Row("报告位于服务器世界目录",null));}}
    @Override public void render(PhoneCanvas c){x=c.x();y=c.y();width=c.width();height=c.height();if(!ClientAdministration.admin()){c.graphics().drawString(c.font(),"管理权限已失效",x+3,y+10,c.style().bodyColor(),false);return;}build();int count=Math.max(1,(height-24)/13);scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-count)));for(int i=0;i<count&&scroll+i<rows.size();i++){Row row=rows.get(scroll+i);int py=y+2+i*13;if(row.click()!=null)c.graphics().fill(x+1,py,x+width-1,py+12,c.style().buttonColor());c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(row.text(),width-6),x+3,py+2,c.style().bodyColor(),false);}c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(busy?"处理中…":message,width-6),x+3,y+height-12,c.style().bodyColor(),false);}
    @Override public boolean mouseClicked(double mx,double my,int button){if(mx<x||mx>=x+width||my<y||my>=y+height)return false;if(button!=0||busy||!ClientAdministration.admin())return true;int at=scroll+(int)(my-y-2)/13;if(at>=0&&at<rows.size()&&rows.get(at).click()!=null)rows.get(at).click().run();return true;}
    @Override public boolean mouseScrolled(double mx,double my,double amount){scroll+=amount>0?-2:2;return true;}
    @Override public boolean charTyped(char c,int mods){if(focus<0)return false;fields[focus].type(c);confirm=false;return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(focus<0)return false;TextInputBuffer input=fields[focus];if(key==GLFW.GLFW_KEY_BACKSPACE)input.delete(true);else if(key==GLFW.GLFW_KEY_DELETE)input.delete(false);else if(key==GLFW.GLFW_KEY_ENTER)focus=-1;else if(key==GLFW.GLFW_KEY_A&&(mods&GLFW.GLFW_MOD_CONTROL)!=0)input.selectAll();else if(key==GLFW.GLFW_KEY_V&&(mods&GLFW.GLFW_MOD_CONTROL)!=0)input.replace(Minecraft.getInstance().keyboardHandler.getClipboard());else return false;confirm=false;return true;}
}
