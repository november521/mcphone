package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.server.VersionWitness;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPair;
import java.util.*;

/** 版本下限只由批准抬高，所有已验签版本的冲突证据独立保留并按完整公钥隔离。 */
public final class VersionWitnessTest {
    private static int checks;private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    public static AppPackage pkg(KeyPair key,String app,long version,String marker)throws Exception {
        Map<String,byte[]> entries=PackageSignTest.content();entries.put("app.vue","<template>\n<text text=\"ok\"/>\n</template>\n".getBytes(StandardCharsets.UTF_8));JsonObject manifest=JsonParser.parseString(new String(entries.get("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();manifest.addProperty("id",app);manifest.addProperty("versionCode",Long.toString(version));manifest.addProperty("description",marker);entries.put("manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));String digest=PackageDigest.of(entries);entries.put(PackageReader.SIG,PackageSignTest.sigJson(key,digest,"author"));return PackageReader.read(PackageSignTest.zip(entries));
    }
    public static void main(String[]args)throws Exception {
        Path path=Files.createTempDirectory("mcphone-version-witness").resolve("witness.json");var key=Signatures.generate();var one=pkg(key,"example:demo",1,"one");var two=pkg(key,"example:demo",2,"two");VersionWitness witness=new VersionWitness(path);
        check(witness.observe(one,false),"有效签名候选可见证");check(witness.approved("example:demo",key.getPublic().getEncoded())==0,"提交不抬高批准版本下限");check(witness.observe(one,true),"第一版批准");
        check(witness.observe(two,false)&&witness.approved("example:demo",key.getPublic().getEncoded())==1,"新候选不会撤销仍在运行的已批准旧版");check(witness.observe(two,true),"批准新版");check(!witness.observe(one,true),"已批准最高版本后禁止回退");
        check(witness.policy("example:demo",key.getPublic().getEncoded()).rejected("example:demo",1,one.digest())!=null,"实际策略拒绝低于批准下限的包");
        var fork=pkg(key,"example:demo",1,"fork");check(!witness.observe(fork,false),"低于最高版本的旧版本仍保留冲突见证");check(witness.policy("example:demo",key.getPublic().getEncoded()).rejected("example:demo",Long.MAX_VALUE,two.digest())!=null,"同版本两份作者签名触发全部版本停用");
        VersionWitness reopened=new VersionWitness(path);check(reopened.policy("example:demo",key.getPublic().getEncoded()).rejected("example:demo",2,two.digest())!=null,"冲突重启后不能清除");
        var replacement=Signatures.generate();check(reopened.observe(pkg(replacement,"example:demo",1,"replacement"),true),"新完整公钥独立审批");check(reopened.policy("example:demo",replacement.getPublic().getEncoded()).rejected("example:demo",1,one.digest())==null,"旧作者冲突不会错禁其它完整公钥");
        VersionWitness first=new VersionWitness(path),second=new VersionWitness(path);check(first.observe(pkg(key,"example:a",7,"a"),true),"第一实例保存新 App");check(second.observe(pkg(key,"example:b",8,"b"),true),"第二实例基于最新磁盘快照保存");VersionWitness both=new VersionWitness(path);check(both.approved("example:a",key.getPublic().getEncoded())==7&&both.approved("example:b",key.getPublic().getEncoded())==8,"两个实例不能覆盖丢失见证");
        Files.writeString(path,"[]invalid");try{new VersionWitness(path);throw new AssertionError("损坏缓存静默初始化为空");}catch(java.io.IOException expected){checks++;}
        try{both.reload();throw new AssertionError("重载损坏缓存放行");}catch(java.io.IOException expected){checks++;}
        check(both.policy("example:a",key.getPublic().getEncoded()).rejected("example:a",7,one.digest())!=null,"运行中读档失败拒绝继续执行");
        Files.writeString(path,"[]");both.reload();check(both.policy("example:a",key.getPublic().getEncoded()).rejected("example:a",7,one.digest())!=null,"失败锁不能被空文件重载清除");
        try{both.observe(one,true);throw new AssertionError("失败锁之后仍批准");}catch(java.io.IOException expected){checks++;}
        Path manualPath=Files.createTempDirectory("mcphone-manual-version").resolve("versions.json");VersionWitness manual=new VersionWitness(manualPath);manual.require(two,true);manual.approveManual(one);
        check(manual.approved("example:demo",key.getPublic().getEncoded())==2,"人工降级不降低自动更新最高版本");check(manual.manualAllowed("example:demo",key.getPublic().getEncoded(),1,one.digest()),"人工只批准确切摘要");
        check(!manual.manualAllowed("example:other",key.getPublic().getEncoded(),1,one.digest())&&!manual.manualAllowed("example:demo",replacement.getPublic().getEncoded(),1,one.digest()),"人工例外不能串 App 或完整公钥");
        check(new VersionWitness(manualPath).manualAllowed("example:demo",key.getPublic().getEncoded(),1,one.digest()),"明确降级重启后仍能恢复");check(!manual.observe(one,true),"人工例外不改变自动准入");manual.require(two,true);check(!manual.manualAllowed("example:demo",key.getPublic().getEncoded(),1,one.digest()),"恢复新版清除旧摘要例外");
        manual.observe(fork,false);try{manual.approveManual(one);throw new AssertionError("人工确认绕过作者同版本分叉");}catch(IllegalArgumentException expected){checks++;}check(!manual.manualAllowed("example:demo",key.getPublic().getEncoded(),1,one.digest()),"冲突立即停止人工例外");
        var follow=com.november.mcphone.core.script.server.ScriptRuntimeConfig.parse("{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"app_policy\":[{\"id\":\"example:demo\",\"follow_author_updates\":true}]}");check(follow.followsUpdates("example:demo")&&!follow.followsUpdates("example:other"),"作者更新按 App 显式开启，默认关闭");
        System.out.println("VersionWitnessTest: "+checks+" passed");
    }
}
