package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.engine.ScriptStaticCheck;
import com.november.mcphone.core.script.server.*;
import com.november.mcphone.core.script.sfc.SfcCompiler;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPair;
import java.security.Signature;
import java.net.URI;
import java.util.*;

/** 验收包由真实源码生成并验签；私钥只在内存中，不能作为玩家或作者的正式身份复用。 */
public final class CompletionFixturesTest {
    private static int checks;
    private static String host=Optional.ofNullable(System.getenv("MCPHONE_FIXTURE_HOST")).orElse("example.com");
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static Path root(){for(Path p=Path.of("").toAbsolutePath();p!=null;p=p.getParent())if(Files.isDirectory(p.resolve("demo-apps/completion")))return p;throw new IllegalStateException("找不到验收源码");}
    private static Map<String,byte[]> contents(Path root,String kind)throws Exception{
        Map<String,byte[]> entries=new LinkedHashMap<>();Path dir=root.resolve("demo-apps/completion/"+kind);
        try(var files=Files.walk(dir)){for(Path file:files.filter(Files::isRegularFile).sorted().toList())entries.put(dir.relativize(file).toString().replace('\\','/'),Files.readAllBytes(file));}return entries;
    }
    private static AppPackage variant(Map<String,byte[]> original,KeyPair key,int version,String marker,boolean added,Path out,String name,JsonArray index)throws Exception{
        Map<String,byte[]> entries=new LinkedHashMap<>(original);JsonObject manifest=JsonParser.parseString(new String(entries.get("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();manifest.addProperty("version",version+".0.0");manifest.addProperty("versionCode",version);manifest.addProperty("description",marker);
        JsonArray hosts=new JsonArray();hosts.add(host);manifest.getAsJsonObject("net").add("hosts",hosts);JsonObject updates=new JsonObject();updates.addProperty("feed","https://"+host+"/mcphone/"+(manifest.get("id").getAsString().endsWith("server")?"server":"frontend")+"-appcast.xml");updates.addProperty("channel","stable");manifest.add("updates",updates);
        if(added)manifest.getAsJsonArray("capabilities").add("effect.give");entries.put("manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));entries.put(PackageReader.SIG,PackageSignTest.sigJson(key,PackageDigest.of(entries),"MCPhone 本地验收夹具"));
        byte[] zip=PackageSignTest.zip(entries);AppPackage pkg=PackageReader.read(zip);check(SigManifest.parse(pkg.signature()).verify(pkg.digest()),name+" 完整作者签名有效");
        SfcCompiler.compilePage("app.vue",new String(pkg.entry("app.vue"),StandardCharsets.UTF_8));checks++;
        NetDeclaration net=NetDeclaration.of(pkg);check(net!=null&&net.hosts().equals(Set.of(host)),name+" 网络声明经过真实解析");
        if(pkg.entry("server.js")!=null){String source=new String(pkg.entry("server.js"),StandardCharsets.UTF_8);check(ScriptStaticCheck.check(Map.of("server.js",source))==null,name+" 后端经过静态预检");check(ActionGuards.parse(manifest.toString()).size()==16,"十六个动作完整保留");check(BackgroundDeclaration.of(pkg).size()==1,"后台声明可被运行期解析");for(JsonElement cap:manifest.getAsJsonArray("capabilities"))check(CapabilityCatalog.knownDeclared(cap.getAsString()),"能力属于真实目录");
            AppPackage frontend=FrontendEnvelope.read(FrontendEnvelope.write(pkg));check(frontend.entry("server.js")==null&&frontend.digest().equals(pkg.digest()),"下发前端不带后端源码，原包签名仍有效");
        }
        Files.write(out.resolve(name+".zip"),zip);JsonObject row=new JsonObject();row.addProperty("file",name+".zip");row.addProperty("id",pkg.manifest().id());row.addProperty("version",version);row.addProperty("digest",pkg.digest());row.addProperty("publicKey",Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));row.addProperty("fingerprint",Signatures.fingerprint(key.getPublic()));index.add(row);return pkg;
    }
    private static Appcast.Release feed(Path out,KeyPair key,int version,String zipName,String feedName)throws Exception{
        byte[] zip=Files.readAllBytes(out.resolve(zipName+".zip"));Signature signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(zip);String signature=Base64.getEncoder().encodeToString(signer.sign());String xml="<rss xmlns:m='"+Appcast.NS+"'><channel><item><enclosure url='https://"+host+"/mcphone/"+zipName+".zip' length='"+zip.length+"' type='application/zip' m:version='"+version+"' m:shortVersionString='"+version+".0.0' m:edSignature='"+signature+"'/></item></channel></rss>";byte[] data=xml.getBytes(StandardCharsets.UTF_8);Files.write(out.resolve(feedName),data);var releases=Appcast.parse(data,URI.create("https://"+host+"/mcphone/"+feedName));check(releases.size()==1,"导出的 feed 由真实解析器接受");return releases.get(0);
    }
    public static void main(String[] args)throws Exception{
        if(host.length()>253||!host.matches("[a-z0-9.-]+"))throw new IllegalArgumentException("MCPHONE_FIXTURE_HOST 必须是小写公共 HTTPS 域名");SafeFetch.validate("https://"+host+"/",Set.of(host));
        Path repo=root(),out=Path.of("build/completion-fixtures").toAbsolutePath();Files.createDirectories(out);KeyPair a=Signatures.generate(),b=Signatures.generate();JsonArray index=new JsonArray();
        Map<String,byte[]> server=contents(repo,"server"),client=contents(repo,"frontend");
        var s1=variant(server,a,1,"后端基线：操作前记录背包、钱包与 UTC 审计。",false,out,"server-v1",index);
        var s2=variant(server,a,2,"同作者后端更新：无新增能力，应保留许可。",false,out,"server-v2",index);
        var s3=variant(server,a,3,"新增 effect.give，跟随作者更新时必须留在待审批队列。",true,out,"server-v3-permissions",index);
        var f1=variant(client,a,1,"前端基线：先测试长文本，再解锁保险箱。",false,out,"frontend-v1",index);
        var f2=variant(client,a,2,"同作者更新：从原生更新页核对版本二。",false,out,"frontend-v2",index);
        var other=variant(client,b,3,"另一完整公钥：必须在原生页面确认新指纹，不能自动替换。",false,out,"frontend-other-author",index);
        var conflict=variant(client,a,2,"故意的同版本签名冲突；此用例最后执行，会永久停止这个测试作者的 App。",false,out,"frontend-conflict-v2",index);
        var serverFeed=feed(out,a,2,"server-v2","server-appcast.xml");var permissionFeed=feed(out,a,3,"server-v3-permissions","server-appcast-permissions.xml");feed(out,a,2,"frontend-v2","frontend-appcast.xml");
        Set<String> approved=new HashSet<>();for(JsonElement cap:JsonParser.parseString(new String(s1.entry("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("capabilities"))approved.add(cap.getAsString());
        check(UpdateVerifier.verify(Files.readAllBytes(out.resolve("server-v2.zip")),serverFeed,s1,1,approved,RevocationPolicy.NONE).decision()==UpdateVerifier.Decision.AUTOMATIC,"双签名同权限升级可自动更新");
        check(UpdateVerifier.verify(Files.readAllBytes(out.resolve("server-v3-permissions.zip")),permissionFeed,s2,2,approved,RevocationPolicy.NONE).decision()==UpdateVerifier.Decision.APPROVAL_REQUIRED,"真实新增能力进入人工审批");
        boolean badStyle=false;try{SfcCompiler.compilePage("app.vue",new String(server.get("app.vue"),StandardCharsets.UTF_8).replace(".page{","column{"));}catch(RuntimeException expected){badStyle=true;}check(badStyle,"还原不支持的类型样式选择器时样例检查必须失败");
        Path temp=Files.createTempDirectory("mcphone-completion-witness");VersionWitness witness=new VersionWitness(temp.resolve("versions.json"));
        check(witness.observe(s1,true)&&witness.observe(s2,false),"新候选不停止已批准基线");check(witness.approved(s1.manifest().id(),a.getPublic().getEncoded())==1,"候选不抬高下限");check(witness.observe(s2,true)&&!witness.observe(s1,true),"批准二后自动回退一被拒");
        check(witness.observe(s3,false)&&witness.approved(s1.manifest().id(),a.getPublic().getEncoded())==2,"新增能力待审包不抬高下限");
        check(witness.observe(f1,true)&&witness.observe(f2,true),"前端新版批准");witness.approveManual(f1);check(witness.manualAllowed(f1.manifest().id(),a.getPublic().getEncoded(),1,f1.digest()),"明确人工降级可以记录精确包例外");check(witness.approved(f1.manifest().id(),a.getPublic().getEncoded())==2,"人工降级不清除最高版本记录");
        check(witness.observe(other,false),"换作者隔离完整公钥见证");check(!witness.observe(conflict,false),"真实同版本两签名证据触发永久停止");
        JsonObject unsupported=JsonParser.parseString(new String(server.get("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();unsupported.getAsJsonArray("actions").get(2).getAsJsonObject().getAsJsonArray("guards").add(JsonParser.parseString("{\"cost\":1}"));boolean rejected=false;try{ActionGuards.parse(unsupported.toString());}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"未实现的 cost 必须拒绝");
        Files.writeString(out.resolve("fixture-index.json"),new GsonBuilder().setPrettyPrinting().create().toJson(index),StandardCharsets.UTF_8);System.out.println("CompletionFixturesTest: "+checks+" passed; 签名样例："+out);
    }
}
