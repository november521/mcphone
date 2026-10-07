package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.pkg.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 已完整验签包的服务端版本见证。按完整公钥隔离，记录每个版本的签名摘要；冲突永久停用，批准版本不可回退。 */
public final class VersionWitness {
    private record Row(String app, byte[] key, long approved, Map<Long,Map<String,String>> versions,String manualDigest) {
        boolean conflict(){return versions.values().stream().anyMatch(proofs->proofs.size()>1);}
    }
    private static final Object IO_LOCK=new Object();
    private final Path file;
    private Map<String,Row> rows;
    private boolean unavailable;
    public VersionWitness(Path file)throws IOException {this.file=file;rows=read();}
    private static String identity(String app,byte[] key){return app+"|"+Base64.getEncoder().encodeToString(key);}

    /** 只在管理扫描时重载；RPC 判定只读内存。坏文件不能退回空策略。 */
    public synchronized void reload() throws IOException {
        try {
            if(!rows.isEmpty()&&!Files.exists(file))throw new IOException("已有版本见证文件被删除");
            rows=read();
        } catch(IOException bad){unavailable=true;throw bad;}
    }
    public synchronized void require(AppPackage pkg,boolean approve)throws IOException {
        if(!observe(pkg,approve))throw new IllegalArgumentException("版本回退或同版本作者签名冲突，拒绝提交/批准");
    }

    /** observe 不抬高批准下限；approve 必须在真正批准时调用。有效冲突证据即使被拒也先 fsync 保存。 */
    public synchronized boolean observe(AppPackage pkg,boolean approve)throws IOException {
        if(unavailable)throw new IOException("版本见证曾写入/重载失败；修复存储并重启宿主后再试");
        StoreRepository.requireSignature(pkg);SigManifest sig=SigManifest.parse(pkg.signature());long version=RevocationPolicy.versionOf(pkg);
        String app=pkg.manifest().id(),id=identity(app,sig.pubkey());
        try { synchronized(IO_LOCK){reload();Map<String,Row> next=new LinkedHashMap<>(rows);Row previous=next.get(id);
            if(previous==null&&next.size()>=128)throw new Full("版本见证的作者/App 数量已满");
            Map<Long,Map<String,String>> versions=new LinkedHashMap<>();
            if(previous!=null)versions.putAll(previous.versions());
            if(!versions.containsKey(version)&&versions.size()>=64)throw new Full("单 App 版本见证已满，需服主归档");
            Map<String,String> proofs=new LinkedHashMap<>(versions.getOrDefault(version,Map.of()));
            if(proofs.size()<2||proofs.containsKey(pkg.digest()))proofs.put(pkg.digest(),new String(pkg.signature(),StandardCharsets.UTF_8));
            versions.put(version,Map.copyOf(proofs));
            long highest=previous==null?-1:previous.approved();
            String manual=previous==null?"":previous.manualDigest();if(approve&&version>=highest)manual="";
            Row candidate=new Row(app,sig.pubkey(),approve&&!versions.values().stream().anyMatch(v->v.size()>1)?Math.max(highest,version):highest,Map.copyOf(versions),manual);
            if(previous!=null&&previous.approved()==candidate.approved()&&previous.versions().equals(candidate.versions())&&previous.manualDigest().equals(candidate.manualDigest()))return !candidate.conflict()&&version>=highest;
            next.put(id,candidate);rows=next;write(next);
            return !candidate.conflict()&&version>=highest;
        }} catch(Full full){throw full;}catch(IOException bad){unavailable=true;throw bad;}
    }

    public synchronized RevocationPolicy policy(String app,byte[] key) {
        if(unavailable)return stopped(app,"版本见证存储不可用，已停止执行");
        Row row=rows.get(identity(app,key));
        if(row==null)return RevocationPolicy.NONE;
        if(row.conflict())return stopped(app,"同一作者在同一版本签发了不同包，已停止执行");
        return row.approved()>0?new RevocationPolicy(Map.of(app,new RevocationPolicy.Rule(row.approved(),Set.of(),Set.of(),"低于该作者在此服已批准的版本"))):RevocationPolicy.NONE;
    }
    private static RevocationPolicy stopped(String app,String reason){return new RevocationPolicy(Map.of(app,new RevocationPolicy.Rule(Long.MAX_VALUE,Set.of(Long.MAX_VALUE),Set.of(),reason)));}
    private static final class Full extends IOException {Full(String message){super(message);}}
    public synchronized long approved(String app,byte[] key){Row row=rows.get(identity(app,key));return row==null?0:Math.max(0,row.approved());}
    public synchronized boolean seen(String app,byte[] key,long version,String digest){Row row=rows.get(identity(app,key));return row!=null&&row.versions().getOrDefault(version,Map.of()).containsKey(digest);}
    /** 仅客户端原生确认调用。只给这个已签名摘要例外，最高历史保持单调，自动更新仍不能回退。 */
    public synchronized void approveManual(AppPackage pkg)throws IOException {
        observe(pkg,false);String id=identity(pkg.manifest().id(),SigManifest.parse(pkg.signature()).pubkey());
        try{synchronized(IO_LOCK){reload();Row row=rows.get(id);if(unavailable||row==null||row.conflict())throw new IllegalArgumentException("签名冲突或历史不可用，不能人工绕过");long version=RevocationPolicy.versionOf(pkg);Map<String,Row> next=new LinkedHashMap<>(rows);next.put(id,new Row(row.app(),row.key(),Math.max(row.approved(),version),row.versions(),version<row.approved()?pkg.digest():""));write(next);rows=next;}}
        catch(IOException bad){unavailable=true;throw bad;}
    }
    public synchronized boolean manualAllowed(String app,byte[] key,long version,String digest){Row row=rows.get(identity(app,key));return !unavailable&&row!=null&&!row.conflict()&&!row.manualDigest().isEmpty()&&row.manualDigest().equals(digest)&&row.versions().getOrDefault(version,Map.of()).containsKey(digest);}

