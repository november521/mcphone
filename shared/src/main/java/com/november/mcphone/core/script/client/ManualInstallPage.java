package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.script.layout.TextInputBuffer;
import com.november.mcphone.core.script.pkg.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** 已安装 App 的手动替换页：全部原生绘制，核对完整公钥、明确降级和原包摘要后才切换。 */
public final class ManualInstallPage implements IPhonePage {
    private final ScriptApp installed;private ScriptApp candidate;private byte[] envelope;
    private final TextInputBuffer phrase=new TextInputBuffer("",64);private final List<String> lines=new ArrayList<>();
    private boolean checked,busy,closed,focus,done;private long generation;private int x,y,w,h,scroll;private String message="选择来源后核对版本与作者";
    public ManualInstallPage(ScriptApp app){installed=app;}
    @Override public void onOpen(){closed=false;generation++;}
    @Override public void onClose(){closed=true;generation++;candidate=null;envelope=null;}
    @Override public boolean capturesKeyboard(){return focus;}
    private boolean alive(long expected,long epoch){return !closed&&generation==expected&&epoch==ClientHandshake.connectionEpoch();}
    private void choose(ScriptApp app,byte[] bytes){candidate=app;envelope=bytes.clone();checked=false;focus=false;phrase.selectAll();phrase.replace("");scroll=0;message="请核对以下信息";}
    private void local(){generation++;try{for(ScriptApp app:ScriptAppFolder.scan())if(app.id().equals(installed.id())&&app.pkg()!=null&&(installed.pkg()==null||!app.pkg().digest().equals(installed.pkg().digest()))){choose(app,FrontendEnvelope.write(app.pkg()));return;}message="本地目录中没有不同版本。请先放入同一 App ID 的候选包。";}catch(Exception bad){message=bad.getMessage();}}
    private void server(){generation++;busy=true;long expected=generation,epoch=ClientHandshake.connectionEpoch();ClientStore.rpc("store.current",ClientStore.args("app",installed.id().toString()),data->{if(!alive(expected,epoch))return;try{if(!data.get("available").getAsBoolean())throw new IllegalArgumentException("服务器没有可见的已批准版本");JsonObject metadata=data.getAsJsonObject("package");ClientStore.download(metadata.get("digest").getAsString(),raw->{if(!alive(expected,epoch))return;busy=false;try{AppPackage pkg=FrontendEnvelope.read(raw);ServerFrontendUpdate.require(pkg,ServerStoreSource.expected(metadata));choose(ScriptAppFolder.fromPackage("server-store:"+pkg.digest(),pkg),raw);}catch(Exception bad){message=bad.getMessage();}},error->{if(alive(expected,epoch)){busy=false;message=error;}});}catch(Exception bad){busy=false;message=bad.getMessage();}},error->{if(alive(expected,epoch)){busy=false;message=error;}});}
    private boolean changedKey(){return candidate!=null&&!Arrays.equals(installed.authorKey(),candidate.authorKey());}
    private void install(){
        if(candidate==null||!checked||done)return;ScriptApp incoming=candidate;var trust=LocalScriptSource.trustOf(incoming);
        if(!trust.state().installable||(changedKey()||trust.state().needsPhrase())&&!SigCopy.phraseAccepted(phrase.text(),trust.fingerprint())){message="请确认作者，并输入新的完整指纹";return;}
        try{if(!ClientPackageVersions.manualPossible(incoming))throw new IllegalArgumentException("签名冲突或版本历史拒绝替换");}catch(Exception bad){message=bad.getMessage();return;}
        busy=true;long expected=generation,epoch=ClientHandshake.connectionEpoch();ClientRevocations.checkManualInstall(incoming,refused->{if(!alive(expected,epoch))return;busy=false;if(refused!=null){message=refused.getString();return;}try{if(!(PhoneScreenRegistry.getApp(installed.id()) instanceof ScriptAppAdapter current)||current.script()!=installed||candidate!=incoming)throw new IllegalArgumentException("当前 App 已变化，请重新打开确认页");ServerStoreSource.installManualBundle(incoming,envelope);done=true;message="已替换。返回主屏重新打开应用。";candidate=null;envelope=null;}catch(Exception bad){message="替换失败："+bad.getMessage();}});
    }
    private void build(){lines.clear();lines.add("手动更换已安装应用");if(candidate==null){lines.add("本地候选包");lines.add("服务器已批准版本");return;}
        lines.add("应用："+candidate.id());lines.add("当前版本："+installed.versionCode());lines.add("候选版本："+candidate.versionCode());
        if(candidate.versionCode()<Math.max(installed.versionCode(),ClientPackageVersions.highest(candidate)))lines.add("这是比当前或历史版本更旧的版本");
        if(changedKey())lines.add("作者密钥变了");lines.add("原作者公钥："+Base64.getEncoder().encodeToString(installed.authorKey()));lines.add("新作者公钥："+Base64.getEncoder().encodeToString(candidate.authorKey()));
        var trust=LocalScriptSource.trustOf(candidate);lines.add(net.minecraft.network.chat.Component.translatable(SigCopy.keyFor(trust.state())).getString());lines.add(net.minecraft.network.chat.Component.translatable(SigCopy.INSTALL_NOTE).getString());lines.add("新指纹："+trust.fingerprint());lines.add("包摘要："+candidate.pkg().digest());
        if(changedKey()||trust.state().needsPhrase()){lines.add("输入新指纹（点击输入行）");lines.add(phrase.text().isEmpty()?"（未输入）":phrase.text());}
        lines.add(checked?"☑ 已核对作者与版本":"☐ 已核对作者与版本");lines.add("确认替换这个包");
    }
    private record DrawRow(String text,int source){}private final List<DrawRow> rows=new ArrayList<>();
    @Override public void render(PhoneCanvas c){x=c.x();y=c.y();w=c.width();h=c.height();build();rows.clear();for(int i=0;i<lines.size();i++){String text=lines.get(i);while(!text.isEmpty()){String part=c.font().plainSubstrByWidth(text,Math.max(1,w-8));if(part.isEmpty())part=text.substring(0,1);rows.add(new DrawRow(part,i));text=text.substring(part.length());}}
        int count=Math.max(1,(h-26)/12);scroll=Math.max(0,Math.min(scroll,Math.max(0,rows.size()-count)));for(int i=0;i<count&&scroll+i<rows.size();i++)c.graphics().drawString(c.font(),rows.get(scroll+i).text(),x+4,y+3+i*12,c.style().bodyColor(),false);c.graphics().drawString(c.font(),c.font().plainSubstrByWidth(busy?"处理中…":message,w-8),x+4,y+h-16,c.style().bodyColor(),false);
    }
    @Override public boolean mouseClicked(double mx,double my,int button){if(button!=0||busy||done||mx<x||mx>=x+w||my<y+3||my>=y+h-26)return true;int at=scroll+(int)(my-y-3)/12;if(at<0||at>=rows.size())return true;int source=rows.get(at).source();if(candidate==null){if(source==1)local();if(source==2)server();}else{String text=lines.get(source);if(text.startsWith("输入新指纹")||source>0&&lines.get(source-1).startsWith("输入新指纹")){focus=true;phrase.selectAll();}else if(source==lines.size()-2){checked=!checked;focus=false;}else if(source==lines.size()-1)install();}return true;}
    @Override public boolean mouseScrolled(double mx,double my,double amount){scroll+=amount>0?-2:2;return true;}
    @Override public boolean charTyped(char c,int mods){if(!focus)return false;phrase.type(c);return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(!focus)return false;if(key==GLFW.GLFW_KEY_BACKSPACE)phrase.delete(true);else if(key==GLFW.GLFW_KEY_DELETE)phrase.delete(false);else if(key==GLFW.GLFW_KEY_ENTER)focus=false;else if(key==GLFW.GLFW_KEY_V&&(mods&GLFW.GLFW_MOD_CONTROL)!=0)phrase.replace(Minecraft.getInstance().keyboardHandler.getClipboard());else return false;return true;}
    @Override public boolean onBack(){if(candidate!=null){generation++;busy=false;candidate=null;envelope=null;focus=false;scroll=0;return true;}return false;}
}
