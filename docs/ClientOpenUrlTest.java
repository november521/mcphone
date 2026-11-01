package com.november.mcphone.core.script.client;

import com.november.mcphone.core.script.client.render.FontMeasure;
import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.layout.*;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.*;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Real compiler, transactions, mouse dispatch and sample. The OS opener is captured, never launched. */
public final class ClientOpenUrlTest {
    private static int checks;
    private static final class Font extends com.november.mcphone.test.TestFontBase {
        private final StringSplitter splitter=new StringSplitter((cp,style)->6);
        @Override public StringSplitter getSplitter(){return splitter;}
        @Override public int width(String s){return (int)Math.ceil(splitter.stringWidth(s));}
        @Override public int width(FormattedText s){return (int)Math.ceil(splitter.stringWidth(s));}
        @Override public int width(FormattedCharSequence s){return (int)Math.ceil(splitter.stringWidth(s));}
    }
    private static final Font FONT=new Font();
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static String source(String action,String script){return "<template>\n<column class=\"page\"><button id=\"go\" :enabled=\"enabled\" @click=\""+action+"\">open</button></column>\n</template>\n<script>\n"+script+"\n</script>\n<style>\n.page{width:fill;height:fill;padding:4;gap:3;}\n</style>";}
    private static final String INITIAL="state={url:'https://www.mcmod.cn/class/2021.html',n:0,enabled:true}\n";
    private static Node find(Node n,String id){if(id.equals(n.id()))return n;for(Node child:n.children()){Node found=find(child,id);if(found!=null)return found;}return null;}
    private static LayoutNode find(LayoutNode n,String id){if(id.equals(n.node.id()))return n;for(LayoutNode child:n.children){LayoutNode found=find(child,id);if(found!=null)return found;}return null;}
    private static List<Node> links(Node n){List<Node> out=new ArrayList<>();if(n.type()==NodeType.BUTTON&&n.str("text","").equals("查看原文"))out.add(n);for(Node c:n.children())out.addAll(links(c));return out;}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object invoke(Object o,String name,Class<?>[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static Statements.Outcome click(SfcCompiler.Page page,UiState state,Supplier<LocalStore> storage,HttpRequests requests){var tree=new TemplateInstance(page.template(),"url.vue").instantiate(state);return tree.click(find(tree.root(),"go"),state,0,storage,requests);}
    private static void validation(){
        for(String url:List.of("https://www.mcmod.cn/class/2021.html","http://localhost:8080/a?q=1#part","HTTPS://example.com/a%20b?x=%E6%9C%BA#正文","https://[::1]/", "https://example.com/"+"a".repeat(2048-20))){check(WebLinks.checked(url).equals(url),"valid HTTP link preserved");}
        List<Object> invalid=new ArrayList<>(List.of("",1,true,List.of(),Map.of(),"/relative","//example.com","file:///C:/test","javascript:alert(1)","data:text/plain,x","mailto:x@example.com","ftp://example.com","https://u:p@example.com/","https://example.com:0/","https://example.com:65536/","https://exa mple.com/","https://example.com/\n","https://example.com/"+"a".repeat(2049)));
        invalid.add(null);
        for(Object value:invalid){boolean rejected=false;try{WebLinks.checked(value);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"reject invalid/non-web address");}
        check(new Statements.Outcome(true,false,false,null,List.of(),List.of()).externalUrl()==null,"old six-argument Outcome constructor");
        check(new Statements.Outcome(true,false,false,null,List.of(),List.of(),PageParameters.EMPTY).externalUrl()==null,"old seven-argument Outcome constructor");
    }
    private static void compilerAndTransactions(Path root)throws Exception{
        for(String action:List.of("openUrl()","openUrl(1)","openUrl(true)","openUrl(null)","openUrl([])","openUrl({})","openUrl(url,url)","openUrl(url);openUrl(url)")){
            boolean rejected=false;try{SfcCompiler.compilePage("bad.vue",source(action,INITIAL));}catch(SfcError e){rejected=e.line()>0;}check(rejected,"invalid action rejected: "+action);
        }
        var page=SfcCompiler.compilePage("url.vue",source("n++;openUrl(url)",INITIAL));UiState state=UiState.page(page.template().initialState());
        var out=click(page,state,null,null);check(out.applied()&&state.getInt("n")==1&&out.externalUrl().equals(state.getString("url")),"click stages one URL after state commit");
        state.set("url","file:///test");out=click(page,state,null,null);check(!out.applied()&&out.externalUrl()==null&&state.getInt("n")==1,"bad URL rolls back earlier assignment");
        page=SfcCompiler.compilePage("url.vue",source("openUrl(url);url=1",INITIAL));state=UiState.page(page.template().initialState());out=click(page,state,null,null);check(!out.applied()&&out.externalUrl()==null,"later failure discards staged link");
        page=SfcCompiler.compilePage("url.vue",source("url='https://example.com/new';openUrl(url)",INITIAL));state=UiState.page(page.template().initialState());out=click(page,state,null,null);check(out.externalUrl().equals("https://example.com/new"),"URL expression sees earlier same-click values");
        page=SfcCompiler.compilePage("legacy.vue",source("openUrl(url)",INITIAL+"function openUrl(value){state.n++;}"));state=UiState.page(page.template().initialState());out=click(page,state,null,null);check(out.applied()&&out.externalUrl()==null&&state.getInt("n")==1,"existing same-name function keeps old meaning");
        boolean rejected=false;try{SfcCompiler.compilePage("input.vue","<template>\n<text-input bind=\"url\" @input=\"openUrl(url)\" />\n</template>\n<script>\n"+INITIAL+"\n</script>");}catch(SfcError e){rejected=e.code()==SfcError.Code.E_EXPR_SYNTAX;}check(rejected,"input cannot launch browser");
        for(String hook:List.of("onInit","onResume")){
            var automatic=SfcCompiler.compilePage("automatic.vue",source("n++",INITIAL+"function "+hook+"(){openUrl(state.url);}"));var states=new PageStates();var opened=states.open("",automatic.template(),"automatic.vue",null);
            if(hook.equals("onResume"))opened=states.open("",automatic.template(),"automatic.vue",null);
            check(!opened.warnings().isEmpty(),"browser operation not exposed to "+hook);
        }
        Supplier<LocalStore> storage=()->LocalStore.openClient(root,new UUID(0,1),"test:open_url");LocalStore initial=storage.get();initial.setString("keep","old");check(initial.save(),"seed saved store");
        String mutate="function change(){state.n++;ctx.store.setString('keep','new');ctx.http.request({url:'https://example.invalid/'},'received');}\nfunction received(s,b,e){}";
        page=SfcCompiler.compilePage("url.vue",source("change();openUrl(url)",INITIAL+mutate));state=UiState.page(page.template().initialState());state.set("url","javascript:x");List<HttpRequests.Request> sent=new ArrayList<>();
        out=click(page,state,storage,new HttpRequests(4,sent::addAll));check(!out.applied()&&sent.isEmpty()&&state.getInt("n")==0&&storage.get().getString("keep","").equals("old"),"invalid link rolls back state/store/staged HTTP");
        Path conflict=LocalStore.clientPath(root,new UUID(0,1),"test:open_url_conflict");LocalStore loaded=LocalStore.openClient(root,new UUID(0,1),"test:open_url_conflict");Files.createDirectories(conflict);Files.writeString(conflict.resolve("keep.txt"),"kept");
        Supplier<LocalStore> broken=()->loaded;state=UiState.page(page.template().initialState());sent.clear();out=click(page,state,broken,new HttpRequests(4,sent::addAll));check(!out.applied()&&out.externalUrl()==null&&sent.isEmpty()&&state.getInt("n")==0,"disk failure prevents opening link and sending HTTP");
        check(Files.readString(conflict.resolve("keep.txt")).equals("kept"),"disk conflict preserved");
    }
    private static void hostAndSample()throws Exception{
        String manifest="<manifest>\n{\"format\":1,\"id\":\"test:open_url\",\"version\":\"1.0.0\",\"name\":\"open\",\"author\":\"test\"}\n</manifest>\n";
        ScriptApp app=ScriptAppFolder.read("url.vue",(manifest+source("openUrl(url)",INITIAL)).getBytes(StandardCharsets.UTF_8));List<String> opened=new ArrayList<>();ScriptPage host=new ScriptPage(app,opened::add);
        invoke(host,"openPage",new Class<?>[]{String.class},"");set(host,"font",FONT);set(host,"cw",120);set(host,"ch",176);invoke(host,"relayout",new Class<?>[]{int.class,int.class,TextMeasure.class},120,176,new FontMeasure(FONT));
        check(opened.isEmpty(),"loading/layout never launches browser");LayoutNode button=find((LayoutNode)field(host,"layout"),"go");check(host.mouseClicked(button.x+button.w/2.0,button.y+button.h/2.0,0),"actual mouse click handled");check(opened.equals(List.of("https://www.mcmod.cn/class/2021.html")),"actual page dispatch opens exactly the clicked URL");
        UiState state=(UiState)field(host,"state");state.set("enabled",false);invoke(host,"relayout",new Class<?>[]{int.class,int.class,TextMeasure.class},120,176,new FontMeasure(FONT));button=find((LayoutNode)field(host,"layout"),"go");host.mouseClicked(button.x+button.w/2.0,button.y+button.h/2.0,0);check(opened.size()==1,"disabled button does not open URL");host.onClose();
        app=ScriptAppFolder.read("mcmod-search.vue",Files.readAllBytes(Path.of("../../docs/samples/mcmod-search.vue")));var entry=app.entry();state=UiState.page(entry.template().initialState());
        String html="<div class=\"search-result-list\"><div class=\"result-item\"><a href=\"https://www.mcmod.cn/class/2021.html\">示例 A</a><div class=\"body\">摘要 A</div><div class=\"result-item\"><a href=\"https://www.mcmod.cn/class/7687.html\">示例 B</a><div class=\"body\">摘要 B</div>";
        var request=new HttpRequests.Request("https://search.mcmod.cn/s?key=test","GET",Map.of(),"",10000,"received");check(PageStates.respond(entry.template().script(),request,new HttpRequests.Result(200,html,""),state,"sample.vue",null,null).isEmpty(),"sample response uses real sandbox");
        var tree=new TemplateInstance(entry.template(),"sample.vue").instantiate(state);var buttons=links(tree.root());check(buttons.size()==2,"each visible sample result has one link button");
        for(int i=0;i<buttons.size();i++){outcomeCheck(tree.click(buttons.get(i),state,0,null,null),i==0?"https://www.mcmod.cn/class/2021.html":"https://www.mcmod.cn/class/7687.html");}
        for(int[] size:List.of(new int[]{120,176},new int[]{226,158})){var layout=LayoutEngine.layout(tree.root(),entry.stylesheet(),state,size[0],size[1],new FontMeasure(FONT),ImageSizes.NONE);check(find(layout,"query").w>20&&find(layout,"results").h>30,"sample phone/tablet search and results fit");}
    }
    private static void outcomeCheck(Statements.Outcome out,String url){check(out.applied()&&url.equals(out.externalUrl()),"loop row opens its own original URL");}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("mcphone-open-url-").toAbsolutePath().normalize();
        try{validation();compilerAndTransactions(root);hostAndSample();}finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("ClientOpenUrlTest: "+checks+" checks passed");
    }
}
