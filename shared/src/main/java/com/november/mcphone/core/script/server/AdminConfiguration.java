package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 管理页只写固定的三个配置。差异预览和确认使用同一快照；崩溃恢复按持久事务统一完成或回滚。 */
public final class AdminConfiguration {
    public static final List<String> FILES=List.of(CapabilityConfig.FILE,ScriptRuntimeConfig.FILE,QuotaManager.FILE);
    public static final String JOURNAL="mcphone/admin/config-transaction.json";
    public record Snapshot(Map<String,JsonObject> files,String revision){
        public Snapshot{Map<String,JsonObject> copy=new LinkedHashMap<>();files.forEach((k,v)->copy.put(k,v.deepCopy()));files=Collections.unmodifiableMap(copy);}
        public JsonObject file(String name){return files.get(name).deepCopy();}
    }
    private final Path world;
    private final AuditLog audit;
    public AdminConfiguration(Path world,AuditLog audit){this.world=world.toAbsolutePath().normalize();this.audit=audit;}
    public Snapshot read()throws IOException {
        Map<String,JsonObject> files=new LinkedHashMap<>();
        for(String name:FILES){Path p=world.resolve(name);if(Files.isSymbolicLink(p)||Files.size(p)>65536)throw new IOException("管理配置路径或大小无效");String raw=Files.readString(p,StandardCharsets.UTF_8);if(JsonScan.check(raw,8)!=null)throw new IOException("管理配置结构无效");files.put(name,JsonParser.parseString(raw).getAsJsonObject());}
        return checked(files);
    }
    private static Snapshot checked(Map<String,JsonObject> files){
        if(!files.keySet().equals(new HashSet<>(FILES)))throw new IllegalArgumentException("事务文件集合无效");
        for(JsonObject f:files.values())if(f.toString().getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("管理配置超额");
        if(!CapabilityConfig.parse(files.get(CapabilityConfig.FILE).toString()).loadError().isEmpty())throw new IllegalArgumentException("能力配置无效");
        ScriptRuntimeConfig.parse(files.get(ScriptRuntimeConfig.FILE).toString());QuotaConfig.parse(files.get(QuotaManager.FILE).toString());
        try{MessageDigest hash=MessageDigest.getInstance("SHA-256");for(String name:FILES){byte[] raw=files.get(name).toString().getBytes(StandardCharsets.UTF_8);hash.update(name.getBytes(StandardCharsets.UTF_8));hash.update((byte)0);hash.update(raw);hash.update((byte)0);}return new Snapshot(files,HexFormat.of().formatHex(hash.digest()));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static Snapshot change(Snapshot before,JsonObject operation){
        Map<String,JsonObject> files=new LinkedHashMap<>();before.files().forEach((k,v)->files.put(k,v.deepCopy()));
        JsonObject caps=files.get(CapabilityConfig.FILE),runtime=files.get(ScriptRuntimeConfig.FILE),limits=files.get(QuotaManager.FILE).getAsJsonObject("limits");
        String kind=operation.get("kind").getAsString();
        switch(kind){
            case "preset"->{exact(operation,"kind","value");String preset=operation.get("value").getAsString();if(!Set.of("hardcore","standard","open").contains(preset))throw new IllegalArgumentException("预设无效");boolean hard=preset.equals("hardcore"),open=preset.equals("open");
                caps.addProperty("preset",preset);JsonArray disabled=new JsonArray();if(!open)for(String id:List.of("resource.read.block","resource.move","container.read"))disabled.add(id);if(hard)disabled.add("trade.escrow");caps.add("disabled",disabled);
                JsonObject boundary=new JsonObject();for(String key:BOUNDARIES)boundary.addProperty(key,open&&Set.of("resource_move","remote_machine_read").contains(key));caps.add("boundary",boundary);
                limits.addProperty("escrow.per_player",hard?0:open?54:27);limits.addProperty("mailbox.per_player",hard?9:27);limits.addProperty("kv.per_player_app",hard?4096:open?16384:8192);limits.addProperty("apps.per_player",hard?16:open?64:32);
                JsonObject net=runtime.has("net")?runtime.getAsJsonObject("net"):new JsonObject();net.addProperty("server_fetch",open);net.addProperty("allow_client_fetch",!hard);runtime.add("net",net);runtime.addProperty("server_scripts",false);runtime.addProperty("cross_server_trust",false);
            }
            case "capability"->{exact(operation,"kind","id","enabled");String id=operation.get("id").getAsString();if(!CapabilityCatalog.knownDeclared(id))throw new IllegalArgumentException("能力不在目录");boolean enabled=bool(operation,"enabled");Set<String> values=new LinkedHashSet<>();if(caps.has("disabled"))for(JsonElement e:caps.getAsJsonArray("disabled"))values.add(e.getAsString());if(enabled)values.remove(id);else values.add(id);JsonArray array=new JsonArray();values.forEach(array::add);caps.add("disabled",array);}
            case "boundary"->{exact(operation,"kind","id","enabled");String id=operation.get("id").getAsString();if(!BOUNDARIES.contains(id))throw new IllegalArgumentException("边界不在目录");JsonObject boundary=caps.has("boundary")?caps.getAsJsonObject("boundary"):new JsonObject();boundary.addProperty(id,bool(operation,"enabled"));caps.add("boundary",boundary);}
            case "scripting"->{exact(operation,"kind","enabled");runtime.addProperty("server_scripts",bool(operation,"enabled"));}
            case "network"->{exact(operation,"kind","server","client");JsonObject net=runtime.has("net")?runtime.getAsJsonObject("net"):new JsonObject();net.addProperty("server_fetch",bool(operation,"server"));net.addProperty("allow_client_fetch",bool(operation,"client"));runtime.add("net",net);}
            case "author"->{exact(operation,"kind","key","blocked");String key=operation.get("key").getAsString();byte[] bytes=Base64.getDecoder().decode(key);com.november.mcphone.core.script.pkg.Signatures.publicKey(bytes);if(!Base64.getEncoder().encodeToString(bytes).equals(key))throw new IllegalArgumentException("作者完整公钥无效");Set<String> keys=new LinkedHashSet<>();if(runtime.has("blocked_authors"))for(JsonElement e:runtime.getAsJsonArray("blocked_authors"))keys.add(e.getAsString());if(bool(operation,"blocked"))keys.add(key);else keys.remove(key);JsonArray array=new JsonArray();keys.forEach(array::add);runtime.add("blocked_authors",array);}
            case "authorUpdates"->{exact(operation,"kind","app","enabled");String app=operation.get("app").getAsString();JsonArray policies=runtime.has("app_policy")?runtime.getAsJsonArray("app_policy"):new JsonArray();JsonObject row=null;for(JsonElement e:policies)if(e.getAsJsonObject().get("id").getAsString().equals(app))row=e.getAsJsonObject();if(row==null){row=new JsonObject();row.addProperty("id",app);policies.add(row);}row.addProperty("follow_author_updates",bool(operation,"enabled"));runtime.add("app_policy",policies);}
            case "appPolicy"->{exact(operation,"kind","app","minimum","revokedVersion","digest","followAuthor","frontendUpdate","reason");String app=operation.get("app").getAsString();JsonArray policies=runtime.has("app_policy")?runtime.getAsJsonArray("app_policy"):new JsonArray();JsonObject row=null;for(JsonElement e:policies)if(e.getAsJsonObject().get("id").getAsString().equals(app))row=e.getAsJsonObject();if(row==null){row=new JsonObject();row.addProperty("id",app);policies.add(row);}row.addProperty("min_version",new java.math.BigDecimal(operation.get("minimum").getAsString()).longValueExact());
                String revoked=operation.get("revokedVersion").getAsString();if(!revoked.isEmpty()){long version=new java.math.BigDecimal(revoked).longValueExact();JsonArray versions=row.has("revoked_versions")?row.getAsJsonArray("revoked_versions"):new JsonArray();if(!versions.contains(new JsonPrimitive(version)))versions.add(version);row.add("revoked_versions",versions);}
                String digest=operation.get("digest").getAsString();if(!digest.isEmpty()){if(!Deployment.validDigest(digest))throw new IllegalArgumentException("吊销摘要无效");JsonArray digests=row.has("revoked_digests")?row.getAsJsonArray("revoked_digests"):new JsonArray();if(!digests.contains(new JsonPrimitive(digest)))digests.add(digest);row.add("revoked_digests",digests);}row.addProperty("follow_author_revocations",bool(operation,"followAuthor"));row.addProperty("frontend_update",operation.get("frontendUpdate").getAsString());row.addProperty("reason",operation.get("reason").getAsString());runtime.add("app_policy",policies);
            }
            default->throw new IllegalArgumentException("管理写入类型无效");
        }
        return checked(files);
    }
    public static final List<String> BOUNDARIES=List.of("resource_move","cross_dimension_transfer","escrow_freezes_decay","remote_machine_read","remote_machine_operate","store_living_entities");
    private static boolean bool(JsonObject args,String key){if(!args.get(key).isJsonPrimitive()||!args.getAsJsonPrimitive(key).isBoolean())throw new IllegalArgumentException("开关必须是布尔值");return args.get(key).getAsBoolean();}
    public static void exact(JsonObject args,String...fields){if(!args.keySet().equals(Set.of(fields)))throw new IllegalArgumentException("管理字段无效");}
    public static List<String> differences(Snapshot before,Snapshot after){List<String> out=new ArrayList<>();for(String name:FILES)diff(name,before.files().get(name),after.files().get(name),out);return List.copyOf(out);}
    private static void diff(String key,JsonElement before,JsonElement after,List<String> out){
        if(Objects.equals(before,after))return;
        if((before!=null&&before.isJsonObject())||(after!=null&&after.isJsonObject())){
            JsonObject a=before!=null&&before.isJsonObject()?before.getAsJsonObject():new JsonObject(),b=after!=null&&after.isJsonObject()?after.getAsJsonObject():new JsonObject();
            Set<String> keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());for(String child:keys)if(!child.startsWith("_"))diff(key+"/"+child,a.get(child),b.get(child),out);
        }else if((before!=null&&before.isJsonArray())||(after!=null&&after.isJsonArray())){
            JsonArray a=before!=null&&before.isJsonArray()?before.getAsJsonArray():new JsonArray(),b=after!=null&&after.isJsonArray()?after.getAsJsonArray():new JsonArray();
            for(int i=0;i<Math.max(a.size(),b.size());i++)diff(key+"["+i+"]",i<a.size()?a.get(i):null,i<b.size()?b.get(i):null,out);
        }else addLines(out,key+": "+display(before)+" → "+display(after));
    }
    /** 按 UTF-8 字节分页完整内容；四行连同响应字段一定装得进普通 4 KiB RPC。 */
    public static void addLines(List<String> out,String value){StringBuilder part=new StringBuilder();int bytes=0;for(int i=0;i<value.length();){int cp=value.codePointAt(i);String text=new String(Character.toChars(cp));int length=new JsonPrimitive(text).toString().getBytes(StandardCharsets.UTF_8).length-2;if(bytes+length>700){out.add(part.toString());part.setLength(0);bytes=0;}part.append(text);bytes+=length;i+=Character.charCount(cp);}if(!part.isEmpty())out.add(part.toString());}
    private static String display(JsonElement value){return value==null?"未设置":value.toString();}
    public synchronized void commit(UUID actor,Snapshot before,Snapshot after)throws IOException {
        if(!read().revision().equals(before.revision()))throw new IllegalArgumentException("配置已改变，请重新预览");checked(after.files());
        audit.append(actor,"admin.config",after.revision(),new Gson().toJsonTree(before.files()),new Gson().toJsonTree(after.files()));
        JsonObject transaction=new JsonObject();transaction.addProperty("format",1);transaction.addProperty("committed",false);transaction.add("before",new Gson().toJsonTree(before.files()));transaction.add("after",new Gson().toJsonTree(after.files()));
        write(world.resolve(JOURNAL),transaction);try{writeFiles(world,after.files());transaction.addProperty("committed",true);write(world.resolve(JOURNAL),transaction);Files.delete(world.resolve(JOURNAL));}
        catch(IOException|RuntimeException bad){transaction.addProperty("committed",false);try{writeFiles(world,before.files());write(world.resolve(JOURNAL),transaction);}catch(Exception rollback){bad.addSuppressed(rollback);}throw bad;}
    }
    public static void recover(Path root)throws IOException {
        Path world=root.toAbsolutePath().normalize(),file=world.resolve(JOURNAL);if(Files.notExists(file))return;
        if(Files.isSymbolicLink(file)||Files.size(file)>512*1024)throw new IOException("管理事务日志路径或大小无效");String raw=Files.readString(file,StandardCharsets.UTF_8);if(JsonScan.check(raw,10)!=null)throw new IOException("管理事务日志损坏");
        try{JsonObject transaction=JsonParser.parseString(raw).getAsJsonObject();if(!transaction.keySet().equals(Set.of("format","committed","before","after"))||transaction.get("format").getAsBigDecimal().intValueExact()!=1)throw new IllegalArgumentException("事务版本无效");Map<String,JsonObject> files=new LinkedHashMap<>();JsonObject selected=transaction.getAsJsonObject(bool(transaction,"committed")?"after":"before");selected.entrySet().forEach(e->files.put(e.getKey(),e.getValue().getAsJsonObject()));checked(files);writeFiles(world,files);Files.delete(file);}
        catch(RuntimeException bad){throw new IOException("管理事务恢复被拒绝",bad);}
    }
    private static void writeFiles(Path world,Map<String,JsonObject> files)throws IOException {for(String name:FILES)write(world.resolve(name),files.get(name));}
    private static void write(Path file,JsonObject value)throws IOException {if(Files.isSymbolicLink(file))throw new IOException("管理配置不能是链接");ScriptStateData.atomicWrite(file,value.toString().getBytes(StandardCharsets.UTF_8));}
}