    private Map<String,Row> read()throws IOException {
        if(!Files.exists(file))return new LinkedHashMap<>();
        if(Files.isSymbolicLink(file)||Files.size(file)>4*1024*1024L)throw new IOException("版本见证文件异常");
        String raw=Files.readString(file,StandardCharsets.UTF_8);if(JsonScan.check(raw,6)!=null)throw new IOException("版本见证损坏");
        try {JsonArray array=JsonParser.parseString(raw).getAsJsonArray();if(array.size()>128)throw new IllegalArgumentException();Map<String,Row> out=new LinkedHashMap<>();
            for(JsonElement element:array){JsonObject entry=element.getAsJsonObject();String app=entry.get("app").getAsString();if(app.length()>64||!app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException();byte[] key=Base64.getDecoder().decode(entry.get("key").getAsString());Signatures.publicKey(key);long approved=entry.get("approved").getAsBigDecimal().longValueExact();if(approved< -1)throw new IllegalArgumentException();
                JsonArray records=entry.getAsJsonArray("versions");if(records.size()>64)throw new IllegalArgumentException();Map<Long,Map<String,String>> versions=new LinkedHashMap<>();
                for(JsonElement record:records){JsonObject v=record.getAsJsonObject();long version=v.get("version").getAsBigDecimal().longValueExact();if(version<0)throw new IllegalArgumentException();JsonObject proofs=v.getAsJsonObject("proofs");if(proofs.size()==0||proofs.size()>2)throw new IllegalArgumentException();Map<String,String> signed=new LinkedHashMap<>();
                    for(var proof:proofs.entrySet()){String digest=proof.getKey(),signature=proof.getValue().getAsString();if(!digest.matches("[a-f0-9]{64}")||signature.getBytes(StandardCharsets.UTF_8).length>2048)throw new IllegalArgumentException();SigManifest s=SigManifest.parse(signature.getBytes(StandardCharsets.UTF_8));if(!Arrays.equals(s.pubkey(),key)||!s.verify(digest))throw new IllegalArgumentException();signed.put(digest,signature);}
                    if(versions.put(version,Map.copyOf(signed))!=null)throw new IllegalArgumentException();
                }
                String manual=entry.has("manualDigest")?entry.get("manualDigest").getAsString():"";if(!manual.isEmpty()&&(!manual.matches("[a-f0-9]{64}")||versions.values().stream().noneMatch(v->v.containsKey(manual))))throw new IllegalArgumentException();
                if(approved>=0&&!versions.containsKey(approved)||out.put(identity(app,key),new Row(app,key,approved,Map.copyOf(versions),manual))!=null)throw new IllegalArgumentException();
            }return out;
        }catch(RuntimeException bad){throw new IOException("版本见证损坏，停止脚本装配",bad);}
    }

    private void write(Map<String,Row> next)throws IOException {
        JsonArray array=new JsonArray();for(Row row:next.values()){JsonObject entry=new JsonObject();entry.addProperty("app",row.app());entry.addProperty("key",Base64.getEncoder().encodeToString(row.key()));entry.addProperty("approved",Long.toString(row.approved()));if(!row.manualDigest().isEmpty())entry.addProperty("manualDigest",row.manualDigest());JsonArray versions=new JsonArray();
            for(var v:row.versions().entrySet()){JsonObject record=new JsonObject();record.addProperty("version",Long.toString(v.getKey()));JsonObject proofs=new JsonObject();v.getValue().forEach(proofs::addProperty);record.add("proofs",proofs);versions.add(record);}entry.add("versions",versions);array.add(entry);
        }byte[] data=array.toString().getBytes(StandardCharsets.UTF_8);if(data.length>4*1024*1024)throw new IOException("版本见证超过 4 MiB");ScriptStateData.atomicWrite(file,data);
    }
}
