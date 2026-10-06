package com.november.mcphone.core.script.pkg;

import com.november.mcphone.core.script.server.*;
import java.nio.file.*;
import java.util.*;

/** 覆盖重启、历史 feed 重放、换钥隔离、损坏签名与两个实例同时保存。 */
public final class AuthorRevocationCacheTest {
    private static int checks;private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static AuthorRevocation.Evidence evidence(String app,long min,Set<Long> versions,java.security.KeyPair key)throws Exception {var statement=new AuthorRevocation(app,new RevocationPolicy.Rule(min,versions,Set.of(),"升级"));return new AuthorRevocation.Evidence(statement,statement.sign(key.getPrivate()));}
    public static void main(String[]args)throws Exception {Path dir=Files.createTempDirectory("mcphone-revocation-test");Path file=dir.resolve("cache.json");var key=Signatures.generate();byte[] pub=key.getPublic().getEncoded();var cache=new AuthorRevocationCache(file);
        check(cache.accept(pub,evidence("t:a",56,Set.of(57L),key)),"采纳有效签名");check(new AuthorRevocationCache(file).policy("t:a",pub).rejected("t:a",55,"")!=null,"重启仍拒绝旧版");check(!cache.accept(pub,evidence("t:a",1,Set.of(),key)),"旧 feed 不放宽下限");
        check(cache.accept(pub,evidence("t:a",60,Set.of(61L),key)),"累计新策略");var union=cache.policy("t:a",pub);check(union.rejected("t:a",57,"")!=null&&union.rejected("t:a",61,"")!=null,"累计版本并集");check(union.rejected("t:a",62,"")==null,"修复版仍可用");check(cache.policy("t:a",Signatures.generate().getPublic().getEncoded()).rejected("t:a",0,"")==null,"完整公钥隔离");
        var a=new AuthorRevocationCache(file);var b=new AuthorRevocationCache(file);a.accept(pub,evidence("t:b",2,Set.of(),key));b.accept(pub,evidence("t:c",3,Set.of(),key));check(new AuthorRevocationCache(file).policy("t:b",pub).rejected("t:b",1,"")!=null,"实例并发保留另一 App 的写入");
        var valid=evidence("t:d",2,Set.of(),key);byte[] sig=valid.signature();sig[0]^=1;try{cache.accept(pub,new AuthorRevocation.Evidence(valid.statement(),sig));throw new AssertionError("改坏签名被接受");}catch(IllegalArgumentException expected){checks++;}
        Files.writeString(file,Files.readString(file).replace("升级","被改动"));try{new AuthorRevocationCache(file);throw new AssertionError("损坏缓存退回空策略");}catch(java.io.IOException expected){checks++;}
        var config=ScriptRuntimeConfig.parse("{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"app_policy\":[{\"id\":\"t:a\",\"follow_author_revocations\":false}]}");check(!config.followsAuthor("t:a")&&config.followsAuthor("t:b"),"跟随默认开，服主可按 App 关闭");
        System.out.println("AuthorRevocationCacheTest: "+checks+" passed");
    }
}
