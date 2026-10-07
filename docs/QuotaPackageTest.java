package com.november.mcphone.core.script.pkg;

import com.november.mcphone.core.script.server.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.november.mcphone.core.script.pkg.PackageSignTest.*;

/** 包限额可提高，缩小限额不删除已有原包；固定 ZIP 防线仍成立。 */
public final class QuotaPackageTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable run,String why){boolean denied=false;try{run.run();}catch(RuntimeException bad){denied=true;}check(denied,why);}
    public static void main(String[] args)throws Exception {
        var key=Signatures.generate();Map<String,byte[]> content=content();Random random=new Random(2);
        for(int f=0;f<3;f++){byte[] text=new byte[128*1024];for(int i=0;i<text.length;i++)text[i]=(byte)('a'+random.nextInt(26));content.put("help"+f+".txt",text);}content.put("META/sig.json",sigJson(key,PackageDigest.of(content),"tester"));byte[] raw=zip(content);
        check(raw.length>65536&&raw.length<PackageReader.HARD_COMPRESSED,"有效的大包");reject(()->PackageReader.read(raw,65536,PackageReader.HARD_INFLATED),"压缩限额");reject(()->PackageReader.read(raw,PackageReader.HARD_COMPRESSED,262144),"解压限额");
        var pkg=PackageReader.read(raw,PackageReader.HARD_COMPRESSED,PackageReader.HARD_INFLATED);check(SigManifest.parse(pkg.signature()).verify(pkg.digest()),"提高限额仍验作者签名");check(FrontendEnvelope.read(FrontendEnvelope.write(pkg)).digest().equals(pkg.digest()),"大包前端保留作者证明");
        Path dir=Files.createTempDirectory("mcphone-large-package");AtomicReference<QuotaConfig> q=new AtomicReference<>(QuotaConfig.DEFAULT.with("package.compressed",PackageReader.HARD_COMPRESSED).with("package.expanded",PackageReader.HARD_INFLATED));StoreRepository store=new StoreRepository(dir).quotas(q::get);UUID player=UUID.randomUUID();String digest=store.submit(raw,player,"tester",1).digest();q.set(q.get().with("package.compressed",65536));check(new StoreRepository(dir).pkg(digest).digest().equals(digest),"缩小配额仍可读取旧包");
        StoreTransfer transfers=new StoreTransfer(()->0).quotas(q::get);reject(()->transfers.begin(player,1,65537,"0".repeat(64)),"新上传服主限额生效");q.set(q.get().with("package.compressed",1048576).with("upload.server",1048576));transfers.begin(player,1,700000,"0".repeat(64));reject(()->transfers.begin(UUID.randomUUID(),1,400000,"0".repeat(64)),"上传按会话预留量计数");
        content.put("too.txt",new byte[PackageReader.MAX_ENTRY_INFLATED+1]);byte[] oversized=zip(content);reject(()->PackageReader.read(oversized,PackageReader.HARD_COMPRESSED,PackageReader.HARD_INFLATED),"服主配额不能打开单条目炸弹防线");
        System.out.println("QuotaPackageTest: "+checks+" passed");
    }
}
