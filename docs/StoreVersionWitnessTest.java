package com.november.mcphone.core.script.pkg;

import com.november.mcphone.core.script.server.*;
import java.nio.file.*;
import java.util.*;

/** 真实商店提交和审核必须共享持久化见证，而不是仅在单独的版本类里检查。 */
public final class StoreVersionWitnessTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static byte[] raw(AppPackage pkg)throws Exception {
        Map<String,byte[]> entries=new LinkedHashMap<>(pkg.entries());entries.put(PackageReader.SIG,pkg.signature());
        return PackageSignTest.zip(entries);
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("mcphone-store-witness");var key=Signatures.generate();byte[] pub=key.getPublic().getEncoded();
        VersionWitness witness=new VersionWitness(dir.resolve("versions.json"));StoreRepository store=new StoreRepository(dir.resolve("store")).witnesses(witness);
        UUID owner=UUID.randomUUID();var one=VersionWitnessTest.pkg(key,"example:demo",1,"one");var two=VersionWitnessTest.pkg(key,"example:demo",2,"two");
        store.submit(raw(one),owner,"sender",1);store.review(one.digest(),owner,true,"ok","public",2);
        store.submit(raw(two),owner,"sender",3);check(witness.approved("example:demo",pub)==1,"提交新版不能撤销正在运行的旧版");
        store.review(two.digest(),owner,true,"ok","public",4);check(witness.approved("example:demo",pub)==2,"实际审核抬高下限");
        try{store.review(one.digest(),owner,true,"rollback","public",5);throw new AssertionError("商店降级批准");}catch(IllegalArgumentException expected){checks++;}
        check(store.entry(one.digest()).reviewed()==2,"降级审批失败没有改写旧审批记录");
        var fork=VersionWitnessTest.pkg(key,"example:demo",1,"fork");
        try{store.submit(raw(fork),owner,"sender",6);throw new AssertionError("商店提交冲突包");}catch(IllegalArgumentException expected){checks++;}
        check(store.entry(fork.digest())==null,"冲突包不会进审核队列");
        check(new VersionWitness(dir.resolve("versions.json")).policy("example:demo",pub).rejected("example:demo",2,two.digest())!=null,"被拒提交仍留下作者冲突证据，阻止当前版本继续执行");
        System.out.println("StoreVersionWitnessTest: "+checks+" passed");
    }
}
