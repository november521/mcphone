package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.store.*;
import com.november.mcphone.api.sdk.SdkGate;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.script.pkg.*;
import com.november.mcphone.core.script.server.StoreRepository;
import com.november.mcphone.core.script.server.StoreTransfer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** 只安装已验签的前端封套。下载数据从来不能写服务端部署表。 */
public final class ServerStoreSource implements IAppSource {
    public static final ResourceLocation ID=ResourceLocation.fromNamespaceAndPath(MCphone.MODID,"server_store");
    private static final Map<ResourceLocation,JsonObject> ADVERTISED=new HashMap<>();
    private static final Set<String> CONFIRMED=new HashSet<>();
    @Override public ResourceLocation getId() {return ID;}
    @Override public Component getDisplayName() {return Component.literal("服务器商店");}
    @Override public boolean isReady() {return ClientHandshake.connectionEpoch()!=0;}
    @Override public void listAvailable(Consumer<List<AppInfo>> callback) {
        if(!isReady()){callback.accept(List.of());return;}
        long epoch=ClientHandshake.connectionEpoch();List<AppInfo> out=new ArrayList<>();list(0,epoch,out,callback);
    }
    private void list(int offset,long epoch,List<AppInfo> out,Consumer<List<AppInfo>> callback) {
        ClientStore.rpc("store.list",ClientStore.offset(offset),data->{
            if(epoch!=ClientHandshake.connectionEpoch()){callback.accept(List.of());return;}
            JsonArray rows=data.getAsJsonArray("items");int total=data.get("total").getAsInt();
            if(total>64||total<0||rows.size()>2){callback.accept(List.copyOf(out));return;}
            for(var row:rows) {
                JsonObject o=row.getAsJsonObject();ResourceLocation id=ResourceLocation.parse(o.get("id").getAsString());ADVERTISED.put(id,o);
                if(PhoneScreenRegistry.isInstalled(id))continue;
                String fp=o.get("fingerprint").getAsString();var v=LocalScriptSource.advertisedTrust(id.toString(),fp);
                out.add(AppInfo.builder(id,Component.literal(o.get("name").getAsString()),ID).version(o.get("version").getAsString()).author(o.get("author").getAsString())
                    .description("服务器批准日期："+o.get("reviewed").getAsString()+"；作者指纹："+fp)
                    .signature(new AppInfo.Signature(SigCopy.keyFor(v.state()),fp,v.knownFingerprint(),v.state().needsPhrase()?SigCopy.requiredPhrase(fp):null,!v.state().installable,v.state().needsConfirm())).build());
            }
            int next=offset+rows.size();if(rows.isEmpty()||next>=total)callback.accept(List.copyOf(out));
            else ClientStore.later(()->list(next,epoch,out,callback));
        },error->callback.accept(List.copyOf(out)));
    }
    @Override public boolean confirmSignature(AppInfo info,String phrase) {
        JsonObject o=ADVERTISED.get(info.id());if(o==null)return false;String fp=o.get("fingerprint").getAsString();
        if(!SigCopy.canProceed(LocalScriptSource.advertisedTrust(info.id().toString(),fp),phrase))return false;
        CONFIRMED.add(o.get("digest").getAsString()+"|"+fp);return true;
    }
    @Override public void install(AppInfo info,Consumer<IPhoneApp> success,Consumer<Component> error) {
        JsonObject o=ADVERTISED.get(info.id());if(o==null){error.accept(Component.literal("请重新打开商店"));return;}
        String digest=o.get("digest").getAsString(),fp=o.get("fingerprint").getAsString();
        ClientStore.download(digest,raw->{
            try {
                AppPackage pkg=FrontendEnvelope.read(raw);ServerFrontendUpdate.require(pkg,expected(o));if(!pkg.manifest().id().equals(info.id().toString())||!SigManifest.parse(pkg.signature()).fingerprint().equals(fp))throw new IllegalArgumentException("商店信息与作者包不一致");
                ScriptApp app=ScriptAppFolder.fromPackage("server-store:"+digest,pkg);var v=LocalScriptSource.trustOf(app);
                ClientPackageVersions.observe(app);
                Component revoked=ClientRevocations.blocked(app);if(revoked!=null)throw new IllegalArgumentException(revoked.getString());
                if(!v.state().installable||v.state()!=TrustState.State.TRUSTED&&!CONFIRMED.contains(digest+"|"+v.fingerprint()))throw new IllegalArgumentException("请先确认作者签名");
                if(!SdkGate.unsatisfied(app.manifest().sdk()).isEmpty())throw new IllegalArgumentException("请先更新 MCPhone");
                ClientRevocations.checkInstall(app,refused->{if(refused!=null){error.accept(refused);return;}try{success.accept(installBundle(app,raw));}catch(IOException|RuntimeException bad){error.accept(Component.literal("安装失败："+bad.getMessage()));}});
            } catch(IOException|RuntimeException bad) {error.accept(Component.literal("安装失败："+bad.getMessage()));}
        },message->error.accept(Component.literal(message)));
    }
    static ServerFrontendUpdate.Expected expected(JsonObject metadata){return new ServerFrontendUpdate.Expected(metadata.get("id").getAsString(),metadata.get("digest").getAsString(),Base64.getDecoder().decode(metadata.get("pubkey").getAsString()),metadata.get("versionCode").getAsBigDecimal().longValueExact(),metadata.get("frontendDigest").getAsString());}
    static boolean cached(ScriptApp app){return app.file().startsWith("server-store:")||app.file().endsWith(".front");}
    static ScriptAppAdapter installBundle(ScriptApp app,byte[] raw)throws IOException {
        return installBundle(app,raw,false);
    }
    static ScriptAppAdapter installManualBundle(ScriptApp app,byte[] raw)throws IOException {return installBundle(app,raw,true);}
    private static ScriptAppAdapter installBundle(ScriptApp app,byte[] raw,boolean manual)throws IOException {
        Component blocked=manual?null:ClientRevocations.blocked(app);if(blocked!=null)throw new IllegalArgumentException(blocked.getString());
        if(manual){if(!ClientPackageVersions.manualPossible(app)||ClientRevocations.rejected(app)!=null)throw new IllegalArgumentException("吊销、冲突或版本历史拒绝人工替换");}
        if(!SdkGate.unsatisfied(app.manifest().sdk()).isEmpty())throw new IllegalArgumentException("请先更新 MCPhone");
        IPhoneApp old=PhoneScreenRegistry.getApp(app.id());if(old!=null&&!(old instanceof ScriptAppAdapter))throw new IllegalArgumentException("这个标识已被内建应用使用");
        ScriptAppAdapter adapter=new ScriptAppAdapter(app);StoreRepository.atomic(cache(app.id()),raw);if(manual)ClientPackageVersions.approveManual(app);else ClientPackageVersions.accept(app);LocalScriptSource.rememberAuthor(app);
        if(old instanceof ScriptAppAdapter previous){
            if(!PhoneScreenRegistry.replace(previous,adapter))throw new IllegalStateException("应用替换失败");
            if(Minecraft.getInstance().screen instanceof com.november.mcphone.core.client.PhoneScreen phone)phone.closeScriptApp(app.id().toString());previous.onUninstall();
        }
        if(old==null){if(!PhoneScreenRegistry.install(adapter))throw new IllegalStateException("应用安装失败");}
        else if(!PhoneScreenRegistry.isInstalled(app.id())&&!PhoneScreenRegistry.install(app.id()))throw new IllegalStateException("应用安装失败");
        return adapter;
    }
    private static Path root() {return Minecraft.getInstance().gameDirectory.toPath().resolve("mcphone/server-apps");}
    private static Path cache(ResourceLocation id) {return root().resolve(StoreTransfer.sha(id.toString().getBytes(StandardCharsets.UTF_8))+".front");}
    public static void restore(Set<ResourceLocation> installed) {
        Path dir=root();if(!Files.isDirectory(dir)||Files.isSymbolicLink(dir))return;
        try(var files=Files.newDirectoryStream(dir,"*.front")) {int n=0;for(Path f:files){if(++n>64)break;
            try {if(Files.isSymbolicLink(f)||Files.size(f)>FrontendEnvelope.MAX_BYTES)continue;ScriptApp app=ScriptAppFolder.fromPackage(f.toString(),FrontendEnvelope.read(Files.readAllBytes(f)));
                ClientPackageVersions.observe(app);
                if(!installed.contains(app.id())||!LocalScriptSource.trustOf(app).state().installable||LocalScriptSource.trustOf(app).state().needsPhrase()||ClientRevocations.blocked(app)!=null||!SdkGate.unsatisfied(app.manifest().sdk()).isEmpty())continue;
                ClientPackageVersions.accept(app);
                PhoneScreenRegistry.register(new ScriptAppAdapter(app));
            } catch(IOException|RuntimeException bad) {MCphone.LOGGER.warn("[MCphone] 服务器前端缓存不能恢复：{}",f.getFileName());}
        }} catch(IOException bad) {MCphone.LOGGER.warn("[MCphone] 服务器前端缓存目录不可用");}
    }
}
