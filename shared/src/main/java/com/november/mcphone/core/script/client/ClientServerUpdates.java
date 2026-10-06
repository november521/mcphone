package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.core.script.pkg.*;
import com.november.mcphone.core.script.server.RevocationPolicy;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** 内源只在商店、详情页和 forced 打开动作检查；不发外网，不在 tick 轮询。 */
public final class ClientServerUpdates {
    private record Policy(String digest,String mode,long revision){}
    private static final Map<String,Policy> policies=new HashMap<>();
    private static final Map<String,String> status=new HashMap<>();
    private static final Set<String> checking=new HashSet<>();
    private ClientServerUpdates(){}
    public static void clear(){policies.clear();status.clear();checking.clear();}
    public static String status(String app){return status.getOrDefault(app,"");}
    public static boolean required(ScriptApp app){Policy p=policies.get(app.id().toString());return ClientHandshake.complete()&&p!=null&&p.mode.equals("forced")&&(app.pkg()==null||!p.digest.equals(app.pkg().digest()));}
    public static boolean forced(String app){Policy p=policies.get(app);return ClientHandshake.complete()&&p!=null&&p.mode.equals("forced");}
    public static void push(ScriptPush push){if(!push.isHost()||!push.topic().equals("mcphone:store.update")||!ClientHandshake.complete())return;try{
        JsonObject data=JsonParser.parseString(new String(push.data(),StandardCharsets.UTF_8)).getAsJsonObject();if(!String.valueOf(ClientHandshake.serverId()).equals(data.get("serverId").getAsString())||ClientHandshake.connectionEpoch()!=data.get("epoch").getAsBigDecimal().longValueExact())return;
        ServerFrontendUpdate.Expected expected=ServerStoreSource.expected(data);String mode=data.get("frontendUpdate").getAsString();if(!Set.of("optional","forced","off").contains(mode))return;
        Policy old=policies.get(expected.app());if(old!=null&&old.revision>=push.revision()||policies.size()>=256&&old==null)return;
        policies.put(expected.app(),new Policy(expected.digest(),mode,push.revision()));
    }catch(RuntimeException ignored){ }}
    public static void check(ScriptApp app,boolean install){check(app,install,ignored->{});}
    public static void check(ScriptApp app,boolean install,Consumer<Component> result){
        if(!ClientHandshake.complete()){result.accept(Component.translatable("mcphone.server_update.unavailable"));return;}
        String id=app.id().toString();if(!checking.add(id)){result.accept(Component.translatable("mcphone.server_update.checking"));return;}
        long epoch=ClientHandshake.connectionEpoch();UUID server=ClientHandshake.serverId();status.put(id,"mcphone.server_update.checking");
        ClientStore.rpc("store.current",ClientStore.args("app",id),data->{
            try{
                if(!data.get("available").getAsBoolean()){finish(id,"mcphone.server_update.unavailable",result);return;}
                JsonObject metadata=data.getAsJsonObject("package");ServerFrontendUpdate.Expected expected=ServerStoreSource.expected(metadata);String mode=metadata.get("frontendUpdate").getAsString();
                if(!expected.app().equals(id)||!Set.of("optional","forced","off").contains(mode))throw new IllegalArgumentException("服务器更新元信息无效");
                ClientHandshake.Entry deployment=ClientHandshake.deployment(id);if(metadata.get("backend").getAsBoolean()&&deployment!=null&&!deployment.deployRev().equals(expected.digest()))throw new IllegalArgumentException("服务器部署已改变，请重新打开更新页");
                Policy prior=policies.get(id);policies.put(id,new Policy(expected.digest(),mode,prior==null?Long.MIN_VALUE:prior.revision));
                if(app.pkg()!=null&&expected.digest().equals(app.pkg().digest())){finish(id,"mcphone.server_update.current",result);return;}
                if(!install){finish(id,mode.equals("off")?"mcphone.server_update.off":"mcphone.server_update.available",result);return;}
                if(!Arrays.equals(app.authorKey(),expected.publicKey())){finish(id,"mcphone.server_update.key_changed",result);return;}
                ClientStore.download(expected.digest(),raw->{
                    try{
                        if(epoch!=ClientHandshake.connectionEpoch()||!Objects.equals(server,ClientHandshake.serverId())||PhoneScreenRegistry.getApp(app.id()) instanceof ScriptAppAdapter current&&current.script()!=app)throw new IllegalArgumentException("应用或连接已改变");
                        AppPackage pkg=FrontendEnvelope.read(raw);ServerFrontendUpdate.require(pkg,expected);
                        ScriptApp incoming=ScriptAppFolder.fromPackage("server-store:"+pkg.digest(),pkg);ClientPackageVersions.observe(incoming);
                        Component blocked=ClientRevocations.blocked(incoming);if(blocked!=null)throw new IllegalArgumentException(blocked.getString());
                        if(!ServerFrontendUpdate.sameAuthor(app.pkg(),pkg)||incoming.versionCode()<=Math.max(app.versionCode(),ClientPackageVersions.highest(app)))throw new IllegalArgumentException("服务器内源拒绝作者改变、同版本替换或版本回退");
                        Set<String> approved=new HashSet<>();JsonArray approvedRows=metadata.getAsJsonArray("approvedCapabilities");if(approvedRows.size()>32)throw new IllegalArgumentException("批准能力超额");for(JsonElement value:approvedRows)approved.add(value.getAsString());
                        if(UpdateVerifier.capabilities(pkg,approved).decision()!=UpdateVerifier.Decision.AUTOMATIC){finish(id,"mcphone.server_update.approval",result);return;}
                        if(!LocalScriptSource.trustOf(incoming).state().installable)throw new IllegalArgumentException("作者信任检查拒绝更新");
                        ClientRevocations.checkInstall(incoming,refused->{if(refused!=null){checking.remove(id);status.put(id,"mcphone.server_update.failed");result.accept(refused);return;}
                            try{if(epoch!=ClientHandshake.connectionEpoch()||!Objects.equals(server,ClientHandshake.serverId())||!(PhoneScreenRegistry.getApp(app.id()) instanceof ScriptAppAdapter current)||current.script()!=app)throw new IllegalArgumentException("应用或连接已改变");ServerStoreSource.installBundle(incoming,raw);finish(id,"mcphone.server_update.installed",result);}
                            catch(Exception bad){failure(id,bad.getMessage(),result);}
                        });
                    }catch(Exception bad){failure(id,bad.getMessage(),result);}
                },error->failure(id,error,result));
            }catch(Exception bad){failure(id,bad.getMessage(),result);}
        },error->failure(id,error,result));
    }
    private static void finish(String id,String key,Consumer<Component> result){checking.remove(id);status.put(id,key);result.accept(Component.translatable(key));}
    private static void failure(String id,String why,Consumer<Component> result){checking.remove(id);status.put(id,"mcphone.server_update.failed");result.accept(Component.translatable("mcphone.server_update.failed_detail",why));}
}
