package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.server.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.*;

/** 保存可重验的作者签名证据。身份绑定 App 与完整公钥；旧 feed 不能消除已采纳的吊销。 */
public final class AuthorRevocationCache {
    private static final int MAX_BYTES=4*1024*1024;
    private static final Object IO_LOCK=new Object();
    private final Path file;
    private Map<String,List<AuthorRevocation.Evidence>> evidence=new LinkedHashMap<>();
    public AuthorRevocationCache(Path file)throws IOException {
        this.file=file.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(this.file))throw new IOException("吊销缓存不能是链接");
        if(Files.notExists(this.file))return;
        if(Files.size(this.file)>MAX_BYTES)throw new IOException("吊销缓存超额");
        try {String raw=Files.readString(this.file,StandardCharsets.UTF_8);if(JsonScan.check(raw,8)!=null)throw new IllegalArgumentException();JsonArray rows=JsonParser.parseString(raw).getAsJsonArray();if(rows.size()>128)throw new IllegalArgumentException();
            for(JsonElement element:rows){JsonObject row=element.getAsJsonObject();String app=row.get("app").getAsString();byte[] key=Base64.getDecoder().decode(row.get("publicKey").getAsString());Signatures.publicKey(key);List<AuthorRevocation.Evidence> list=new ArrayList<>();JsonArray declarations=row.getAsJsonArray("evidence");if(declarations.size()>64)throw new IllegalArgumentException();
                for(JsonElement item:declarations){JsonObject o=item.getAsJsonObject();Set<Long> versions=new HashSet<>();Set<String> digests=new HashSet<>();for(JsonElement v:o.getAsJsonArray("versions"))versions.add(Long.parseLong(v.getAsString()));for(JsonElement d:o.getAsJsonArray("digests"))digests.add(d.getAsString());var statement=new AuthorRevocation(app,new RevocationPolicy.Rule(Long.parseLong(o.get("min").getAsString()),versions,digests,o.get("reason").getAsString()));byte[] signature=Base64.getDecoder().decode(o.get("signature").getAsString());if(!statement.verify(key,signature))throw new IllegalArgumentException("缓存签名无效");list.add(new AuthorRevocation.Evidence(statement,signature));}
                if(evidence.putIfAbsent(identity(app,key),List.copyOf(list))!=null)throw new IllegalArgumentException("重复缓存身份");
            }
        }catch(RuntimeException|GeneralSecurityException bad){throw new IOException("吊销缓存损坏，禁止使用空策略覆盖",bad);}
    }
    private static String identity(String app,byte[] key){return app+"|"+Base64.getEncoder().encodeToString(key);}
    public synchronized RevocationPolicy policy(String app,byte[] key){RevocationPolicy result=RevocationPolicy.NONE;for(var item:evidence.getOrDefault(identity(app,key),List.of()))result=result.union(new RevocationPolicy(Map.of(app,item.statement().rule())));return result;}
    public synchronized boolean accept(byte[] key,AuthorRevocation.Evidence incoming)throws IOException {
        synchronized(IO_LOCK){evidence=new AuthorRevocationCache(file).evidence;return acceptCurrent(key,incoming);}
    }
    private boolean acceptCurrent(byte[] key,AuthorRevocation.Evidence incoming)throws IOException {
        try {if(!incoming.statement().verify(key,incoming.signature()))throw new IllegalArgumentException("吊销证据签名无效");}catch(GeneralSecurityException bad){throw new IllegalArgumentException("吊销公钥无效",bad);}
        String id=identity(incoming.statement().app(),key);List<AuthorRevocation.Evidence> prior=evidence.getOrDefault(id,List.of());
        if(prior.stream().anyMatch(p->covers(p.statement().rule(),incoming.statement().rule())))return false;
        List<AuthorRevocation.Evidence> list=new ArrayList<>(prior);list.removeIf(p->covers(incoming.statement().rule(),p.statement().rule()));list.add(incoming);
        if(list.size()>64||!evidence.containsKey(id)&&evidence.size()>=128)throw new IOException("吊销证据数量已满");
        Map<String,List<AuthorRevocation.Evidence>> next=new LinkedHashMap<>(evidence);next.put(id,List.copyOf(list));byte[] bytes=encode(next);
        if(bytes.length>MAX_BYTES)throw new IOException("吊销证据超过 4 MiB");if(Files.isSymbolicLink(file))throw new IOException("吊销缓存不能是链接");ScriptStateData.atomicWrite(file,bytes);evidence=next;return true;
    }
    private static boolean covers(RevocationPolicy.Rule a,RevocationPolicy.Rule b){return a.minVersion()>=b.minVersion()&&a.versions().containsAll(b.versions())&&a.digests().containsAll(b.digests());}
    private static byte[] encode(Map<String,List<AuthorRevocation.Evidence>> values){JsonArray rows=new JsonArray();values.forEach((id,list)->{int split=id.indexOf('|');JsonObject row=new JsonObject();row.addProperty("app",id.substring(0,split));row.addProperty("publicKey",id.substring(split+1));JsonArray entries=new JsonArray();for(var item:list){var rule=item.statement().rule();JsonObject o=new JsonObject();o.addProperty("min",Long.toString(rule.minVersion()));o.addProperty("reason",rule.reason());JsonArray versions=new JsonArray(),digests=new JsonArray();rule.versions().stream().sorted().forEach(v->versions.add(Long.toString(v)));rule.digests().stream().sorted().forEach(digests::add);o.add("versions",versions);o.add("digests",digests);o.addProperty("signature",Base64.getEncoder().encodeToString(item.signature()));entries.add(o);}row.add("evidence",entries);rows.add(row);});return rows.toString().getBytes(StandardCharsets.UTF_8);}
}
