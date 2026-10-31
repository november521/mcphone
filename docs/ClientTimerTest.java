package com.november.mcphone.core.script.client;

import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.http.HttpSession;
import com.november.mcphone.core.script.client.render.HitTest;
import com.november.mcphone.core.script.layout.*;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.*;
import com.november.mcphone.core.script.timer.DelayedCallbacks;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Monotonic fake time, real JS/transactions/page dispatch. No sleeping or external requests. */
public final class ClientTimerTest {
    private static int checks;
    private static final UUID ACCOUNT = new UUID(0, 1);
    private static final String INITIAL = "state={job:0,fires:0,value:'',draft:'',saved:''}\n";
    private static final String START = "state.job=ctx.timer.after(100,'done');";
    private static final String DONE = "function done(){state.fires++;state.value=state.draft;}\n";
    private static final TextMeasure FONT = new TextMeasure() {
        public int width(String s){return s.codePointCount(0,s.length())*6;}
        public int lineHeight(){return 9;}
        public String truncate(String s,int width){return s.substring(0,Math.min(s.length(),Math.max(0,width/6)));}
    };
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void rejects(Runnable action,String why){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,why);}
    private static String source(String body,String script){return "<template>\n"+body+"\n</template>\n<script>\n"+script+"\n</script>\n";}
    private static CompiledTemplate page(String script){return SfcCompiler.compilePage("timer.vue",source("<column><button id=\"start\" @click=\"start()\">start</button><text-input id=\"query\" bind=\"draft\" @input=\"start()\"/></column>",script)).template();}
    private static Node find(Node root,String id){if(id.equals(root.id()))return root;for(Node child:root.children()){Node found=find(child,id);if(found!=null)return found;}return null;}
    private static LayoutNode find(LayoutNode root,String id){if(id.equals(root.node.id()))return root;for(LayoutNode child:root.children){LayoutNode found=find(child,id);if(found!=null)return found;}return null;}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object invoke(Object o,String name,Class<?>[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static Supplier<LocalStore> storage(Path root,String app){return LocalStore.clientProvider(root,ACCOUNT,app,()->ACCOUNT);}

    private static void scheduler() {
        AtomicLong clock=new AtomicLong();List<String> fired=new ArrayList<>();DelayedCallbacks session=new DelayedCallbacks(()->true,clock::get);
        var batch=session.batch(fired::add);int first=batch.after(50,"first");clock.set(100_000_000L);session.poll();check(fired.isEmpty(),"uncommitted scheduling cannot fire");batch.commit();
        clock.set(149_000_000L);session.poll();check(fired.isEmpty(),"deadline starts at successful commit");clock.set(150_000_000L);session.poll();session.poll();check(fired.equals(List.of("first")),"due task fires exactly once");
        try(var cancel=session.batch(fired::add)){cancel.cancel(first);cancel.cancel(0);cancel.cancel(Integer.MAX_VALUE);cancel.commit();}
        check(fired.size()==1,"completed/unknown/zero cancellation is harmless");
        try(var abandoned=session.batch(fired::add)){abandoned.after(50,"abandoned");}clock.addAndGet(100_000_000L);session.poll();check(fired.size()==1,"discarded batch publishes no tasks");
        int kept;try(var add=session.batch(fired::add)){kept=add.after(50,"kept");add.commit();}
        try(var cancel=session.batch(fired::add)){cancel.cancel(kept);}clock.addAndGet(50_000_000L);session.poll();check(fired.get(1).equals("kept"),"discarded cancellation leaves original task active");
        try(var cancelled=session.batch(fired::add)){int id=cancelled.after(50,"never");cancelled.cancel(id);cancelled.commit();}clock.addAndGet(50_000_000L);session.poll();check(fired.size()==2,"create then cancel in same transaction sends nothing");
        List<Integer> ids=new ArrayList<>();try(var full=session.batch(fired::add)){for(int i=0;i<8;i++)ids.add(full.after(50,"n"+i));rejects(()->full.after(50,"ninth"),"page task count bounded");full.commit();}
        try(var replace=session.batch(fired::add)){replace.cancel(ids.get(0));replace.after(50,"replacement");replace.commit();}clock.addAndGet(50_000_000L);session.poll();check(fired.size()==3,"only one due callback runs per poll");for(int i=0;i<7;i++)session.poll();check(fired.size()==10 && fired.get(9).equals("replacement"),"cancel plus schedule frees capacity and preserves due order");
        try(var invalid=session.batch(fired::add)){for(int delay:new int[]{-1,0,49,60001,Integer.MAX_VALUE})rejects(()->invalid.after(delay,"bad"),"delay range enforced");for(String name:new String[]{null,"","x.y","x".repeat(65)})rejects(()->invalid.after(50,name),"plain bounded callback name required");rejects(()->invalid.cancel(-1),"negative cancellation rejected");}
        AtomicBoolean active=new AtomicBoolean(true);DelayedCallbacks guarded=new DelayedCallbacks(active::get,clock::get);try(var add=guarded.batch(fired::add)){add.after(50,"oldAccount");add.commit();}active.set(false);clock.addAndGet(50_000_000L);guarded.poll();active.set(true);guarded.poll();check(fired.size()==10,"account invalidation clears tasks permanently");rejects(()->guarded.batch(fired::add).after(50,"revive"),"closed session cannot be revived");
        DelayedCallbacks other=new DelayedCallbacks(()->true,clock::get);int own;try(var add=session.batch(fired::add)){own=add.after(50,"owned");add.commit();}try(var cancel=other.batch(fired::add)){cancel.cancel(own);cancel.commit();}clock.addAndGet(50_000_000L);session.poll();check(fired.get(10).equals("owned"),"other page cannot cancel task ID");
        clock.set(Long.MAX_VALUE-10_000_000L);DelayedCallbacks wrapped=new DelayedCallbacks(()->true,clock::get);try(var add=wrapped.batch(fired::add)){add.after(50,"wrap");add.commit();}clock.addAndGet(49_000_000L);wrapped.poll();check(fired.size()==11,"nanoTime wrap does not fire early");clock.addAndGet(1_000_000L);wrapped.poll();check(fired.get(11).equals("wrap"),"nanoTime wrap retains relative deadline");
        AtomicInteger runs=new AtomicInteger();DelayedCallbacks recursive=new DelayedCallbacks(()->true,clock::get);java.util.function.Consumer<String> receiver=name->{runs.incrementAndGet();try(var add=recursive.batch(ignored->runs.incrementAndGet())){add.after(50,"later");add.commit();}clock.addAndGet(50_000_000L);recursive.poll();};try(var add=recursive.batch(receiver)){add.after(50,"first");add.commit();}clock.addAndGet(50_000_000L);recursive.poll();check(runs.get()==1,"callback-created work and recursive poll do not recurse");recursive.poll();check(runs.get()==2,"callback-created task waits for another tick");session.close();other.close();wrapped.close();recursive.close();
    }

    private static DelayedCallbacks.Batch batch(DelayedCallbacks timers,CompiledTemplate template,UiState state,Supplier<LocalStore> storage,AtomicInteger sent,List<String> warnings) {
        return timers.batch(callback->{var result=PageStates.delayed(state,template.script(),callback,"timer.vue",storage,
                target->new HttpRequests(4,requests->sent.addAndGet(requests.size())),target->batch(timers,template,target,storage,sent,warnings));warnings.addAll(result.warnings());});
    }
    private static Statements.Outcome start(CompiledTemplate template,UiState state,Supplier<LocalStore> storage,DelayedCallbacks timers,AtomicInteger sent,List<String> warnings) {
        var tree=new TemplateInstance(template,"timer.vue").instantiate(state);
        return tree.click(find(tree.root(),"start"),state,0,storage,new HttpRequests(4,requests->sent.addAndGet(requests.size())),batch(timers,template,state,storage,sent,warnings));
    }

    private static void bridgeAndTransactions(Path root)throws Exception {
        int index=0;
        for(String statement:List.of("ctx.timer.after(49,'done')","ctx.timer.after(60001,'done')","ctx.timer.after(1.5,'done')","ctx.timer.after('100','done')","ctx.timer.after(NaN,'done')","ctx.timer.after(Infinity,'done')","ctx.timer.after(50,done)","ctx.timer.after(50,'missing')","ctx.timer.after(50,'withArg')","ctx.timer.after(50,'done','extra')","ctx.timer.cancel(-1)","ctx.timer.cancel(1.5)","ctx.timer.cancel('1')","ctx.timer.cancel(0,1)")) {
            var template=page(INITIAL+"function start(){state.value='wrong';"+statement+";}\n"+DONE+"function withArg(value){}\n");UiState state=UiState.page(template.initialState());AtomicLong clock=new AtomicLong();AtomicInteger sent=new AtomicInteger();List<String> warnings=new ArrayList<>();try(var timers=new DelayedCallbacks(()->true,clock::get)){check(!start(template,state,null,timers,sent,warnings).applied() && state.getString("value").isEmpty(),"invalid timer bridge arguments roll back state: "+statement);clock.set(100_000_000_000L);timers.poll();check(state.getInt("fires")==0,"invalid request leaves no task");}
        }
        for(String failure:List.of("","throw 'stop';","state.fires='bad';","ctx.store.setString('big','x'.repeat(4097));","while(true){}")) {
            String app="test:timer_"+index++;Supplier<LocalStore> local=storage(root,app);local.get().setString("keep","original");check(local.get().save(),"seed storage");Path file=LocalStore.clientPath(root,ACCOUNT,app);byte[] before=Files.readAllBytes(file);
            var template=page(INITIAL+"function start(){ctx.store.setString('keep','changed');ctx.http.request({url:'https://example.invalid/'},'received');"+START+failure+"}\n"+DONE+"function received(s,b,e){}\n");UiState state=UiState.page(template.initialState());AtomicLong clock=new AtomicLong();AtomicInteger sent=new AtomicInteger();List<String> warnings=new ArrayList<>();try(var timers=new DelayedCallbacks(()->true,clock::get)) {
                var result=start(template,state,local,timers,sent,warnings);check(result.applied()==failure.isEmpty(),"timer scheduling shares transaction success");state.set("draft","latest");clock.set(100_000_000L);timers.poll();
                if(failure.isEmpty())check(state.getInt("fires")==1 && state.getString("value").equals("latest") && sent.get()==1 && local.get().getString("keep","").equals("changed"),"successful scheduling commits effects and callback reads latest state");
                else check(state.getInt("job")==0 && state.getInt("fires")==0 && sent.get()==0 && Arrays.equals(before,Files.readAllBytes(file)),"failed scheduling rolls back timer, state, file and HTTP");
            }
        }
        for(String failure:List.of("","throw 'stop';","state.fires='bad';","while(true){}")) {
            var template=page(INITIAL+"function start(){"+START+"}\nfunction done(){state.fires++;ctx.store.setString('fired','yes');ctx.http.request({url:'https://example.invalid/'},'received');ctx.timer.after(50,'again');"+failure+"}\nfunction again(){state.value='again';}\nfunction received(s,b,e){}\n");UiState state=UiState.page(template.initialState());AtomicLong clock=new AtomicLong();AtomicInteger sent=new AtomicInteger();List<String> warnings=new ArrayList<>();Supplier<LocalStore> local=storage(root,"test:timer_fire_"+index++);try(var timers=new DelayedCallbacks(()->true,clock::get)) {
                check(start(template,state,local,timers,sent,warnings).applied(),"schedule callback transaction");clock.set(100_000_000L);timers.poll();clock.addAndGet(50_000_000L);timers.poll();
                check((state.getInt("fires")==1)==failure.isEmpty() && (sent.get()==1)==failure.isEmpty(),"fired callback commits or rolls back state and requests");
                check(local.get().keys().contains("fired")==failure.isEmpty() && state.getString("value").equals(failure.isEmpty()?"again":""),"fired callback includes store and newly scheduled task in transaction");
                check(warnings.isEmpty()==failure.isEmpty(),"failing callback reports once without retry");
            }
        }
        AtomicLong clock=new AtomicLong();AtomicInteger sent=new AtomicInteger();List<String> warnings=new ArrayList<>();LocalStore conflicted=storage(root,"test:timer_io").get();Path conflict=LocalStore.clientPath(root,ACCOUNT,"test:timer_io");Files.createDirectories(conflict);Files.writeString(conflict.resolve("keep"),"original");
        var io=page(INITIAL+"function start(){ctx.store.setString('keep','new');"+START+"}\n"+DONE);UiState state=UiState.page(io.initialState());try(var timers=new DelayedCallbacks(()->true,clock::get)){check(!start(io,state,()->conflicted,timers,sent,warnings).applied(),"disk failure rejects scheduling transaction");clock.set(100_000_000L);timers.poll();check(state.getInt("fires")==0 && state.getInt("job")==0 && Files.readString(conflict.resolve("keep")).equals("original"),"disk failure publishes no timer and preserves target");}
        var cancel=page(INITIAL+"function start(){if(state.job===0){"+START+"}else{ctx.timer.cancel(state.job);throw 'stop';}}\n"+DONE);state=UiState.page(cancel.initialState());try(var timers=new DelayedCallbacks(()->true,clock::get)){check(start(cancel,state,null,timers,sent,warnings).applied(),"seed cancellable task");check(!start(cancel,state,null,timers,sent,warnings).applied(),"failed cancellation transaction");clock.addAndGet(100_000_000L);timers.poll();check(state.getInt("fires")==1,"failed cancellation leaves existing task alive");}
    }

    private static ScriptApp app(String script) {
        String manifest="<manifest>\n{\"format\":1,\"id\":\"test:timer_host\",\"version\":\"1.0.0\",\"name\":\"timer\",\"author\":\"test\"}\n</manifest>\n";
        String body="<column><text-input id=\"query\" bind=\"draft\" @input=\"start()\"/><button id=\"start\" @click=\"start()\">start</button><button id=\"away\" @click=\"nav('away')\">away</button></column>";
        ScriptApp base=ScriptAppFolder.read("timer.vue",(manifest+source(body,script)).getBytes(StandardCharsets.UTF_8));
        var away=SfcCompiler.compilePage("away.vue",source("<button id=\"back\" @click=\"back()\">back</button>","state={value:''}\n"));
        return ScriptApp.of(base.id(),base.manifest(),null,base.entry(),Map.of("away",away),null,"timer.vue");
    }
    private static UiState state(ScriptPage host)throws Exception{return (UiState)field(host,"state");}
    private static TemplateInstance.Tree tree(ScriptPage host,ScriptApp app)throws Exception{return new TemplateInstance(app.page((String)field(host,"current")).template(),"host.vue").instantiate(state(host));}
    private static void click(ScriptPage host,ScriptApp app,String id)throws Exception {
        var tree=tree(host,app);var result=tree.click(find(tree.root(),id),state(host),0,null,(HttpRequests)invoke(host,"requests",new Class<?>[]{}),(DelayedCallbacks.Batch)invoke(host,"timers",new Class<?>[]{}));check(result.applied(),"host click "+id);invoke(host,"run",new Class<?>[]{Statements.Outcome.class},result);
    }
    private static void hosts()throws Exception {
        AtomicLong clock=new AtomicLong();ScriptApp app=app(INITIAL+"function start(){ctx.timer.cancel(state.job);"+START+"}\n"+DONE);ScriptPage host=new ScriptPage(app,clock::get);invoke(host,"openPage",new Class<?>[]{String.class},"");UiState list=state(host);
        list.set("draft","first");var tree=tree(host,app);set(host,"tree",tree);var layout=LayoutEngine.layout(tree.root(),app.entry().stylesheet(),list,120,176,FONT,ImageSizes.NONE);invoke(host,"inputChanged",new Class<?>[]{LayoutNode.class},find(layout,"query"));clock.addAndGet(50_000_000L);host.tick();check(list.getInt("fires")==0,"input schedules rather than invoking immediately");
        list.set("draft","latest");tree=tree(host,app);set(host,"tree",tree);layout=LayoutEngine.layout(tree.root(),app.entry().stylesheet(),list,120,176,FONT,ImageSizes.NONE);invoke(host,"inputChanged",new Class<?>[]{LayoutNode.class},find(layout,"query"));clock.addAndGet(99_000_000L);host.tick();check(list.getInt("fires")==0,"new input cancels old debounce deadline");clock.addAndGet(1_000_000L);host.tick();check(list.getInt("fires")==1 && list.getString("value").equals("latest"),"input debounce runs once through actual page tick with latest text");
        click(host,app,"start");click(host,app,"away");clock.addAndGet(100_000_000L);host.tick();check(list.getInt("fires")==1,"leaving page cancels its pending timer");check(host.onBack() && state(host)==list,"back restores retained state");host.tick();check(list.getInt("fires")==1,"back does not resurrect old tasks");click(host,app,"start");host.onClose();clock.addAndGet(100_000_000L);host.tick();check(list.getInt("fires")==1,"closing App cancels current tasks");
        ScriptApp lifecycle=app(INITIAL+"function onInit(){"+START+"}\nfunction onResume(){"+START+"}\nfunction start(){}\n"+DONE);host=new ScriptPage(lifecycle,clock::get);invoke(host,"openPage",new Class<?>[]{String.class},"");list=state(host);check(list.getInt("job")>0,"initialization can schedule task");clock.addAndGet(100_000_000L);host.tick();check(list.getInt("fires")==1,"onInit task fires on active page");click(host,lifecycle,"away");check(host.onBack(),"resume page");clock.addAndGet(100_000_000L);host.tick();check(list.getInt("fires")==2,"onResume schedules in fresh active-page session");host.onClose();
        ScriptApp network=app(INITIAL+"function start(){ctx.http.request({url:'https://example.invalid/'},'received');}\nfunction received(s,b,e){state.value=b;state.job=ctx.timer.after(50,'done');}\n"+DONE);host=new ScriptPage(network,clock::get);invoke(host,"openPage",new Class<?>[]{String.class},"");set(host,"http",new HttpSession(()->true,request->CompletableFuture.completedFuture(new HttpRequests.Result(200,"reply",""))));list=state(host);
        click(host,network,"start");host.tick();check(list.getString("value").equals("reply") && list.getInt("job")>0 && list.getInt("fires")==0,"HTTP callback can schedule on its currently active page");clock.addAndGet(50_000_000L);host.tick();check(list.getInt("fires")==1,"HTTP-created task fires later");
        list.set("value","waiting");click(host,network,"start");click(host,network,"away");host.tick();clock.addAndGet(100_000_000L);host.tick();check(list.getString("value").equals("waiting") && list.getInt("fires")==1,"response to hidden page cannot schedule against another page's timer host");host.onClose();
        AtomicBoolean account=new AtomicBoolean(true);host=new ScriptPage(app,clock::get);set(host,"activeAccount",(java.util.function.BooleanSupplier)account::get);invoke(host,"openPage",new Class<?>[]{String.class},"");list=state(host);click(host,app,"start");account.set(false);clock.addAndGet(100_000_000L);host.tick();account.set(true);host.tick();check(list.getInt("fires")==0,"page tasks stop permanently after account guard fails");host.onClose();
    }

    private static void sample()throws Exception {
        Path folder=Path.of("../../docs/samples/delayed-search");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes);var paths=Files.walk(folder)){for(Path path:paths.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals("README.md")).sorted().toList()){zip.putNextEntry(new ZipEntry(folder.relativize(path).toString().replace('\\','/')));zip.write(Files.readAllBytes(path));zip.closeEntry();}}
        ScriptApp app=ScriptAppFolder.read("delayed-search.zip",bytes.toByteArray());AtomicLong clock=new AtomicLong();ScriptPage host=new ScriptPage(app,clock::get);invoke(host,"openPage",new Class<?>[]{String.class},"");UiState list=state(host);list.set("query","2");var tree=tree(host,app);set(host,"tree",tree);var layout=LayoutEngine.layout(tree.root(),app.entry().stylesheet(),list,120,176,FONT,ImageSizes.NONE);invoke(host,"inputChanged",new Class<?>[]{LayoutNode.class},find(layout,"query"));clock.set(299_000_000L);host.tick();check(list.getInt("searches")==0,"sample waits before applying search");clock.set(300_000_000L);host.tick();check(list.getInt("searches")==1 && list.getString("applied").equals("2"),"sample applies latest input once after quiet period");
        @SuppressWarnings("unchecked") List<Map<String,Object>> notes=(List<Map<String,Object>>)list.get("notes");check(!Boolean.TRUE.equals(notes.get(0).get("visible")) && Boolean.TRUE.equals(notes.get(1).get("visible")) && !Boolean.TRUE.equals(notes.get(2).get("visible")),"sample filter results match input");
        click(host,app,"long");click(host,app,"cancel");clock.addAndGet(3_000_000_000L);host.tick();check(list.getInt("searches")==1,"sample manual cancellation suppresses waiting search");click(host,app,"long");click(host,app,"away");clock.addAndGet(3_000_000_000L);host.tick();check(host.onBack() && list.getInt("searches")==1 && list.getString("query").equals("2"),"sample page switch cancels wait and preserves input draft");
        for(int[] size:List.of(new int[]{120,176},new int[]{226,158})) {
            tree=tree(host,app);layout=LayoutEngine.layout(tree.root(),app.entry().stylesheet(),list,size[0],size[1],FONT,ImageSizes.NONE);
            for(String id:List.of("query","cancel","away","long")){LayoutNode node=find(layout,id);check(node!=null && node.w>8 && node.h>0 && HitTest.pick(layout,0,0,size[0],size[1],node.x+node.w/2.0,node.y+node.h/2.0,FONT)==node,"sample control visible and reachable on phone/tablet: "+id);}
        }
        host.onClose();
    }

    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("mcphone-timer-").toAbsolutePath().normalize();
        try{scheduler();bridgeAndTransactions(root);hosts();sample();}
        finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("ClientTimerTest: "+checks+" checks passed");
    }
}
