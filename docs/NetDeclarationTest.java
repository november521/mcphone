package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 域名用途受作者签名覆盖，任何包内容变动都会使旧许可失效。 */
public final class NetDeclarationTest {
    private static int checks;
    private static void check(boolean v,String why) { checks++; if(!v) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        var key=Signatures.generate(); var content=new LinkedHashMap<>(PackageSignTest.content());
        JsonObject manifest=JsonParser.parseString(new String(content.get("manifest.json"),StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.add("net",JsonParser.parseString("{\"hosts\":[\"api.example.com\"],\"why\":\"使用你自己的凭证\"}"));
        content.put("manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));
        var pkg=PackageSignTest.pkg(content,PackageSignTest.sigJson(key,PackageDigest.of(content),"author"));
        var net=NetDeclaration.of(pkg); check(net!=null && net.hosts().equals(Set.of("api.example.com")),"签名声明可读");
        check(net.why().equals("使用你自己的凭证"),"授权显示完整用途");
        check(NetDeclaration.of(PackageSignTest.pkg(content,null))==null,"未签名包不能申请网络许可");
        var changed=new LinkedHashMap<>(content); changed.put("app.vue","changed".getBytes(StandardCharsets.UTF_8));
        check(NetDeclaration.of(PackageSignTest.pkg(changed,pkg.signature()))==null,"改内容未重签不接受");
        var signedChanged=PackageSignTest.pkg(changed,PackageSignTest.sigJson(key,PackageDigest.of(changed),"author"));
        check(!NetDeclaration.of(signedChanged).consentKey().equals(net.consentKey()),"同钥内容更新也要求重新授权");
        for(String bad:List.of("{\"hosts\":[\"*.example.com\"],\"why\":\"x\"}","{\"hosts\":[\"api.example.com\",\"api.example.com\"],\"why\":\"x\"}",
                "{\"hosts\":[\"api.example.com\"],\"why\":\"\"}","{\"hosts\":[],\"why\":\"x\"}")) {
            manifest.add("net",JsonParser.parseString(bad)); content.put("manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));
            boolean rejected=false; try { NetDeclaration.of(PackageSignTest.pkg(content,PackageSignTest.sigJson(key,PackageDigest.of(content),"author"))); }
            catch(RuntimeException ex) { rejected=true; } check(rejected,"无效网络声明拒绝");
        }
        System.out.println("NetDeclarationTest: "+checks+" passed");
    }
}
