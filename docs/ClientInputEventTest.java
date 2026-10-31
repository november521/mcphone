package com.november.mcphone.core.script.client.render;

import com.november.mcphone.core.script.client.ScriptApp;
import com.november.mcphone.core.script.client.ScriptAppFolder;
import com.november.mcphone.core.script.client.ScriptPage;
import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.layout.*;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.*;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real editors, page dispatch, compiler and transactions. Only font/OS clipboard/window boundaries are isolated. */
public final class ClientInputEventTest {
    private static int checks;
    private static final UUID ACCOUNT = new UUID(0, 1);
    private static void check(boolean ok, String why) {checks++; if (!ok) throw new AssertionError(why);}
    private static final class Font extends com.november.mcphone.test.TestFontBase {
        private final StringSplitter splitter = new StringSplitter((cp, style) -> 6);
        @Override public StringSplitter getSplitter() {return splitter;}
        @Override public int width(String s) {return (int) Math.ceil(splitter.stringWidth(s));}
        @Override public int width(FormattedText s) {return (int) Math.ceil(splitter.stringWidth(s));}
        @Override public int width(FormattedCharSequence s) {return (int) Math.ceil(splitter.stringWidth(s));}
        @Override public String plainSubstrByWidth(String s, int width) {return splitter.plainHeadByWidth(s, width, Style.EMPTY);}
        @Override public String plainSubstrByWidth(String s, int width, boolean tail) {return tail ? splitter.plainTailByWidth(s, width, Style.EMPTY) : plainSubstrByWidth(s, width);}
    }
    private static final Font FONT = new Font();
    private static final String INITIAL = "state={draft:'',echo:'',calls:0,shown:true,enabled:true,limit:64}\n";
    private static final String CHANGE = "function changed(){state.calls++;state.echo=state.draft;}\n";
    private static String source(String body, String script) {
        return "<template>\n" + body + "\n</template>\n<script>\n" + script + "\n</script>\n<style>\n.page{width:fill;height:fill;gap:3;padding:2;} .area{height:48;}\n</style>\n";
    }
    private static String editor(boolean multiline, String handler) {
        return "<column class=\"page\"><" + (multiline ? "textarea class=\"area\"" : "text-input")
                + " id=\"edit\" v-if=\"shown\" :enabled=\"enabled\" bind=\"draft\" :max-length=\"limit\" " + handler + " />"
                + "<text>{{echo}}</text></column>";
    }
    private static Object field(Object object, String name) throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void set(Object object, String name, Object value) throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static Object invoke(Object object, String name, Class<?>[] types, Object... values) throws Exception {Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,values);}
    private static ScriptApp readApp(String name, byte[] bytes) throws Exception {Method m=ScriptAppFolder.class.getDeclaredMethod("read",String.class,byte[].class);m.setAccessible(true);return (ScriptApp)m.invoke(null,name,bytes);}
    private static LayoutNode find(LayoutNode root, String id) {if(id.equals(root.node.id()))return root;for(LayoutNode child:root.children){LayoutNode n=find(child,id);if(n!=null)return n;}return null;}
    private static Node find(Node root, String id) {if(id.equals(root.id()))return root;for(Node child:root.children()){Node n=find(child,id);if(n!=null)return n;}return null;}

    private static final class Fixture implements AutoCloseable {
        final ScriptPage host;
        final UiState state;
        final TextInputs inputs;
        String clipboard = "";
        Fixture(boolean multiline, String script) throws Exception {this(editor(multiline,"@input=\"changed()\""),script);}
        Fixture(String body, String script) throws Exception {
            String manifest="<manifest>\n{\"format\":1,\"id\":\"test:input_events\",\"version\":\"1.0.0\",\"name\":\"input\",\"author\":\"test\"}\n</manifest>\n";
            ScriptApp app=readApp("input.vue",(manifest+source(body,script)).getBytes(StandardCharsets.UTF_8));
            host=new ScriptPage(app);
            inputs=new TextInputs(com.november.mcphone.test.TestEditBoxFactory::create,(box,x,y,button)->{},()->clipboard,text->clipboard=text,node->{
                try {invoke(host,"inputChanged",new Class<?>[]{LayoutNode.class},node);} catch(Exception e){throw new AssertionError(e);}
            });
            set(host,"inputs",inputs);set(host,"font",FONT);set(host,"cx",17);set(host,"cy",23);set(host,"cw",120);set(host,"ch",176);
            invoke(host,"openPage",new Class<?>[]{String.class},"");state=(UiState)field(host,"state");
            invoke(host,"relayout",new Class<?>[]{int.class,int.class,TextMeasure.class},120,176,new FontMeasure(FONT));sync();
        }
        void sync() throws Exception {invoke(host,"refreshInputs",new Class<?>[]{});}
        void focus() throws Exception {
            sync();LayoutNode node=find((LayoutNode)field(host,"layout"),"edit");
            check(node!=null && node.w>8 && node.h>0,"editor has usable geometry");
            check(host.mouseClicked(17+node.x+4,23+node.y+4,0) && inputs.focused(),"real page hit path focuses editor");
        }
        EditBox box() throws Exception {return (EditBox)field(field(inputs,"focused"),"box");}
        void paste(String text, boolean multiline) throws Exception {if(multiline){clipboard=text;host.keyPressed(86,0,2);}else box().insertText(text);sync();}
        String text(){return state.getString("draft");}
        @Override public void close(){host.onClose();}
    }

    private static void compiler() {
        for(boolean multiline:List.of(false,true)) {
            String tag=multiline?"textarea":"text-input";
            var page=SfcCompiler.compilePage("input.vue",source(editor(multiline,"@input=\"changed(draft)\""),INITIAL+"function changed(value){state.echo=value;}"));
            check(page.template().script().declares("changed"),"input accepts declared function and existing primitive arguments: "+tag);
            for(String handler:List.of("draft='x'","calls++","nav('detail')","back()","close()","call('server')","changed(); changed()","missing()")) {
                boolean rejected=false;
                try {SfcCompiler.compilePage("input.vue",source(editor(multiline,"@input=\""+handler+"\""),INITIAL+CHANGE));} catch(SfcError e){rejected=e.line()>0;}
                check(rejected,"input rejects non-function, multiple or undeclared handlers: "+handler);
            }
        }
        for(String tag:List.of("text","button","column")) {
            boolean rejected=false;
            try {SfcCompiler.compilePage("input.vue",source("<"+tag+" @input=\"changed()\"/>",INITIAL+CHANGE));} catch(SfcError e){rejected=e.getMessage().contains("@input");}
            check(rejected,"input attribute restricted to text editors: "+tag);
        }
    }

    private static void edits() throws Exception {
        for(boolean multiline:List.of(false,true)) {
            try(Fixture f=new Fixture(multiline,INITIAL+CHANGE)) {
                check(f.state.getInt("calls")==0,"creation does not fire input");f.focus();
                f.host.charTyped('a',0);f.sync();check(f.text().equals("a") && f.state.getString("echo").equals("a") && f.state.getInt("calls")==1,"typing dispatches once with newest bound value");
                f.host.charTyped(0x1f600,0);f.sync();check(f.text().equals("a😀") && f.state.getInt("calls")==2,"supplementary character is one accepted edit");
                if(multiline)f.host.keyPressed(259,0,0);else f.box().deleteChars(-1);
                f.sync();check(f.text().equals("a") && f.state.getInt("calls")==3,"backspace fires once and removes a whole emoji");
                if(multiline)f.host.keyPressed(263,0,0);else f.box().setCursorPosition(0);
                f.sync();check(f.state.getInt("calls")==3,"caret movement does not fire input");
                if(multiline)f.host.keyPressed(65,0,2);else {f.box().setCursorPosition(0);f.box().setHighlightPos(f.text().length());}
                f.paste("中\n文",multiline);check(f.text().equals(multiline?"中\n文":"中文") && f.state.getString("echo").equals(f.text()) && f.state.getInt("calls")==4,"paste/selection replacement fires once after normalization");
                f.state.set("draft","external");f.sync();check(f.state.getInt("calls")==4,"script assignment does not fire input");
                f.state.set("limit",3);f.sync();check(f.text().equals("ext") && f.state.getInt("calls")==4,"max-length reconciliation does not fire input");
                if(multiline)f.host.keyPressed(269,0,2);else {f.box().setCursorPosition(f.text().length());f.box().setHighlightPos(f.text().length());}
                f.host.charTyped('x',0);f.sync();check(f.text().equals("ext") && f.state.getInt("calls")==4,"rejected length-limit edit does not fire input");
                f.state.set("enabled",false);f.sync();check(!f.inputs.focused() && !f.host.charTyped('z',0) && f.state.getInt("calls")==4,"disabled input drops focus and cannot dispatch");
                f.state.set("enabled",true);f.state.set("limit",64);f.sync();f.focus();f.state.set("shown",false);f.sync();
                check(!f.inputs.focused() && !f.host.charTyped('z',0) && f.state.getInt("calls")==4,"hidden input cannot retain event delivery");
            }
            try(Fixture f=new Fixture(multiline,INITIAL+"function changed(){state.calls++;state.draft=state.draft.toUpperCase();}")) {
                f.focus();f.host.charTyped('a',0);f.sync();check(f.text().equals("A") && f.state.getInt("calls")==1,"callback can normalize its binding without recursion");
                for(int i=0;i<3;i++){f.sync();f.host.tick();}check(f.state.getInt("calls")==1,"sync/render preparation/tick do not dispatch events");
            }
            for(String failure:List.of("throw 'stop';","state.calls='bad';","while(true){}")) {
                try(Fixture f=new Fixture(multiline,INITIAL+"function changed(){state.echo='wrong';state.calls++;"+failure+"}")) {
                    f.focus();f.host.charTyped('e',0);f.sync();check(f.text().equals("e") && f.state.getString("echo").isEmpty() && f.state.getInt("calls")==0,"callback script/type/budget failure preserves accepted input and rolls back derived state");
                    check(f.inputs.focused() && !((String)field(f.host,"toast")).isEmpty(),"failed callback retains editing and shows existing failure feedback");
                }
            }
        }
        try(Fixture f=new Fixture(editor(false,""),INITIAL)) {f.focus();f.host.charTyped('a',0);f.sync();check(f.text().equals("a") && f.state.getInt("calls")==0,"legacy input without handler still works");}
        try(Fixture f=new Fixture(editor(false,"@input=\"changed(draft)\""),INITIAL+"function changed(value){state.calls++;state.echo=value;}")) {
            f.focus();f.host.charTyped('v',0);f.sync();check(f.state.getString("echo").equals("v"),"function arguments use post-edit state");
        }
        for(boolean multiline:List.of(false,true)) {
            String padding=String.join(",",java.util.Collections.nCopies(4,"'"+"x".repeat(4096)+"'"));
            String full=INITIAL.replace("limit:64}","limit:64,padding:["+padding+"]}");
            try(Fixture f=new Fixture(multiline,full+CHANGE)) {
                f.focus();f.host.charTyped('a',0);f.sync();
                check(f.text().isEmpty() && f.state.getInt("calls")==0,"page text budget rejects edit before emitting an input event");
                if(!multiline)check(f.box().getValue().isEmpty(),"rejected single-line edit restores native displayed value");
            }
        }
    }

    private static void transactions(Path root) throws Exception {
        int index=0;
        for(String failure:List.of("","throw 'stop';","state.calls='bad';","ctx.store.setString('big','x'.repeat(4097));")) {
            String app="test:input_"+index++;Supplier<LocalStore> storage=LocalStore.clientProvider(root,ACCOUNT,app,()->ACCOUNT);
            storage.get().setString("keep","original");check(storage.get().save(),"seed storage");Path file=LocalStore.clientPath(root,ACCOUNT,app);byte[] before=Files.readAllBytes(file);
            var page=SfcCompiler.compilePage("input.vue",source(editor(false,"@input=\"changed()\""),INITIAL+"function changed(){state.calls++;state.echo=state.draft;ctx.store.setString('keep',state.draft);ctx.http.request({url:'https://example.invalid/'},'received');"+failure+"}\nfunction received(s,b,e){}"));
            UiState state=UiState.page(page.template().initialState());state.set("draft","accepted");var tree=new TemplateInstance(page.template(),"input.vue").instantiate(state);AtomicInteger sent=new AtomicInteger();
            var outcome=tree.input(find(tree.root(),"edit"),state,storage,new HttpRequests(4,batch->sent.addAndGet(batch.size())));
            check(outcome.applied()==failure.isEmpty(),"input uses shared transaction outcome");
            check(state.getString("draft").equals("accepted"),"input always survives callback transaction outcome");
            if(failure.isEmpty()) check(state.getInt("calls")==1 && storage.get().getString("keep","").equals("accepted") && sent.get()==1,"success commits state, store and requests together");
            else {
                check(state.getInt("calls")==0 && state.getString("echo").isEmpty() && sent.get()==0 && Arrays.equals(before,Files.readAllBytes(file)),"failed callback rolls back every staged effect");
                check(outcome.warnings().stream().anyMatch(w->w.contains("@input")),"failure identifies input event");
            }
        }
        AtomicInteger io=new AtomicInteger();var page=SfcCompiler.compilePage("old.vue",source(editor(false,""),INITIAL));UiState state=UiState.page(page.template().initialState());var tree=new TemplateInstance(page.template(),"old.vue").instantiate(state);
        check(tree.input(find(tree.root(),"edit"),state,()->{io.incrementAndGet();throw new AssertionError();},null)==null && io.get()==0,"missing handler does not load storage");
        LocalStore conflicted=LocalStore.clientProvider(root,ACCOUNT,"test:input_io",()->ACCOUNT).get();conflicted.setString("keep","original");
        Path conflict=LocalStore.clientPath(root,ACCOUNT,"test:input_io");Files.createDirectories(conflict);Files.writeString(conflict.resolve("keep.txt"),"untouched");
        var broken=SfcCompiler.compilePage("io.vue",source(editor(false,"@input=\"changed()\""),INITIAL+"function changed(){state.calls++;ctx.store.setString('keep','changed');}"));
        state=UiState.page(broken.template().initialState());state.set("draft","accepted");tree=new TemplateInstance(broken.template(),"io.vue").instantiate(state);
        check(!tree.input(find(tree.root(),"edit"),state,()->conflicted,null).applied() && state.getString("draft").equals("accepted") && state.getInt("calls")==0,"disk commit failure preserves accepted text and rolls back callback state");
        check(conflicted.getString("keep","").equals("original") && Files.readString(conflict.resolve("keep.txt")).equals("untouched"),"failed disk commit preserves provider and existing target");
        var loop=SfcCompiler.compilePage("loop.vue",source("<column><text-input v-for=\"item in items\" :key=\"item.id\" bind=\"draft\" @input=\"changed(item.id,draft)\"/></column>","state={draft:'',result:'',items:[{id:1},{id:2}]}\nfunction changed(id,value){state.result=String(id)+':'+value;}"));
        state=UiState.page(loop.template().initialState());state.set("draft","fresh");tree=new TemplateInstance(loop.template(),"loop.vue").instantiate(state);
        check(tree.input(tree.root().children().get(1),state,null,null).applied() && state.getString("result").equals("2:fresh"),"loop event retains row arguments and reads latest input");
    }

    private static void sample(Path root) throws Exception {
        Path folder=Path.of("../../docs/samples/input-events");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes);var paths=Files.walk(folder)) {
            for(Path path:paths.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals("README.md")).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(folder.relativize(path).toString().replace('\\','/')));zip.write(Files.readAllBytes(path));zip.closeEntry();
            }
        }
        ScriptApp app=readApp("input-events.zip",bytes.toByteArray());var page=app.entry();Supplier<LocalStore> storage=LocalStore.clientProvider(root,ACCOUNT,app.manifest().id(),()->ACCOUNT);UiState state=new PageStates().open("",page.template(),"app.vue",storage).state();
        state.set("query","2");var tree=new TemplateInstance(page.template(),"app.vue").instantiate(state);
        check(tree.input(find(tree.root(),"query"),state,storage,null).applied(),"real sample input handler applies");
        tree=new TemplateInstance(page.template(),"app.vue").instantiate(state);
        check(tree.root().children().stream().noneMatch(n->"filter".equals(n.id())),"sample needs no filter button");
        @SuppressWarnings("unchecked") List<Map<String,Object>> notes=(List<Map<String,Object>>)state.get("notes");
        check(!Boolean.TRUE.equals(notes.get(0).get("visible")) && Boolean.TRUE.equals(notes.get(1).get("visible")) && !Boolean.TRUE.equals(notes.get(2).get("visible")),"sample input 2 immediately filters notes");
        for(int[] size:List.of(new int[]{120,176},new int[]{226,158})) {
            var layout=LayoutEngine.layout(tree.root(),page.stylesheet(),state,size[0],size[1],new FontMeasure(FONT),ImageSizes.NONE);LayoutNode query=find(layout,"query");
            check(query!=null && query.w>=32 && HitTest.pick(layout,0,0,size[0],size[1],query.x+4,query.y+4,new FontMeasure(FONT))==query,"sample search editor visible and reachable on phone/tablet");
        }
        var detail=app.page("detail");UiState note=new PageStates().open("detail",detail.template(),"detail.vue",storage,null,new PageParameters(Map.of("id",2,"title","便签 2"))).state();
        note.set("note","一😀\n二");var detailTree=new TemplateInstance(detail.template(),"detail.vue").instantiate(note);
        check(detailTree.input(find(detailTree.root(),"note"),note,storage,null).applied() && note.getInt("length")==5 && note.getString("preview").equals("一😀 二"),"sample multiline input updates length and preview immediately");
        check(storage.get().keys().isEmpty(),"preview callback does not auto-save notes");
        for(int[] size:List.of(new int[]{120,176},new int[]{226,158})) {
            var layout=LayoutEngine.layout(new TemplateInstance(detail.template(),"detail.vue").instantiate(note).root(),detail.stylesheet(),note,size[0],size[1],new FontMeasure(FONT),ImageSizes.NONE);
            LayoutNode area=find(layout,"note"),save=find(layout,"save");
            check(area!=null && area.w>=32 && area.h>=24 && HitTest.pick(layout,0,0,size[0],size[1],area.x+4,area.y+4,new FontMeasure(FONT))==area,"sample multiline area remains editable on phone/tablet");
            check(save!=null && HitTest.pick(layout,0,0,size[0],size[1],save.x+save.w/2.0,save.y+save.h/2.0,new FontMeasure(FONT))==save,"sample save button remains reachable on phone/tablet");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("mcphone-input-events-").toAbsolutePath().normalize();
        Path classes=Path.of(ClientInputEventTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());Path language=classes.resolve("assets/minecraft/lang/en_us.json");boolean created=!Files.exists(language);
        if(created){Files.createDirectories(language.getParent());Files.writeString(language,"{\"mcphone.script.client_function.failed\":\"Page action failed.\"}");}
        try {compiler();edits();transactions(root);sample(root);}
        finally {if(created)Files.deleteIfExists(language);try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("ClientInputEventTest: "+checks+" checks passed");
    }
}
