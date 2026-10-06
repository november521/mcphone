package com.november.mcphone.feature.store.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.script.client.*;
import com.november.mcphone.core.script.layout.TextInputBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.Consumer;

/** 管理入口与二次确认页均由原生绘制。关闭、换服和撤权丢弃旧响应，差异全部查看后才显示保存。 */
public final class NativeAdminPage implements IPhonePage {
    private record Row(String text,Runnable click){}
    private final List<Row> rows=new ArrayList<>();
    private final List<String> differences=new ArrayList<>();
    private final TextInputBuffer[] fields={new TextInputBuffer("",36),new TextInputBuffer("0",19),new TextInputBuffer("",19),new TextInputBuffer("",64),new TextInputBuffer("",128)};
    private JsonArray items=new JsonArray();private JsonObject selected;
    private String kind="root",app="",token="",message="",updateMode="optional";
    private int offset,next,total,diffNext,diffTotal,focus=-1,scroll,x,y,width,height;
    private boolean busy,closed,followAuthor=true;private long generation;
    @Override public void onOpen(){closed=false;generation++;}
    @Override public void onClose(){closed=true;generation++;token="";focus=-1;}
    @Override public boolean capturesKeyboard(){return focus>=0;}
    private void rpc(String action,JsonObject args,Consumer<JsonObject> success){busy=true;long expected=generation,epoch=ClientHandshake.connectionEpoch();ClientStore.rpc(action,args,value->{if(closed||expected!=generation||epoch!=ClientHandshake.connectionEpoch())return;busy=false;success.accept(value);},error->{if(!closed&&expected==generation&&epoch==ClientHandshake.connectionEpoch()){busy=false;message=error;}});}
    private void load(String category,int start,String filter){generation++;kind=category;app=filter;selected=null;focus=-1;token="";offset=start;scroll=0;JsonObject args=ClientStore.args("kind",category);args.addProperty("offset",start);args.addProperty("app",filter);rpc("admin.list",args,data->{items=data.getAsJsonArray("items");next=data.get("next").getAsInt();total=data.get("total").getAsInt();message="";});}
    private void preview(JsonObject operation){JsonObject args=new JsonObject();args.add("operation",operation);focus=-1;rpc("admin.preview",args,data->{token=data.get("token").getAsString();differences.clear();diff(data);scroll=0;});}
    private void diff(JsonObject data){for(JsonElement row:data.getAsJsonArray("differences"))differences.add(row.getAsString());diffNext=data.get("next").getAsInt();diffTotal=data.get("total").getAsInt();}
    private void moreDiff(){JsonObject args=ClientStore.args("token",token);args.addProperty("offset",diffNext);rpc("admin.previewPage",args,this::diff);}
    private void commit(){rpc("admin.commit",ClientStore.args("token",token),data->{message="已保存，并记录审计";token="";differences.clear();selected=null;load(kind,0,app);});}
    private void open(IPhonePage page){if(Minecraft.getInstance().screen instanceof com.november.mcphone.core.client.PhoneScreen phone)phone.openAddonPage(page);}
    private void copy(String value){Minecraft.getInstance().keyboardHandler.setClipboard(value);message="已复制";}
    private static JsonObject operation(String kind){return ClientStore.args("kind",kind);}
    private void preset(String name){JsonObject op=operation("preset");op.addProperty("value",name);preview(op);}
    private void toggle(String category,String id,boolean enabled){JsonObject op=operation(category);op.addProperty("id",id);op.addProperty("enabled",enabled);preview(op);}
    private void license(String mode){JsonObject op=operation("license");op.addProperty("app",selected.get("id").getAsString());op.addProperty("mode",mode);op.addProperty("player",fields[0].text());preview(op);}
    private void resolve(boolean occurred){JsonObject op=operation("settlement");for(String field:List.of("ledger","id","owner"))op.add(field,selected.get(field));op.addProperty("occurred",occurred);preview(op);}
    private void policy(){JsonObject op=operation("appPolicy");op.addProperty("app",selected.get("id").getAsString());op.addProperty("minimum",fields[1].text());op.addProperty("revokedVersion",fields[2].text());op.addProperty("digest",fields[3].text());op.addProperty("reason",fields[4].text());op.addProperty("followAuthor",followAuthor);op.addProperty("frontendUpdate",updateMode);preview(op);}
    private void select(JsonObject item){selected=item;scroll=0;focus=-1;if(kind.equals("deployments")){set(1,item.get("minimum").getAsString());set(2,"");set(3,"");set(4,item.get("reason").getAsString());followAuthor=item.get("followAuthor").getAsBoolean();updateMode=item.get("frontendUpdate").getAsString();}}
    private void set(int index,String value){fields[index].selectAll();fields[index].replace(value);}
    private void row(String text,Runnable action){rows.add(new Row(text,action));}
    private void text(String value){text(value,null,s->Minecraft.getInstance().font.plainSubstrByWidth(s,Math.max(12,width-6)));}
    private void text(String value,Runnable click,java.util.function.UnaryOperator<String> fit){String remaining=value;while(!remaining.isEmpty()){String part=fit.apply(remaining);if(part.isEmpty())part=remaining.substring(0,remaining.offsetByCodePoints(0,1));row(part,click);remaining=remaining.substring(part.length());}}
    private void field(int index,String label){field(index,label,s->Minecraft.getInstance().font.plainSubstrByWidth(s,Math.max(12,width-6)));}
    /** 标签及每一行内容都能聚焦；分行函数可在无窗口测试中替换，点击仍走真实页面。 */
    private void field(int index,String label,java.util.function.UnaryOperator<String> fit){
        Runnable edit=()->{focus=index;fields[index].selectAll();message="正在编辑："+label+"；Ctrl+V 粘贴，Enter 完成";};
        row((focus==index?"▶ ":"")+label+(focus==index?"（输入中）":"（点击编辑）"),edit);
        String value=fields[index].text();if(focus==index)value=value.substring(0,fields[index].cursor())+"▏"+value.substring(fields[index].cursor());
        text(value.isEmpty()?"（空，点击输入）":value,edit,fit);
    }
    private void build(){rows.clear();
        if(!token.isEmpty()){row("请逐项核对变更",null);for(String line:differences)text(line.replace("serverconfig/mcphone-capabilities.json/","能力 / ").replace("serverconfig/mcphone-script-runtime.json/","运行设置 / ").replace("serverconfig/mcphone-quotas.json/limits/","配额 / "));if(diffNext<diffTotal)row("继续查看差异 "+diffNext+" / "+diffTotal,this::moreDiff);else row("确认保存以上更改",this::commit);row("取消更改",()->{token="";differences.clear();scroll=0;});return;}
        if(kind.equals("root")){row("服务器管理",null);String[] categories={"settings","capabilities","boundaries","deployments","published","authors","economy","permissions","commands","pending"};String[] names={"预设与功能开关","应用能力","世界边界","部署与使用许可","上架应用的作者更新","作者禁用","经济状态与对账","权限名单（只读）","命令模板开关","不明结果人工核对"};for(int i=0;i<categories.length;i++){String category=categories[i];row(names[i],()->load(category,0,""));}row("配额与占用排行",()->open(new QuotaPage()));row("作品与审批队列",()->open(new ServerManagementPage()));row("审计导出",()->open(new AuditExportPage()));row("礼包与容器编辑",()->open(new com.november.mcphone.feature.gifts.client.GiftApp().openPage()));row("预览重载服务器配置",()->preview(operation("reload")));return;}
        if(selected==null){row("返回管理首页",()->{kind="root";scroll=0;});if(kind.equals("settings")){row("切到硬核预设",()->preset("hardcore"));row("切到标准预设",()->preset("standard"));row("切到开放预设",()->preset("open"));}for(JsonElement e:items){JsonObject item=e.getAsJsonObject();String suffix=item.has("enabled")?item.get("enabled").getAsBoolean()?" · 开":" · 关":"";row(item.get("name").getAsString()+suffix,()->select(item));}row("刷新",()->load(kind,offset,app));if(offset>0)row("上一批",()->load(kind,Math.max(0,offset-8),app));if(next<total)row("下一批",()->load(kind,next,app));return;}
        text(selected.get("name").getAsString());row("复制记录编号",()->copy(selected.get("id").getAsString()));String id=selected.get("id").getAsString();
        switch(kind){
            case "settings"->{if(id.equals("server_scripts")){JsonObject op=operation("scripting");op.addProperty("enabled",!selected.get("enabled").getAsBoolean());row("预览切换服务端脚本",()->preview(op));}else if(id.equals("server_fetch")||id.equals("client_fetch")){JsonObject op=operation("network");boolean server=false,client=false;for(JsonElement e:items){JsonObject item=e.getAsJsonObject();if(item.get("id").getAsString().equals("server_fetch"))server=item.get("enabled").getAsBoolean();if(item.get("id").getAsString().equals("client_fetch"))client=item.get("enabled").getAsBoolean();}op.addProperty("server",id.equals("server_fetch")?!server:server);op.addProperty("client",id.equals("client_fetch")?!client:client);row("预览切换外网开关",()->preview(op));}else text("预设不会启用服务端脚本或跨服信任；开放外网仍受域名许可限制。");}
            case "capabilities","boundaries"->{if(selected.has("note"))text(selected.get("note").getAsString());if(selected.has("available")&&!selected.get("available").getAsBoolean())text("此版本没有执行入口，打开开关不会新增功能。");row("预览切换开关",()->toggle(kind.equals("capabilities")?"capability":"boundary",id,!selected.get("enabled").getAsBoolean()));}
            case "authors"->{text(selected.get("fingerprint").getAsString());JsonObject op=operation("author");op.addProperty("key",id);op.addProperty("blocked",selected.get("enabled").getAsBoolean());row(selected.get("enabled").getAsBoolean()?"预览禁用作者":"预览恢复作者",()->preview(op));}
            case "deployments"->{text(selected.get("everyone").getAsBoolean()?"目前对所有玩家开放":"目前使用指定玩家名单");row("查看指定玩家",()->load("licenses",0,id));field(0,"玩家 UUID");row("预览授权此玩家",()->license("specific"));row("预览撤销此玩家",()->license("revoke"));row("预览对所有人开放",()->license("all"));row("预览取消所有人许可",()->license("unlicenseAll"));row("预览清除全部许可",()->license("clear"));field(1,"最低可用版本");field(2,"新增吊销版本（可留空）");field(3,"新增吊销摘要（可留空）");field(4,"变更理由");row("跟随作者撤销："+(followAuthor?"是":"否"),()->followAuthor=!followAuthor);row("前端更新："+updateMode,()->updateMode=switch(updateMode){case "optional"->"forced";case "forced"->"off";default->"optional";});row("预览版本与更新策略",this::policy);row("跟随作者更新："+(selected.get("followUpdates").getAsBoolean()?"是":"否"),()->{JsonObject follow=operation("authorUpdates");follow.addProperty("app",id);follow.addProperty("enabled",!selected.get("followUpdates").getAsBoolean());preview(follow);});JsonObject op=operation("remove");op.addProperty("app",id);row("预览撤掉这个部署",()->preview(op));}
            case "licenses"->{JsonObject op=operation("license");op.addProperty("app",app);op.addProperty("mode","revoke");op.addProperty("player",id);row("预览撤销此玩家许可",()->preview(op));}
            case "economy"->text(selected.get("audit").getAsString());
            case "permissions"->text(selected.get("note").getAsString());
            case "published"->{text("当前版本："+selected.get("version").getAsString());JsonObject follow=operation("authorUpdates");follow.addProperty("app",id);follow.addProperty("enabled",!selected.get("followUpdates").getAsBoolean());row("跟随作者更新："+(selected.get("followUpdates").getAsBoolean()?"是":"否"),()->preview(follow));}
            case "commands"->{if(id.equals("switches")){boolean enabled=selected.get("enabled").getAsBoolean(),others=selected.get("others").getAsBoolean();JsonObject op=operation("commandSwitch");op.addProperty("enabled",!enabled);op.addProperty("others",others);row("预览切换命令模板总开关",()->preview(op));JsonObject range=operation("commandSwitch");range.addProperty("enabled",enabled);range.addProperty("others",!others);row("影响其他玩家："+(others?"开":"关"),()->preview(range));}else text(selected.get("note").getAsString());}
            case "pending"->{text("先核对原主背包或钱包流水。确认已发生不会重复给物品或给钱。");row("预览确认已经发生",()->resolve(true));row("预览确认未发生，允许重试",()->resolve(false));}
        }row("返回列表",()->{selected=null;focus=-1;scroll=0;});
    }
    @Override public void render(PhoneCanvas c){x=c.x();y=c.y();width=c.width();height=c.height();if(!ClientAdministration.admin()){c.graphics().drawString(c.font(),"管理权限已失效",x+3,y+12,c.style().bodyColor(),false);return;}build();int count=Math.max(1,(height-24)/13);scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-count)));for(int i=0;i<count&&scroll+i<rows.size();i++){Row row=rows.get(scroll+i);int top=y+2+i*13;if(row.click()!=null)c.graphics().fill(x+1,top,x+width-1,top+12,c.style().buttonColor());c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(row.text(),width-6),x+3,top+2,c.style().bodyColor(),false);}c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(busy?"处理中…":message,width-6),x+3,y+height-12,c.style().bodyColor(),false);}
    @Override public boolean mouseClicked(double mx,double my,int button){if(mx<x||mx>=x+width||my<y||my>=y+height)return false;if(button!=0||busy||!ClientAdministration.admin()||my<y+2||my>=y+height-24)return true;int index=scroll+(int)(my-y-2)/13;if(index>=0&&index<rows.size()&&rows.get(index).click()!=null)rows.get(index).click().run();return true;}
    @Override public boolean mouseScrolled(double mx,double my,double amount){scroll+=amount>0?-2:2;return true;}
    @Override public boolean charTyped(char c,int mods){if(focus<0)return false;fields[focus].type(c);return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(focus<0)return false;TextInputBuffer input=fields[focus];boolean ctrl=(mods&GLFW.GLFW_MOD_CONTROL)!=0,shift=(mods&GLFW.GLFW_MOD_SHIFT)!=0;if(key==GLFW.GLFW_KEY_BACKSPACE)input.delete(true);else if(key==GLFW.GLFW_KEY_DELETE)input.delete(false);else if(key==GLFW.GLFW_KEY_ENTER){focus=-1;message="输入完成，请预览并确认更改";}else if(ctrl&&key==GLFW.GLFW_KEY_A)input.selectAll();else if(ctrl&&key==GLFW.GLFW_KEY_V)input.replace(Minecraft.getInstance().keyboardHandler.getClipboard());else if(ctrl&&key==GLFW.GLFW_KEY_C)Minecraft.getInstance().keyboardHandler.setClipboard(input.selected());else if(key==GLFW.GLFW_KEY_LEFT)input.move(-1,shift);else if(key==GLFW.GLFW_KEY_RIGHT)input.move(1,shift);else if(key==GLFW.GLFW_KEY_HOME)input.moveTo(0,shift);else if(key==GLFW.GLFW_KEY_END)input.moveTo(input.text().length(),shift);else return false;return true;}
    @Override public boolean onBack(){if(!token.isEmpty()){generation++;busy=false;token="";differences.clear();return true;}if(selected!=null){generation++;busy=false;selected=null;focus=-1;return true;}if(!kind.equals("root")){generation++;kind="root";busy=false;scroll=0;return true;}return false;}
}
