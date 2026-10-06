package com.november.mcphone.core.script.pkg;

import com.november.mcphone.core.script.server.RevocationPolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 不信任 feed：改一个字、移到别的 App、换公钥、重复单值都不产生停用政策。 */
public final class AuthorRevocationTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[]args)throws Exception {
        var key=Signatures.generate();String app="t:market";AuthorRevocation statement=new AuthorRevocation(app,new RevocationPolicy.Rule(56,Set.of(57L,61L),Set.of("a".repeat(64)),"请升级"));String signature=Base64.getEncoder().encodeToString(statement.sign(key.getPrivate()));
        String xml="<rss xmlns:m='"+Appcast.NS+"'><channel><m:revocation><m:minVersion>56</m:minVersion><m:revoked>61</m:revoked><m:revoked>57</m:revoked><m:revokedDigest>sha256:"+"a".repeat(64)+"</m:revokedDigest><m:reason>请升级</m:reason></m:revocation><m:revocationSignature>"+signature+"</m:revocationSignature></channel></rss>";URI feed=URI.create("https://example.com/feed.xml");byte[] pub=key.getPublic().getEncoded();
        var valid=AuthorRevocation.parse(xml.getBytes(StandardCharsets.UTF_8),feed,app,pub);check(valid.isPresent()&&valid.get().rule().minVersion()==56,"正确签名");
        check(AuthorRevocation.parse(xml.replace(">56<",">55<").getBytes(StandardCharsets.UTF_8),feed,app,pub).isEmpty(),"改一个字无效");check(AuthorRevocation.parse(xml.getBytes(StandardCharsets.UTF_8),feed,"t:other",pub).isEmpty(),"App 域绑定");check(AuthorRevocation.parse(xml.getBytes(StandardCharsets.UTF_8),feed,app,Signatures.generate().getPublic().getEncoded()).isEmpty(),"完整公钥身份");
        check(AuthorRevocation.parse(xml.replace("<m:minVersion>","<m:minVersion>56</m:minVersion><m:minVersion>").getBytes(StandardCharsets.UTF_8),feed,app,pub).isEmpty(),"拒绝重复单值");
        RevocationPolicy local=new RevocationPolicy(Map.of(app,new RevocationPolicy.Rule(60,Set.of(62L),Set.of(),"服主")));RevocationPolicy union=local.union(new RevocationPolicy(Map.of(app,valid.orElseThrow().rule())));check(union.rejected(app,59,"b".repeat(64)).minVersion()==60,"更严下限取 60");check(union.rejected(app,61,"b".repeat(64))!=null&&union.rejected(app,62,"b".repeat(64))!=null,"吊销集合取并集");check(union.rejected(app,63,"a".repeat(64))!=null,"摘要吊销");check(union.rejected(app,63,"b".repeat(64))==null,"不影响修复版");
        System.out.println("AuthorRevocationTest: "+checks+" passed");
    }
}
