package com.november.mcphone.core.script.server;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;

/** 分片只是搬运字节；提交后必须再次解包与验签。连接、玩家、顺序和总量都由服务端约束。 */
public final class StoreTransfer {
    public static final int CHUNK=2048, MAX=1024*1024, MAX_ACTIVE=32, MAX_MEMORY=4*1024*1024;
    private final LongSupplier clock;
    private final Map<UUID,Session> sessions=new HashMap<>();
    private java.util.function.Supplier<QuotaConfig> quotas=()->QuotaConfig.DEFAULT;
    public StoreTransfer quotas(java.util.function.Supplier<QuotaConfig> config){quotas=config;return this;}
    private static final class Session {
        final String id,hash; final long epoch,started; final int size,chunk;
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        long touched; int next; byte[] last;
        Session(String id,long epoch,int size,String hash,long now,int chunk) {this.id=id;this.epoch=epoch;this.size=size;this.hash=hash;this.started=now;this.touched=now;this.chunk=chunk;}
    }
    public StoreTransfer(LongSupplier clock) {this.clock=clock;}
    public String begin(UUID player,long epoch,int size,String hash) {
        return begin(player,epoch,size,hash,CHUNK);
    }
    public String beginFile(UUID player,long epoch,int size,String hash) {
        return begin(player,epoch,size,hash,com.november.mcphone.core.script.net.StoreFileRequest.CHUNK);
    }
    private String begin(UUID player,long epoch,int size,String hash,int chunk) {
        sweep();
        if(epoch==0 || size<1 || size>Math.min(MAX,quotas.get().get("package.compressed")) || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("上传参数无效");
        if(sessions.containsKey(player)) throw new IllegalStateException("已有上传正在进行");
        if(sessions.size()>=MAX_ACTIVE || sessions.values().stream().mapToLong(s->s.size).sum()+size>quotas.get().get("upload.server")) throw new IllegalStateException("上传繁忙");
        String id=UUID.randomUUID().toString().replace("-","");
        sessions.put(player,new Session(id,epoch,size,hash,clock.getAsLong(),chunk)); return id;
    }
    public int chunk(UUID player,long epoch,String id,int index,byte[] bytes) {
        return chunk(player,epoch,id,index,bytes,CHUNK);
    }
    public int fileChunk(UUID player,long epoch,String id,int index,byte[] bytes) {
        return chunk(player,epoch,id,index,bytes,com.november.mcphone.core.script.net.StoreFileRequest.CHUNK);
    }
    private int chunk(UUID player,long epoch,String id,int index,byte[] bytes,int chunk) {
        Session s=find(player,epoch,id);if(s.chunk!=chunk)throw new IllegalArgumentException("上传通道不一致");int expected=Math.min(chunk,s.size-s.bytes.size());
        if(index==s.next-1 && s.last!=null && Arrays.equals(bytes,s.last)) {s.touched=clock.getAsLong();return s.next;}
        if(index!=s.next || bytes.length!=expected || expected==0) throw new IllegalArgumentException("分片顺序或长度无效");
        s.bytes.writeBytes(bytes);s.last=bytes.clone();s.next++;s.touched=clock.getAsLong();return s.next;
    }
    public byte[] commit(UUID player,long epoch,String id) {
        Session s=find(player,epoch,id);
        if(s.bytes.size()!=s.size) throw new IllegalArgumentException("上传未完成");
        byte[] raw=s.bytes.toByteArray(); sessions.remove(player);
        if(!sha(raw).equals(s.hash)) throw new IllegalArgumentException("上传摘要不匹配"); return raw;
    }
    public void cancel(UUID player) {sessions.remove(player);}
    public void sweep() {long now=clock.getAsLong();sessions.values().removeIf(s->now-s.touched>30_000 || now-s.started>60_000);}
    private Session find(UUID player,long epoch,String id) {
        sweep(); Session s=sessions.get(player);
        if(s==null || s.epoch!=epoch || !s.id.equals(id)) throw new IllegalArgumentException("上传已过期或不属于此连接"); return s;
    }
    public static String sha(byte[] raw) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
}
