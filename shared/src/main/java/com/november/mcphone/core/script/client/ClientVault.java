package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.server.StoreRepository;
import com.november.mcphone.core.script.server.store.*;
import net.minecraft.client.Minecraft;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 口令只进入有界本机加密线程。服务器只搬运密文；本地最高版本表按 serverId 与玩家 UUID 分桶。 */
public final class ClientVault {
    private static final String HEADER_APP="mcphone:vault",HEADER_KEY="header",HEADER_TEXT="MCphone vault format 1";
    private static final ThreadPoolExecutor CRYPTO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"mcphone-vault-crypto");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static VaultClient client;
    private static byte[] salt;
    private static long generation;
    private static UUID server,player;
    private static final Set<char[]> PASSWORDS=Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private ClientVault() { }
    public static boolean unlocked(){return client!=null&&client.unlocked()&&Objects.equals(server,ClientHandshake.serverId())&&Minecraft.getInstance().player!=null&&player.equals(Minecraft.getInstance().player.getUUID());}
    public static void lock(){generation++;if(client!=null)client.lock();client=null;if(salt!=null)Arrays.fill(salt,(byte)0);salt=null;server=null;player=null;
        synchronized(PASSWORDS){for(char[] password:PASSWORDS)VaultCrypto.wipe(password);PASSWORDS.clear();}}
    public static void unlock(char[] passphrase,Consumer<String> result){
        lock();long expected=generation;UUID targetServer=ClientHandshake.serverId();var mc=Minecraft.getInstance();
        if(targetServer==null||mc.player==null||ClientHandshake.connectionEpoch()==0){VaultCrypto.wipe(passphrase);result.accept("服务器保险箱不可用");return;}
        UUID targetPlayer=mc.player.getUUID();long epoch=ClientHandshake.connectionEpoch();PASSWORDS.add(passphrase);
        fetch(HEADER_APP,HEADER_KEY,record->{
            if(generation!=expected||epoch!=ClientHandshake.connectionEpoch()){VaultCrypto.wipe(passphrase);return;}
            byte[] useSalt=record==null?VaultCrypto.newSalt():record.salt();
            try {CRYPTO.execute(()->{
                VaultClient candidate=new VaultClient(targetServer.toString(),targetPlayer.toString());String failure=null;
                try {candidate.restoreVersions(load(targetServer,targetPlayer));candidate.unlock(passphrase,useSalt);
                    if(record==null&&candidate.versionsForLocal().containsKey(HEADER_APP+"\0"+HEADER_KEY))throw new IOException("保险箱头被删除或回滚");
                    if(record!=null&&!HEADER_TEXT.equals(candidate.get(HEADER_APP,HEADER_KEY,record)))throw new IllegalArgumentException("保险箱头记录无效");
                }catch(IOException|RuntimeException bad){failure="口令不正确、密文被修改，或本地版本记录不可用";candidate.lock();}
                finally{VaultCrypto.wipe(passphrase);PASSWORDS.remove(passphrase);}
                String error=failure;
                mc.execute(()->{
                    if(generation!=expected||epoch!=ClientHandshake.connectionEpoch()){candidate.lock();Arrays.fill(useSalt,(byte)0);return;}
                    if(error!=null){Arrays.fill(useSalt,(byte)0);result.accept(error);return;}
                    if(record==null){SealedRecord header=candidate.put(HEADER_APP,HEADER_KEY,HEADER_TEXT,useSalt);
                        send(HEADER_APP,HEADER_KEY,header,()->finish(candidate,useSalt,targetServer,targetPlayer,expected,result),message->{candidate.lock();Arrays.fill(useSalt,(byte)0);result.accept(message);});
                    }else finish(candidate,useSalt,targetServer,targetPlayer,expected,result);
                });
            });}catch(RejectedExecutionException full){VaultCrypto.wipe(passphrase);PASSWORDS.remove(passphrase);Arrays.fill(useSalt,(byte)0);result.accept("本机加密忙，请稍后再试");}
        },message->{VaultCrypto.wipe(passphrase);PASSWORDS.remove(passphrase);result.accept(message);});
    }
    private static void finish(VaultClient candidate,byte[] useSalt,UUID targetServer,UUID targetPlayer,long expected,Consumer<String> result){
        if(generation!=expected){candidate.lock();Arrays.fill(useSalt,(byte)0);return;}
        try {save(targetServer,targetPlayer,candidate.versionsForLocal());client=candidate;salt=useSalt;server=targetServer;player=targetPlayer;result.accept("保险箱已解锁；关闭手机后自动锁定");}
        catch(IOException|RuntimeException bad){candidate.lock();Arrays.fill(useSalt,(byte)0);result.accept("本地防回滚记录不能保存，保险箱保持锁定");}
    }
    public static void get(String app,String key,Consumer<String> success,Consumer<String> error){
        if(!unlocked()){error.accept("请先在设置中解锁保险箱");return;}long expected=generation;VaultClient current=client;
        fetch(app,key,record->{if(generation!=expected)return;try {
            if(record==null){if(current.versionsForLocal().containsKey(app+"\0"+key))throw new IllegalArgumentException("服务端记录比本地版本旧或被删除");success.accept("");return;}
            if(!Arrays.equals(salt,record.salt()))throw new IllegalArgumentException("保险箱盐值被替换");
            String plaintext=current.get(app,key,record);save(server,player,current.versionsForLocal());success.accept(plaintext);
        }catch(IOException|RuntimeException bad){error.accept("无法解密：口令、记录身份或最高版本不一致");}},error);
    }
    public static void put(String app,String key,String plaintext,Consumer<String> result){
        put(app,key,plaintext,(ok,message)->result.accept(message));
    }
    public static void put(String app,String key,String plaintext,java.util.function.BiConsumer<Boolean,String> result){
        if(!unlocked()){result.accept(false,"请先在设置中解锁保险箱");return;}
        if(plaintext.getBytes(StandardCharsets.UTF_8).length>2048){result.accept(false,"单条保险箱明文最多 2 KiB");return;}
        long expected=generation;VaultClient current=client;
        // 先读取当前密文以核对最高版本；新设备不能拿第1版覆盖服务端已经存在的记录。
        get(app,key,ignored->{if(generation!=expected)return;try {
            SealedRecord record=current.put(app,key,plaintext,salt);save(server,player,current.versionsForLocal());
            send(app,key,record,()->{if(generation==expected)result.accept(true,"密文已保存");},message->{if(generation==expected)result.accept(false,message+"；请查看版本状态后再试");});
        }catch(IOException|RuntimeException bad){result.accept(false,"密文或本地最高版本不能保存，写入已停止");}},message->result.accept(false,message));
    }
    private static void fetch(String app,String key,Consumer<SealedRecord> success,Consumer<String> error){JsonObject args=ClientStore.args("app",app);args.addProperty("key",key);
        ClientStore.rpc("vault.get",args,o->{var value=o.get("record");success.accept(value==null||value.isJsonNull()?null:DurablePlayerStore.decodeRecord(value.getAsJsonObject()));},error);}
    private static void send(String app,String key,SealedRecord record,Runnable success,Consumer<String> error){JsonObject args=ClientStore.args("app",app);args.addProperty("key",key);args.add("record",DurablePlayerStore.encodeRecord(record));
        ClientStore.rpc("vault.put",args,o->success.run(),error);}
    private static Path local(UUID server,UUID player){return Minecraft.getInstance().gameDirectory.toPath().resolve("mcphone/vault-versions").resolve(server+"-"+player+".json");}
    private static Map<String,Long> load(UUID server,UUID player)throws IOException {Path file=local(server,player);if(!Files.exists(file))return Map.of();
        if(Files.isSymbolicLink(file)||Files.size(file)>131072)throw new IOException("版本表异常");String json=Files.readString(file,StandardCharsets.UTF_8);if(JsonScan.check(json,2)!=null)throw new IOException("版本表损坏");
        JsonObject o=JsonParser.parseString(json).getAsJsonObject();if(o.size()>513)throw new IOException("版本记录超额");Map<String,Long> versions=new HashMap<>();
        for(var e:o.entrySet()){String[] parts=e.getKey().split("\0",-1);long version=e.getValue().getAsBigDecimal().longValueExact();if(parts.length!=2||!parts[0].matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!parts[1].matches("[A-Za-z0-9_.-]{1,64}")||version<1)throw new IOException("版本记录无效");versions.put(e.getKey(),version);}return versions;
    }
    private static void save(UUID server,UUID player,Map<String,Long> versions)throws IOException {if(versions.size()>513)throw new IOException("版本记录超额");JsonObject o=new JsonObject();versions.forEach((key,value)->o.addProperty(key,Long.toString(value)));byte[] bytes=o.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>131072)throw new IOException("版本表超额");StoreRepository.atomic(local(server,player),bytes);}
}
