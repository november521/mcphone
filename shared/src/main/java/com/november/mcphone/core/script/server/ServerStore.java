package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.core.script.pkg.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/** 服务器商店的固定操作。每一片下载重新检查可见性；每次审批重新检查 UUID 管理许可。 */
public final class ServerStore {
    private final MinecraftServer server;
    private final Supplier<ScriptRuntimeConfig> config;
    private final StoreRepository repository;
    private final StoreTransfer transfers=new StoreTransfer(System::currentTimeMillis);
    private final StoreFileBudget fileBytes=new StoreFileBudget(System::currentTimeMillis);
    private final ScriptRateLimiter fileRate=new ScriptRateLimiter(System::currentTimeMillis);
    private final Map<String,byte[]> envelopes=new LinkedHashMap<>();
    private final Map<String,JsonObject> descriptions=new LinkedHashMap<>();
    private final ServerAuthorUpdates authorUpdates;
    public ServerStore quotas(Supplier<QuotaConfig> quotas){transfers.quotas(quotas);repository.quotas(quotas);return this;}
    public ServerStore(MinecraftServer server,Supplier<ScriptRuntimeConfig> config) throws IOException {
        this.server=server;this.config=config;
        repository=new StoreRepository(server.getWorldPath(LevelResource.ROOT).resolve("mcphone/store/pending")).witnesses(ScriptHost.current().witnesses());
        authorUpdates=new ServerAuthorUpdates(server,config,this);
    }
    public ScriptRpcResult handle(ScriptRpc rpc,PlayerSnapshot player,JsonObject args) {
        try {
            JsonObject result=new JsonObject(); String action=rpc.actionId();
            switch(action) {
                case "store.begin" -> {
                    exact(args,"size","sha");int size=integer(args,"size");
                    if(size<1||size>StoreTransfer.MAX)throw new IllegalArgumentException("上传总长无效");
                    var rate=ScriptHost.current().uploadRate(player.uuid(),size);
                    if(!rate.allowed())return ScriptRpcResult.rateLimited(rpc.requestId(),rate.retryAfterMs(),ScriptErrorCode.RATE_LIMITED.defaultMessageKey());
                    result.addProperty("id",transfers.begin(player.uuid(),rpc.connectionEpoch(),size,args.get("sha").getAsString()));
                }
                case "store.file.begin" -> {
                    exact(args,"size","sha");int size=integer(args,"size");ScriptHost host=ScriptHost.current();
                    int limit=(int)host.quotas().current().get("package.compressed");
                    if(size<1||size>limit)throw new IllegalArgumentException("上传总长超出服务器配额");
                    if(!fileBytes.upload(player.uuid(),size,limit))return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.RATE_LIMITED);
                    result.addProperty("id",transfers.beginFile(player.uuid(),rpc.connectionEpoch(),size,args.get("sha").getAsString()));
                }
                case "store.chunk" -> {
                    exact(args,"id","index","bytes");String encoded=args.get("bytes").getAsString();if(encoded.length()>2732)throw new IllegalArgumentException("分片超额");
                    result.addProperty("next",transfers.chunk(player.uuid(),rpc.connectionEpoch(),args.get("id").getAsString(),integer(args,"index"),Base64.getDecoder().decode(encoded)));
                }
                case "store.commit" -> {
                    exact(args,"id");byte[] raw=transfers.commit(player.uuid(),rpc.connectionEpoch(),args.get("id").getAsString());
                    QuotaConfig q=ScriptHost.current().quotas().current();AppPackage p=PackageReader.read(raw,(int)q.get("package.compressed"),(int)q.get("package.expanded"));StoreRepository.requireSignature(p);
                    if(!TrustState.of(p,p.manifest().id(),TrustStore.load(ServerPackageScanner.configPath(server))).state().installable)throw new IllegalArgumentException("作者信任检查拒绝");
                    ScriptHost.current().witnesses().require(p,false);requireNotRevoked(p);
                    StoreRepository.Entry e=repository.submit(raw,player.uuid(),player.name(),System.currentTimeMillis());
                    summary(e);
                    result.addProperty("digest",e.digest());result.addProperty("state",e.state().name());
                    MCphone.LOGGER.info("[MCphone] 商店提交 submitter={} name={} app={} digest={}",player.uuid(),e.name(),e.app(),e.digest());
                }
                case "store.list", "store.queue" -> {
                    exact(args,"offset");boolean queue=action.equals("store.queue");
                    if(queue&&!reviewer(player.uuid()))return denied(rpc);
                    int offset=integer(args,"offset");if(offset<0||offset>64)throw new IllegalArgumentException("分页无效");
                    List<StoreRepository.Entry> list=repository.entries().stream().filter(e->queue||visible(e,player.uuid())).toList();
                    JsonArray rows=new JsonArray();for(int i=offset;i<Math.min(list.size(),offset+2);i++)rows.add(summary(list.get(i)));
                    result.add("items",rows);result.addProperty("total",list.size());
                }
                case "store.current" -> {
                    exact(args,"app");String app=args.get("app").getAsString();if(app.length()>64||!app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("App 标识无效");
                    StoreRepository.Entry chosen=null;long version=-1;
                    for(StoreRepository.Entry entry:repository.entries())if(entry.app().equals(app)&&visible(entry,player.uuid())){long incoming=RevocationPolicy.versionOf(repository.pkg(entry.digest()));if(incoming>version){chosen=entry;version=incoming;}}
                    result.addProperty("available",chosen!=null);if(chosen!=null)result.add("package",summary(chosen));
                }
                case "store.meta", "store.read", "store.inspect" -> {
                    boolean inspect=action.equals("store.inspect");
                    if(inspect&&!reviewer(player.uuid()))return denied(rpc);
                    String digest=args.get("digest").getAsString();StoreRepository.Entry e=repository.entry(digest);
                    if(e==null||(!inspect&&!visible(e,player.uuid())))return denied(rpc);
                    AppPackage p=repository.pkg(digest);if(!inspect)requireNotRevoked(p);
                    if(action.equals("store.meta")) {exact(args,"digest");result=summary(e);byte[] bytes=envelope(p);result.addProperty("size",bytes.length);result.addProperty("sha",StoreTransfer.sha(bytes));}
                    else if(inspect) {
                        exact(args,"digest","path","offset");String path=args.get("path").getAsString();int offset=integer(args,"offset");
                        if(path.isEmpty()) {
                            if(offset<0||offset>64)throw new IllegalArgumentException("分页无效");
                            JsonArray files=new JsonArray();List<String> paths=new ArrayList<>(p.paths());Collections.sort(paths);
                            for(int i=offset;i<Math.min(paths.size(),offset+12);i++) {String name=paths.get(i);JsonObject f=new JsonObject();f.addProperty("path",name);f.addProperty("size",p.entry(name).length);files.add(f);}
                            result.add("files",files);result.addProperty("total",paths.size());result.addProperty("manifest",new String(p.entry("manifest.json"),StandardCharsets.UTF_8));
                            // 完整 manifest 可能超过回包上限；它也可以按普通文件分片翻阅。
                            if(result.toString().getBytes(StandardCharsets.UTF_8).length>3500)result.remove("manifest");
                        } else {
                            byte[] file=p.entry(path);if(file==null||!(path.endsWith(".js")||path.equals("manifest.json")))throw new IllegalArgumentException("只可翻阅源码与清单");
                            slice(result,file,offset);
                        }
                    } else {exact(args,"digest","offset");slice(result,envelope(p),integer(args,"offset"));}
                }
                case "store.review" -> {
                    exact(args,"digest","approve","reason","visibility","actions","capabilities","licenseAll","phrase");
                    if(!reviewer(player.uuid()))return denied(rpc);
                    String digest=args.get("digest").getAsString();boolean approve=args.get("approve").getAsBoolean();
                    AppPackage p=repository.pkg(digest);if(approve)requireNotRevoked(p);
                    ScriptHost.current().quotas().audit().append(player.uuid(),"store.review",p.manifest().id(),new Gson().toJsonTree(repository.entry(digest)),args);
                    JsonObject manifest=JsonParser.parseString(new String(p.entry("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();
                    List<String> declared=ServerPackageScanner.stringList(manifest,"capabilities"),actions=ServerPackageScanner.stringList(args,"actions"),caps=ServerPackageScanner.stringList(args,"capabilities");
                    if(actions.size()>32||caps.size()>32||!declared.containsAll(caps))throw new IllegalArgumentException("能力勾选无效");
                    boolean privileged=p.entry("server.js")!=null||!declared.isEmpty();
                    if(approve) {
                        var trust=TrustStore.load(ServerPackageScanner.configPath(server));var verdict=TrustState.of(p,p.manifest().id(),trust);
                        if(!SigCopy.canProceed(verdict,args.get("phrase").getAsString()))throw new IllegalArgumentException("作者指纹需要确认");
                        if(privileged) {
                            var live=server.getPlayerList().getPlayer(player.uuid());
                            if(!config.get().deploymentApprovers().contains(player.uuid())||live==null||!live.hasPermissions(3))return denied(rpc);
                            if(p.entry("server.js")==null)throw new IllegalArgumentException("申请能力但没有服务端入口");
                            List<String> declaredActions=new ArrayList<>(ActionGuards.parse(new String(p.entry("manifest.json"),StandardCharsets.UTF_8)).keySet());
                            if(!declaredActions.containsAll(actions))throw new IllegalArgumentException("动作勾选无效");
                            DeploymentData.Candidate candidate=new DeploymentData.Candidate(p.manifest().id(),digest,FrontendDigest.of(p),System.currentTimeMillis(),declaredActions,declared);
                            Deployment preview=ScriptHost.current().deployments().preview(candidate,actions,caps,player.uuid(),System.currentTimeMillis()).deployment();
                            var scope=ServerAppAssembler.assembleOne(preview,p);if(scope==null)throw new IllegalArgumentException("服务端脚本静态检查失败");scope.discard();
                            ScriptHost.current().witnesses().require(p,true);
                            StoreRepository.atomic(server.getWorldPath(LevelResource.ROOT).resolve(ServerPackageScanner.DIR).resolve(digest+".mcphone"),repository.raw(digest));
                            ScriptHost.current().deployments().approve(candidate,actions,caps,player.uuid(),System.currentTimeMillis());
                            if(!ScriptHost.reassemble(server,p.manifest().id()))throw new IllegalStateException("部署装配失败");
                        }
                        repository.review(digest,player.uuid(),true,args.get("reason").getAsString(),args.get("visibility").getAsString(),System.currentTimeMillis());
                        var signature=SigManifest.parse(p.signature());trust.record(signature.fingerprint(),signature.author(),signature.pubkey(),System.currentTimeMillis());trust.trust(signature.fingerprint(),p.manifest().id());trust.save(ServerPackageScanner.configPath(server));
                        if(args.get("licenseAll").getAsBoolean())AuthorityData.get(server).licenseAll(p.manifest().id());
                    } else repository.review(digest,player.uuid(),false,args.get("reason").getAsString(),"hidden",System.currentTimeMillis());
                    MCphone.LOGGER.warn("[MCphone] 商店审核 reviewer={} digest={} approved={} capabilities={} actions={} reason={}",player.uuid(),digest,approve,caps,actions,StoreRepository.clean(args.get("reason").getAsString(),128));
                    for(var online:server.getPlayerList().getPlayers())HandshakeService.pushTo(online,ScriptHost.current().epoch(online.getUUID()));
                    result.addProperty("approved",approve);
                }
                case "store.request" -> {
                    exact(args,"app");String app=args.get("app").getAsString();
                    if(repository.entries().stream().noneMatch(e->e.app().equals(app)&&visible(e,player.uuid())))return denied(rpc);
                    // 请求只提醒管理员，不授予权限；客户端不能指定接收者。
                    for(var online:server.getPlayerList().getPlayers())if(reviewer(online.getUUID()))online.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MCPhone] "+StoreRepository.clean(player.name(),32)+" ("+player.uuid()+") 申请 "+app+" 的使用许可"));
                    MCphone.LOGGER.info("[MCphone] 商店申请 player={} app={}",player.uuid(),app);
                }
                default -> throw new IllegalArgumentException("未知商店操作");
            }
            byte[] data=result.toString().getBytes(StandardCharsets.UTF_8);if(data.length>ScriptProtocol.DATA_MAX)throw new IllegalArgumentException("回包超额");
            return ScriptRpcResult.ok(rpc.requestId(),data,0);
        } catch(IOException unavailable) {MCphone.LOGGER.error("[MCphone] 商店存储不可用",unavailable);return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);}
        finally {if(rpc.actionId().equals("store.commit")||rpc.actionId().equals("store.review")){ScriptHost.current().publishRevocations();refreshUpdateSources();}}
    }
    public boolean reviewer(UUID player) {return config.get().storeReviewers().contains(player);}
    /** incoming 导入只排队；与已批准摘要完全相同的包才沿用服主批准。 */
    public void importIncoming() {
        Path dir=server.getWorldPath(LevelResource.ROOT).resolve(ServerPackageScanner.DIR);
        if(!java.nio.file.Files.isDirectory(dir))return;
        try(var files=java.nio.file.Files.newDirectoryStream(dir)) {
            int count=0;
            for(Path file:files) {
                if(++count>64)break;
                String name=file.getFileName().toString();
                if(!(name.endsWith(".mcphone")||name.endsWith(".zip"))||java.nio.file.Files.isSymbolicLink(file)||!java.nio.file.Files.isRegularFile(file)||java.nio.file.Files.size(file)>StoreTransfer.MAX)continue;
                try {
                    byte[] raw=java.nio.file.Files.readAllBytes(file);QuotaConfig q=ScriptHost.current().quotas().current();AppPackage pkg=PackageReader.read(raw,(int)q.get("package.compressed"),(int)q.get("package.expanded"));
                    StoreRepository.requireSignature(pkg);requireNotRevoked(pkg);
                    if(repository.entry(pkg.digest())==null)repository.submit(raw,new UUID(0,0),"服务器目录",System.currentTimeMillis());
                    Deployment d=ScriptHost.current().deployments().deployment(pkg.manifest().id());
                    if(d!=null&&d.packageDigest().equals(pkg.digest()))recordDeployment(d);
                } catch(IOException|RuntimeException bad) {MCphone.LOGGER.warn("[MCphone] 商店导入拒绝 {}：{}",name,bad.toString());}
            }
        } catch(IOException bad) {MCphone.LOGGER.warn("[MCphone] 商店目录不可读",bad);}refreshUpdateSources();
    }
    public void tickUpdates(){authorUpdates.tick();}
    public List<JsonObject> publishedSummaries()throws IOException {Map<String,JsonObject> latest=new TreeMap<>();for(var e:repository.entries())if(e.state()==StoreRepository.State.APPROVED){JsonObject row=summary(e);Deployment d=ScriptHost.current().deployments().deployment(e.app());if(row.get("backend").getAsBoolean()&&(d==null||!d.packageDigest().equals(e.digest())))continue;JsonObject previous=latest.get(e.app());if(previous==null||row.get("versionCode").getAsBigDecimal().longValueExact()>previous.get("versionCode").getAsBigDecimal().longValueExact()){row.addProperty("followUpdates",config.get().followsUpdates(e.app()));latest.put(e.app(),row);}}return List.copyOf(latest.values());}
    public void reloadUpdates(){refreshUpdateSources();authorUpdates.reload();}
    public void close(){authorUpdates.close();}
    private void refreshUpdateSources(){Map<String,AppPackage> newest=new LinkedHashMap<>();for(var e:repository.entries())if(e.state()==StoreRepository.State.APPROVED)try{AppPackage pkg=repository.pkg(e.digest());Deployment d=ScriptHost.current().deployments().deployment(e.app());if(pkg.entry("server.js")!=null&&(d==null||!d.packageDigest().equals(pkg.digest())))continue;AppPackage old=newest.get(e.app());if(old==null||RevocationPolicy.versionOf(pkg)>RevocationPolicy.versionOf(old))newest.put(e.app(),pkg);}catch(Exception bad){MCphone.LOGGER.warn("[MCphone] 更新源不能载入 {}",e.app());}newest.forEach((id,pkg)->{try{Deployment d=ScriptHost.current().deployments().deployment(id);authorUpdates.register(pkg,d==null?Set.of():Set.copyOf(d.approvedCapabilities()));}catch(RuntimeException bad){MCphone.LOGGER.warn("[MCphone] 更新源无效 {}",id);}});}
    /** worker 交回的包仍在主线程核对来源，未批准的新权限只写待审队列，不修改许可名单。 */
    public void acceptAuthorUpdate(AppPackage installed,UpdateVerifier.Verified result,byte[] raw,Set<String> previousApproval)throws IOException {
        ScriptHost host=ScriptHost.current();String id=installed.manifest().id();if(!config.get().followsUpdates(id)||!config.get().net().enabled())return;
        StoreRepository.Entry old=repository.entry(installed.digest());if(old==null||old.state()!=StoreRepository.State.APPROVED)return;
        if(!TrustState.of(installed,id,TrustStore.load(ServerPackageScanner.configPath(server))).state().installable)return;
        AppPackage incoming=result.app();StoreRepository.requireSignature(incoming);if(!ServerFrontendUpdate.sameAuthor(installed,incoming)||RevocationPolicy.versionOf(incoming)<=host.witnesses().approved(id,SigManifest.parse(incoming.signature()).pubkey()))return;
        requireNotRevoked(incoming);host.witnesses().require(incoming,false);Deployment previous=host.deployments().deployment(id);
        if(installed.entry("server.js")!=null&&(previous==null||!previous.packageDigest().equals(installed.digest())||!Set.copyOf(previous.approvedCapabilities()).equals(previousApproval)))return;
        var decision=UpdateVerifier.capabilities(incoming,previousApproval);StoreRepository.Entry entry=repository.submit(raw,new UUID(0,0),"作者更新",System.currentTimeMillis());
        JsonObject after=new JsonObject();after.addProperty("version",Long.toString(result.version()));after.addProperty("digest",incoming.digest());after.addProperty("decision",decision.decision().name());after.add("added",new Gson().toJsonTree(decision.addedCapabilities()));host.quotas().audit().append(new UUID(0,0),"store.author_update",id,new Gson().toJsonTree(old),after);
        if(decision.decision()!=UpdateVerifier.Decision.AUTOMATIC){repository.pendingReason(entry.digest(),"作者更新待审批："+decision.decision()+" "+String.join(",",decision.addedCapabilities().stream().sorted().toList()));return;}
        List<String> caps=ServerPackageScanner.stringList(JsonParser.parseString(new String(incoming.entry("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject(),"capabilities");
        boolean backend=incoming.entry("server.js")!=null;if(!backend&&!caps.isEmpty())throw new IllegalArgumentException("有服务端能力而没有后端入口");
        long now=System.currentTimeMillis();UUID approver=previous==null?old.reviewer():previous.approver();
        DeploymentData.Candidate candidate=null;List<String> actions=List.of();
        if(backend){actions=new ArrayList<>(ActionGuards.parse(new String(incoming.entry("manifest.json"),StandardCharsets.UTF_8)).keySet());candidate=new DeploymentData.Candidate(id,incoming.digest(),FrontendDigest.of(incoming),now,actions,caps);var preview=host.deployments().preview(candidate,actions,caps,approver,now).deployment();var scope=ServerAppAssembler.assembleOne(preview,incoming);if(scope==null)throw new IllegalArgumentException("更新静态检查失败");scope.discard();StoreRepository.atomic(server.getWorldPath(LevelResource.ROOT).resolve(ServerPackageScanner.DIR).resolve(incoming.digest()+".mcphone"),raw);}
        repository.review(incoming.digest(),approver,true,"跟随作者，能力增量自动批准",old.visibility(),now);
        if(backend){host.deployments().approve(candidate,actions,caps,approver,now);if(!ScriptHost.reassemble(server,id))throw new IOException("更新装配失败");}
        else if(previous!=null){host.deployments().remove(id);ScriptHost.reassemble(server,id);}
        refreshUpdateSources();host.broadcastHandshake();
    }
    public void recordDeployment(Deployment d) {
        StoreRepository.Entry e=repository.entry(d.packageDigest());if(e==null)return;
        try {repository.review(e.digest(),d.approver()==null?new UUID(0,0):d.approver(),true,"服主部署批准",e.state()==StoreRepository.State.APPROVED?e.visibility():"public",d.approvedAt());}
        catch(IOException bad){MCphone.LOGGER.error("[MCphone] 商店批准记录未保存",bad);}
    }
    public boolean visible(StoreRepository.Entry e,UUID player) {
        if(e.state()!=StoreRepository.State.APPROVED||e.visibility().equals("hidden"))return false;
        if(e.visibility().equals("licensed")&&!AuthorityData.get(server).isLicensed(e.app(),player))return false;
        try {JsonObject description=summary(e);AppPackage pkg=repository.pkg(e.digest());if(!TrustState.of(pkg,e.app(),TrustStore.load(ServerPackageScanner.configPath(server))).state().installable)return false;requireNotRevoked(pkg);
            if(description.get("backend").getAsBoolean()) {Deployment d=ScriptHost.current().deployments().deployment(e.app());return d!=null&&d.packageDigest().equals(e.digest());} return true;
        } catch(IOException|RuntimeException bad) {return false;}
    }
    public int visibility(String app,UUID player) {
        var entries=repository.entries();for(int i=entries.size()-1;i>=0;i--) {var e=entries.get(i);if(e.app().equals(app)&&e.state()==StoreRepository.State.APPROVED)return visible(e,player)?(e.visibility().equals("public")?0:1):-1;}return 0;
    }
    private JsonObject summary(StoreRepository.Entry e) throws IOException {
        JsonObject cached=descriptions.get(e.digest());if(cached!=null){JsonObject result=cached.deepCopy();result.addProperty("state",e.state().name());result.addProperty("reviewed",Long.toString(e.reviewed()));result.addProperty("visibility",e.visibility());result.addProperty("reason",e.reason());updateInfo(result,e);return result;}
        AppPackage p=repository.pkg(e.digest());SigManifest sig=SigManifest.parse(p.signature());JsonObject result=new JsonObject();
        result.addProperty("digest",e.digest());result.addProperty("id",e.app());result.addProperty("name",StoreRepository.clean(p.manifest().name(),64));
        result.addProperty("version",p.manifest().version());result.addProperty("author",StoreRepository.clean(sig.author(),32));
        result.addProperty("fingerprint",sig.fingerprint());result.addProperty("pubkey",Base64.getEncoder().encodeToString(sig.pubkey()));
        result.addProperty("submitter",e.submitter().toString());result.addProperty("submitterName",e.name());result.addProperty("state",e.state().name());
        result.addProperty("reviewed",Long.toString(e.reviewed()));result.addProperty("visibility",e.visibility());result.addProperty("reason",e.reason());
        result.addProperty("backend",p.entry("server.js")!=null);result.addProperty("versionCode",Long.toString(RevocationPolicy.versionOf(p)));
        result.addProperty("serverTrust",TrustState.of(p,p.manifest().id(),TrustStore.load(ServerPackageScanner.configPath(server))).state().name());
        result.addProperty("frontendDigest",FrontendDigest.of(p));updateInfo(result,e);
        descriptions.put(e.digest(),result.deepCopy());return result;
    }
    private void updateInfo(JsonObject result,StoreRepository.Entry e){result.addProperty("frontendUpdate",config.get().frontendUpdate(e.app()));Deployment d=ScriptHost.current().deployments().deployment(e.app());JsonArray approved=new JsonArray();if(d!=null&&d.packageDigest().equals(e.digest()))d.approvedCapabilities().forEach(approved::add);result.add("approvedCapabilities",approved);}
    public void pushUpdates(net.minecraft.server.level.ServerPlayer player){ScriptHost host=ScriptHost.current();for(Deployment d:host.deployments().deployments()){
        StoreRepository.Entry entry=repository.entry(d.packageDigest());if(entry==null||!visible(entry,player.getUUID()))continue;
        try{JsonObject data=summary(entry);data.addProperty("serverId",host.serverId().toString());data.addProperty("epoch",Long.toString(host.epoch(player.getUUID())));byte[] raw=data.toString().getBytes(StandardCharsets.UTF_8);if(raw.length<=ScriptProtocol.DATA_MAX)ScriptPushHandler.push(player,new ScriptPush(ScriptProtocol.HOST_APP_ID,"mcphone:store.update",raw,Seq.next()));}
        catch(IOException|RuntimeException bad){MCphone.LOGGER.warn("[MCphone] 前端更新提示无法推送 {}",d.appId(),bad);}
    }}
    private byte[] envelope(AppPackage p) throws IOException {byte[] bytes=envelopes.get(p.digest());if(bytes==null){bytes=FrontendEnvelope.write(p);if(envelopes.size()>=64)envelopes.remove(envelopes.keySet().iterator().next());envelopes.put(p.digest(),bytes);}return bytes;}
    private void requireNotRevoked(AppPackage p) {ScriptHost host=ScriptHost.current();RevocationPolicy policy=host==null?config.get().revocations():host.revocations(p);if(policy.rejected(p.manifest().id(),RevocationPolicy.versionOf(p),p.digest())!=null)throw new IllegalArgumentException("包已被吊销");}
    /** 由已切回主线程的专用通道调用。读取每片仍检查当前作者信任、版本、部署与可见性。 */
    public StoreFileReply file(StoreFileRequest request,UUID player) {
        ScriptHost host=ScriptHost.current();
        try {
            if(host.epoch(player)!=request.epoch())return StoreFileChannel.failed(host,request,ScriptErrorCode.NOT_AUTHORIZED);
            if(!fileRate.allow(player,"store.file").allowed())return StoreFileChannel.failed(host,request,ScriptErrorCode.RATE_LIMITED);
            int next,size=0;byte[] bytes=new byte[0];
            if(request.kind()==StoreFileRequest.UPLOAD)next=transfers.fileChunk(player,request.epoch(),request.token(),request.point(),request.bytes());
            else {
                StoreRepository.Entry entry=repository.entry(request.digest());
                if(entry==null||!visible(entry,player))return StoreFileChannel.failed(host,request,ScriptErrorCode.NOT_AUTHORIZED);
                AppPackage pkg=repository.pkg(request.digest());requireNotRevoked(pkg);byte[] full=envelope(pkg);size=full.length;
                int offset=request.point();if(offset>=size)throw new IllegalArgumentException("片偏移无效");
                next=Math.min(size,offset+StoreFileRequest.CHUNK);
                if(!fileBytes.download(player,next-offset))return StoreFileChannel.failed(host,request,ScriptErrorCode.RATE_LIMITED);
                bytes=Arrays.copyOfRange(full,offset,next);
            }
            return new StoreFileReply(StoreFileRequest.PROTOCOL,host.serverId(),request.epoch(),request.token(),request.kind(),request.point(),next,size,ScriptErrorCode.OK,bytes);
        } catch(IllegalArgumentException bad){return StoreFileChannel.failed(host,request,ScriptErrorCode.INVALID_ARGUMENT);}
        catch(IOException|RuntimeException bad){return StoreFileChannel.failed(host,request,ScriptErrorCode.UNAVAILABLE);}
    }
    private static void slice(JsonObject out,byte[] bytes,int offset) {if(offset<0||offset>bytes.length||offset%StoreTransfer.CHUNK!=0)throw new IllegalArgumentException("片偏移无效");int end=Math.min(bytes.length,offset+StoreTransfer.CHUNK);out.addProperty("bytes",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,end)));out.addProperty("next",end);out.addProperty("size",bytes.length);out.addProperty("eof",end==bytes.length);}
    private static void exact(JsonObject args,String...keys) {if(!args.keySet().equals(Set.of(keys)))throw new IllegalArgumentException("参数字段无效");}
    private static int integer(JsonObject args,String key) {return args.get(key).getAsBigDecimal().intValueExact();}
    private static ScriptRpcResult denied(ScriptRpc rpc) {return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.NOT_AUTHORIZED);}
    public void forget(UUID player) {transfers.cancel(player);}
}
