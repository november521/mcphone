package com.november.mcphone.feature.store.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.script.client.*;
import com.november.mcphone.core.script.layout.TextInputBuffer;
import com.november.mcphone.core.script.pkg.*;
import com.november.mcphone.core.script.server.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** 原生审核页面：不运行提交者 UI；完整摘要、公钥、提交者、清单、逐项能力和全文源码都可查看。 */
public final class ServerManagementPage implements IPhonePage {
    private enum View {WORKS,QUEUE,DETAIL,FILES,SOURCE,CAPS,ACTIONS,CONFIRM}
    private View view=View.WORKS;
    private record Row(String text,int color,Runnable click) { }
    private final List<Row> rows=new ArrayList<>();
    private List<ScriptApp> works=List.of();private JsonArray queue=new JsonArray(),files=new JsonArray();
    private JsonObject selected,manifest;private final Set<String> approvedCaps=new LinkedHashSet<>(),approvedActions=new LinkedHashSet<>();
    private String source="",sourcePath="",message="",visibility="public",detection="";private boolean reviewer,busy,closed,licenseAll,approve=true;
    private int offset,total,scroll,x,y,width,height,rowCount,focus=-1,horizontal;
    private long generation;
    private final TextInputBuffer reason=new TextInputBuffer("人工审核",128),phrase=new TextInputBuffer("",128);
    @Override public void onOpen(){closed=false;generation++;works=ScriptAppFolder.scan().stream().filter(a->a.pkg()!=null&&a.pkg().signed()).toList();
        ClientStore.rpc("store.roles",new JsonObject(),data->{if(!closed)reviewer=data.get("reviewer").getAsBoolean();},this::error);}
    @Override public void onClose(){closed=true;generation++;}
    private void error(String text){if(!closed){busy=false;message=text;}}
    private void change(View next){view=next;scroll=0;focus=-1;horizontal=0;message="";}
    private String digest(){return selected.get("digest").getAsString();}
    private void refreshQueue(){busy=true;long expected=generation;ClientStore.rpc("store.queue",ClientStore.offset(offset),data->{if(closed||expected!=generation)return;busy=false;queue=data.getAsJsonArray("items");total=data.get("total").getAsInt();},this::error);}
    private void detail(JsonObject object){selected=object;manifest=null;approvedCaps.clear();approvedActions.clear();change(View.DETAIL);readFile("manifest.json",text->{manifest=JsonParser.parseString(text).getAsJsonObject();});}
    private void fileList(int offset){busy=true;JsonObject args=ClientStore.args("digest",digest());args.addProperty("path","");args.addProperty("offset",offset);
        ClientStore.rpc("store.inspect",args,data->{if(closed)return;busy=false;files=data.getAsJsonArray("files");total=data.get("total").getAsInt();this.offset=offset;change(View.FILES);},this::error);}
    private void readFile(String path,java.util.function.Consumer<String> done){busy=true;long expected=generation;readChunk(path,new ByteArrayOutputStream(),expected,done);}
    private void readChunk(String path,ByteArrayOutputStream bytes,long expected,java.util.function.Consumer<String> done){
        JsonObject args=ClientStore.args("digest",digest());args.addProperty("path",path);args.addProperty("offset",bytes.size());
        ClientStore.rpc("store.inspect",args,data->{if(closed||generation!=expected)return;
            byte[] part=Base64.getDecoder().decode(data.get("bytes").getAsString());int size=data.get("size").getAsInt();
            if(size<0||size>PackageReader.MAX_ENTRY_INFLATED||part.length!=Math.min(StoreTransfer.CHUNK,size-bytes.size())||data.get("next").getAsInt()!=bytes.size()+part.length){error("源码分片无效");return;}
            bytes.writeBytes(part);if(data.get("eof").getAsBoolean()){if(bytes.size()!=size){error("源码不完整");return;}busy=false;done.accept(bytes.toString(StandardCharsets.UTF_8));}
            else ClientStore.later(()->{if(!closed&&generation==expected)readChunk(path,bytes,expected,done);});
        },this::error);
    }
    private void add(String text,Runnable run){rows.add(new Row(TextInputBuffer.clean(text,2048),0,run));}
    private void build(){rows.clear();switch(view){
        case WORKS -> {add("选择签名包提交上架",null);for(ScriptApp app:works){add(app.manifest().name()+" · "+app.manifest().version(),()->{busy=true;ClientStore.upload(Path.of(app.file()),result->{if(closed)return;busy=false;message="已提交，状态："+result.get("state").getAsString();},this::error);});}if(works.isEmpty())add("mcphone/apps 中没有签名包",null);}
        case QUEUE -> {for(var item:queue){JsonObject o=item.getAsJsonObject();add(o.get("name").getAsString()+" · "+o.get("state").getAsString(),()->detail(o));}}
        case DETAIL -> {
            add("应用："+selected.get("id").getAsString(),null);add("版本："+selected.get("version").getAsString(),null);
            add("复制完整包摘要",()->Minecraft.getInstance().keyboardHandler.setClipboard(digest()));add(digest().substring(0,16)+"…",null);
            add("复制完整作者指纹",()->Minecraft.getInstance().keyboardHandler.setClipboard(selected.get("fingerprint").getAsString()));add(selected.get("fingerprint").getAsString(),null);
            add("复制作者完整公钥",()->Minecraft.getInstance().keyboardHandler.setClipboard(selected.get("pubkey").getAsString()));
            var trust=LocalScriptSource.advertisedTrust(selected.get("id").getAsString(),selected.get("fingerprint").getAsString());
            add("本机作者信任："+trust.state().name(),null);add("提交者名字："+selected.get("submitterName").getAsString(),null);
            add("复制提交者 UUID",()->Minecraft.getInstance().keyboardHandler.setClipboard(selected.get("submitter").getAsString()));add(selected.get("submitter").getAsString(),null);
            add("提交者与签名作者分别核对",null);add("文件清单与完整源码",()->fileList(0));
            if(manifest!=null){add("部署类型："+(manifest.has("deploy")?manifest.get("deploy").getAsString():"client"),null);add("逐项能力 · 已勾选 "+approvedCaps.size(),()->change(View.CAPS));add("逐项动作 · 已勾选 "+approvedActions.size(),()->change(View.ACTIONS));
                if(manifest.has("net")&&manifest.get("net").isJsonObject()) {JsonObject net=manifest.getAsJsonObject("net");if(net.has("hosts")&&net.get("hosts").isJsonArray())for(var host:net.getAsJsonArray("hosts"))add("外网："+host.getAsString(),null);}
            }
            add("可见性："+visibility,()->visibility=visibility.equals("public")?"licensed":visibility.equals("licensed")?"hidden":"public");
            add((licenseAll?"☑":"☐")+" 同时授权所有玩家",()->licenseAll=!licenseAll);
            add("批准（按已勾选的能力）",()->{if(manifest==null)return;approve=true;change(View.CONFIRM);});
            add("驳回并写理由",()->{approve=false;change(View.CONFIRM);});
        }
        case FILES -> {for(var item:files){JsonObject f=item.getAsJsonObject();String path=f.get("path").getAsString();add(path+" · "+f.get("size").getAsInt()+" B",()->{if(!path.endsWith(".js")&&!path.equals("manifest.json")){message="此项为资源文件";return;}readFile(path,text->{source=text;sourcePath=path;detection=path.endsWith(".js")?com.november.mcphone.core.script.engine.ScriptStaticCheck.check(Map.of("server.js",source)):"";change(View.SOURCE);});});}}
        case SOURCE -> {add(sourcePath+" · 复制全文",()->Minecraft.getInstance().keyboardHandler.setClipboard(source));String[] lines=source.split("\\R",-1);
            if(detection!=null&&!detection.isEmpty())rows.add(new Row("单文件检测："+TextInputBuffer.clean(detection,128),0xFFFF7777,null));
            for(int i=0;i<lines.length;i++){String line=TextInputBuffer.clean(lines[i],4096);add((i+1)+" "+(horizontal<line.length()?line.substring(horizontal):""),null);}}
        case CAPS -> {if(manifest.has("capabilities"))for(var value:manifest.getAsJsonArray("capabilities")){String id=value.getAsString();var cap=CapabilityCatalog.of(id);int color=cap==null||cap.tier()!=CapabilityTier.PLAIN?0xFFFF7777:0;
                rows.add(new Row((approvedCaps.contains(id)?"☑ ":"☐ ")+id,color,()->{if(!approvedCaps.remove(id))approvedCaps.add(id);}));}}
        case ACTIONS -> {if(manifest.has("actions"))for(var value:manifest.getAsJsonArray("actions")){String id=value.isJsonPrimitive()?value.getAsString():value.getAsJsonObject().get("id").getAsString();add((approvedActions.contains(id)?"☑ ":"☐ ")+id,()->{if(!approvedActions.remove(id))approvedActions.add(id);});}}
        case CONFIRM -> {add(approve?"将按当前勾选内容批准":"将驳回当前摘要",null);add("理由（点击编辑）",()->focus=0);add(reason.text(),()->focus=0);add("密钥变更时输入新指纹",()->focus=1);add(phrase.text(),()->focus=1);
            add("确认提交审核结果",this::review);add("作者签名不代表内容无害",null);add("特权还需部署审批 UUID 许可",null);}
    }}
    private void review(){busy=true;JsonObject args=ClientStore.args("digest",digest());args.addProperty("approve",approve);args.addProperty("reason",reason.text());args.addProperty("visibility",visibility);args.addProperty("licenseAll",licenseAll);args.addProperty("phrase",phrase.text());
        JsonArray caps=new JsonArray(),actions=new JsonArray();approvedCaps.forEach(caps::add);approvedActions.forEach(actions::add);args.add("capabilities",caps);args.add("actions",actions);
        ClientStore.rpc("store.review",args,data->{if(closed)return;busy=false;change(View.QUEUE);offset=0;refreshQueue();},this::error);}
    @Override public void render(PhoneCanvas c){x=c.x();y=c.y();width=c.width();height=c.height();build();rowCount=Math.max(1,(height-45)/13);scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-rowCount)));
        c.graphics().drawString(c.font(),reviewer?"我的作品  |  审核队列":"我的作品",x+3,y+3,c.style().bodyColor(),false);
        for(int i=0;i<rowCount&&scroll+i<rows.size();i++){Row row=rows.get(scroll+i);int py=y+19+i*13;if(row.click!=null)c.graphics().fill(x+2,py-1,x+width-2,py+11,c.style().buttonColor());
            c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(row.text,Math.max(0,width-8)),x+4,py,row.color==0?c.style().bodyColor():row.color,false);}
        c.graphics().drawString(c.font(),"◀ "+(scroll+1)+"/"+Math.max(1,rows.size())+" ▶",x+3,y+height-25,c.style().subtleColor(),false);
        c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(busy?"处理中…":message,Math.max(0,width-6)),x+3,y+height-12,c.style().bodyColor(),false);
    }
    @Override public boolean mouseClicked(double mx,double my,int button){if(mx<x||mx>=x+width||my<y||my>=y+height)return false;if(button!=0||busy)return true;
        if(my<y+17){if(reviewer&&mx>=x+width/2){change(View.QUEUE);offset=0;refreshQueue();}else change(View.WORKS);return true;}
        if(my>=y+height-29){if(view==View.QUEUE||view==View.FILES){int delta=view==View.QUEUE?2:12;int next=mx<x+width/2?Math.max(0,offset-delta):Math.min(Math.max(0,total-1),offset+delta);if(next!=offset){if(view==View.QUEUE){offset=next;refreshQueue();}else fileList(next);}}else scroll+=mx<x+width/2?-rowCount:rowCount;return true;}
        int index=scroll+(int)(my-y-19)/13;if(my>=y+18&&index>=0&&index<rows.size()&&rows.get(index).click!=null)rows.get(index).click.run();return true;}
    @Override public boolean mouseScrolled(double mx,double my,double amount){scroll+=amount>0?-3:3;return true;}
    @Override public boolean onBack(){if(view==View.WORKS||view==View.QUEUE)return false;change(View.DETAIL);return true;}
    @Override public boolean charTyped(char value,int modifiers){if(focus<0)return false;(focus==0?reason:phrase).type(value);return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(view==View.SOURCE&&(key==GLFW.GLFW_KEY_RIGHT||key==GLFW.GLFW_KEY_LEFT)){horizontal=Math.max(0,horizontal+(key==GLFW.GLFW_KEY_RIGHT?8:-8));return true;}
        if(focus<0)return false;TextInputBuffer input=focus==0?reason:phrase;boolean ctrl=(mods&GLFW.GLFW_MOD_CONTROL)!=0,shift=(mods&GLFW.GLFW_MOD_SHIFT)!=0;
        if(ctrl&&key==GLFW.GLFW_KEY_A)input.selectAll();else if(ctrl&&key==GLFW.GLFW_KEY_V)input.replace(Minecraft.getInstance().keyboardHandler.getClipboard());else if(ctrl&&key==GLFW.GLFW_KEY_C)Minecraft.getInstance().keyboardHandler.setClipboard(input.selected());
        else if(key==GLFW.GLFW_KEY_BACKSPACE)input.delete(true);else if(key==GLFW.GLFW_KEY_DELETE)input.delete(false);else if(key==GLFW.GLFW_KEY_LEFT)input.move(-1,shift);else if(key==GLFW.GLFW_KEY_RIGHT)input.move(1,shift);else if(key==GLFW.GLFW_KEY_TAB)focus=1-focus;else if(key==GLFW.GLFW_KEY_ENTER)focus=-1;else return false;return true;}
}
