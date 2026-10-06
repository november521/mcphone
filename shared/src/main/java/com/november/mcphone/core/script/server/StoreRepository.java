package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.pkg.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 有界商店审核日志。原包用摘要命名，审核元数据与提交者身份分开保存。 */
public final class StoreRepository {
    public enum State {PENDING,APPROVED,REJECTED}
    public record Entry(String digest,String app,UUID submitter,String name,long submitted,State state,
                        UUID reviewer,long reviewed,String reason,String visibility) { }
    private final Path root;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private java.util.function.Supplier<QuotaConfig> quotas=()->QuotaConfig.DEFAULT;
    private VersionWitness witness;
    public StoreRepository witnesses(VersionWitness value){witness=Objects.requireNonNull(value);return this;}
    public StoreRepository quotas(java.util.function.Supplier<QuotaConfig> value){quotas=value;return this;}
    public StoreRepository(Path root) throws IOException {
        this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);
        Path index=this.root.resolve("index.json"); if(!Files.exists(index)) return;
        if(Files.isSymbolicLink(index)||Files.size(index)>262144) throw new IOException("商店日志异常");
        String json=Files.readString(index,StandardCharsets.UTF_8);
        if(JsonScan.check(json,4)!=null) throw new IOException("商店日志损坏");
        try {
            JsonArray array=JsonParser.parseString(json).getAsJsonArray(); if(array.size()>64) throw new IllegalArgumentException("目录超额");
            for(JsonElement value:array) {
                JsonObject o=value.getAsJsonObject(); Entry e=new Entry(o.get("digest").getAsString(),o.get("app").getAsString(),
                    UUID.fromString(o.get("submitter").getAsString()),clean(o.get("name").getAsString(),32),o.get("submitted").getAsBigDecimal().longValueExact(),
                    State.valueOf(o.get("state").getAsString()),o.get("reviewer").isJsonNull()?null:UUID.fromString(o.get("reviewer").getAsString()),
                    o.get("reviewed").getAsBigDecimal().longValueExact(),clean(o.get("reason").getAsString(),128),visibility(o.get("visibility").getAsString()));
                if(!e.digest.matches("[0-9a-f]{64}") || !e.app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || entries.put(e.digest,e)!=null) throw new IllegalArgumentException("条目无效");
                AppPackage p=pkg(e.digest);if(!p.manifest().id().equals(e.app)) throw new IllegalArgumentException("目录与原包不一致");
            }
        } catch(RuntimeException bad) {throw new IOException("商店日志损坏",bad);}
    }
    public Entry submit(byte[] raw,UUID submitter,String name,long now) throws IOException {
        AppPackage pkg=PackageReader.read(raw,(int)quotas.get().get("package.compressed"),(int)quotas.get().get("package.expanded")); requireSignature(pkg);
        if(witness!=null)witness.require(pkg,false);
        Entry old=entries.get(pkg.digest()); if(old!=null) return old;
        if(entries.size()>=64) throw new IllegalStateException("商店目录已满，请管理员归档旧包");
        Entry e=new Entry(pkg.digest(),pkg.manifest().id(),submitter,clean(name,32),now,State.PENDING,null,0,"","hidden");
        atomic(root.resolve(e.digest+".mcphone"),raw);
        Map<String,Entry> next=new LinkedHashMap<>(entries);next.put(e.digest,e);save(next);return e;
    }
    public Entry review(String digest,UUID reviewer,boolean approve,String reason,String visibility,long now) throws IOException {
        Entry old=entry(digest); if(old==null) throw new IllegalArgumentException("待审包不存在");
        if(approve){AppPackage pkg=pkg(digest);requireSignature(pkg);if(witness!=null)witness.require(pkg,true);}
        Entry e=new Entry(old.digest,old.app,old.submitter,old.name,old.submitted,approve?State.APPROVED:State.REJECTED,
            reviewer,now,clean(reason,128),visibility(visibility));
        Map<String,Entry> next=new LinkedHashMap<>(entries);next.put(digest,e);save(next);return e;
    }
    public AppPackage pkg(String digest) throws IOException {return PackageReader.read(raw(digest),PackageReader.HARD_COMPRESSED,PackageReader.HARD_INFLATED);}
    public void pendingReason(String digest,String reason)throws IOException {
        Entry old=entry(digest);if(old==null||old.state()!=State.PENDING)return;
        Map<String,Entry> next=new LinkedHashMap<>(entries);next.put(digest,new Entry(old.digest(),old.app(),old.submitter(),old.name(),old.submitted(),old.state(),old.reviewer(),old.reviewed(),clean(reason,128),old.visibility()));save(next);
    }
    public byte[] raw(String digest) throws IOException {
        if(!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("摘要无效");
        Path file=root.resolve(digest+".mcphone");
        if(Files.isSymbolicLink(file)||Files.size(file)>StoreTransfer.MAX) throw new IOException("商店原包异常");
        byte[] raw=Files.readAllBytes(file);AppPackage p=PackageReader.read(raw,PackageReader.HARD_COMPRESSED,PackageReader.HARD_INFLATED);requireSignature(p);
        if(!p.digest().equals(digest)) throw new IOException("商店原包被修改");return raw;
    }
    public Entry entry(String digest) {return entries.get(digest);}
    public List<Entry> entries() {return List.copyOf(entries.values());}
    private void save(Map<String,Entry> next) throws IOException {
        JsonArray array=new JsonArray();for(Entry e:next.values()) {
            JsonObject o=new JsonObject();o.addProperty("digest",e.digest);o.addProperty("app",e.app);o.addProperty("submitter",e.submitter.toString());
            o.addProperty("name",e.name);o.addProperty("submitted",e.submitted);o.addProperty("state",e.state.name());
            if(e.reviewer==null)o.add("reviewer",JsonNull.INSTANCE);else o.addProperty("reviewer",e.reviewer.toString());
            o.addProperty("reviewed",e.reviewed);o.addProperty("reason",e.reason);o.addProperty("visibility",e.visibility);array.add(o);
        }
        byte[] bytes=array.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>262144)throw new IllegalStateException("目录元数据超额");
        atomic(root.resolve("index.json"),bytes);entries.clear();entries.putAll(next);
    }
    public static void requireSignature(AppPackage p) {
        if(!p.signed() || !SigManifest.parse(p.signature()).verify(p.digest()))throw new IllegalArgumentException("商店只接受有效作者签名");
    }
    public static String clean(String text,int limit) {return text.codePoints().filter(c->!Character.isISOControl(c)&&c!=0x2028&&c!=0x2029).limit(limit).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();}
    private static String visibility(String v) {if(!Set.of("public","licensed","hidden").contains(v))throw new IllegalArgumentException("可见性无效");return v;}
    public static void atomic(Path path,byte[] bytes) throws IOException {
        if(Files.isSymbolicLink(path)||Files.isSymbolicLink(path.getParent()))throw new IOException("不能写符号链接");
        Files.createDirectories(path.getParent());Path tmp=path.resolveSibling(path.getFileName()+".tmp");
        if(Files.isSymbolicLink(tmp)) throw new IOException("临时文件异常");
        try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
