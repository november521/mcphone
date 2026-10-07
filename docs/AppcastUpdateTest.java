package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.server.RevocationPolicy;
import java.net.URI;
import java.security.*;
import java.util.*;
import static com.november.mcphone.core.script.pkg.PackageSignTest.*;

/** XXE、注册域、防重放以及 ZIP 外签名与包内作者链。 */
public final class AppcastUpdateTest {
    private static int count;
    private static void require(boolean value) { count++; if (!value) throw new AssertionError("更新闸 #" + count); }
    private static void reject(Throwing body) throws Exception { boolean denied=false; try { body.run(); } catch (Exception expected) { denied=true; } require(denied); }
    private static byte[] signed(KeyPair pair, long version, String capability) throws Exception {
        Map<String,byte[]> c = content(); JsonObject m=JsonParser.parseString(MANIFEST).getAsJsonObject(); m.addProperty("versionCode", version);
        if (capability != null) { JsonArray caps=new JsonArray(); caps.add(capability); m.add("capabilities", caps); }
        c.put("manifest.json",bytes(m.toString())); c.put("META/sig.json",sigJson(pair,PackageDigest.of(c),"author")); return zip(c);
    }
    private static Appcast.Release release(byte[] zip, KeyPair signer, long version) throws Exception {
        Signature s=Signature.getInstance("Ed25519"); s.initSign(signer.getPrivate()); s.update(zip);
        return new Appcast.Release(URI.create("https://example.com/app.zip"),version,"1.0.0",1,"stable",zip.length,s.sign());
    }
    private static String feed(String download) {
        return "<rss xmlns:m='"+Appcast.NS+"'><channel><item><enclosure url='"+download+"' m:version='2' m:shortVersionString='1.0.0' m:edSignature='"+Base64.getEncoder().encodeToString(new byte[64])+"' length='123' type='application/zip'/></item></channel></rss>";
    }
    public static void main(String[] args) throws Exception {
        URI f=URI.create("https://updates.example.co.uk/feed.xml");
        require(Appcast.parse(bytes(feed("https://cdn.example.co.uk/app.zip")),f).size()==1);
        reject(() -> Appcast.parse(bytes(feed("https://evil.co.uk/app.zip")),f));
        reject(() -> Appcast.parse(bytes("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///no-such-file'>]><rss><channel><title>&x;</title></channel></rss>"),f));
        reject(() -> Appcast.parse(bytes("<rss>"+"<a>".repeat(17)+"</a>".repeat(17)+"</rss>"),f));
        reject(() -> Appcast.parse(bytes(feed("https://example.co.uk/"+"x".repeat(2100))),f));
        reject(() -> Appcast.parse(bytes(feed("http://example.co.uk/app.zip")),f));
        var releases=Appcast.parse(bytes(feed("https://example.co.uk/app.zip")),f);
        require(Appcast.latest(releases,"stable",1,1).isPresent()); require(Appcast.latest(releases,"beta",1,1).isEmpty()); require(Appcast.latest(releases,"stable",1,2).isEmpty());
        KeyPair author=Signatures.generate(), other=Signatures.generate(); AppPackage installed=PackageReader.read(signed(author,1,null));
        byte[] plain=signed(author,2,"storage.self"); var r=release(plain,author,2);
        require(UpdateVerifier.verify(plain,r,installed,1,Set.of(),RevocationPolicy.NONE).decision()==UpdateVerifier.Decision.AUTOMATIC);
        require(UpdateVerifier.witness(plain,r,installed,2).version()==2);
        reject(()->UpdateVerifier.witness(plain,release(plain,other,2),installed,2));
        reject(()->UpdateVerifier.witness(plain,r,installed,1));
        reject(() -> UpdateVerifier.verify(plain,r,installed,2,Set.of(),RevocationPolicy.NONE));
        reject(() -> UpdateVerifier.verify(plain,release(plain,other,2),installed,1,Set.of(),RevocationPolicy.NONE));
        byte[] changed=signed(other,2,null); reject(() -> UpdateVerifier.verify(changed,release(changed,author,2),installed,1,Set.of(),RevocationPolicy.NONE));
        byte[] granted=signed(author,2,"item.give");
        require(UpdateVerifier.verify(granted,release(granted,author,2),installed,1,Set.of(),RevocationPolicy.NONE).decision()==UpdateVerifier.Decision.APPROVAL_REQUIRED);
        require(UpdateVerifier.verify(granted,release(granted,author,2),installed,1,Set.of("item.give"),RevocationPolicy.NONE).decision()==UpdateVerifier.Decision.AUTOMATIC);
        byte[] restricted=signed(author,2,"command.template:gift");
        require(UpdateVerifier.verify(restricted,release(restricted,author,2),installed,1,Set.of(),RevocationPolicy.NONE).decision()==UpdateVerifier.Decision.RESTRICTED_APPROVAL);
        require(UpdateVerifier.verify(restricted,release(restricted,author,2),installed,1,Set.of("command.template:gift"),RevocationPolicy.NONE).decision()==UpdateVerifier.Decision.AUTOMATIC);
        reject(() -> UpdateVerifier.verify(plain,release(plain,author,999),installed,1,Set.of(),RevocationPolicy.NONE));
        // 改内容后重新签外层也不能掩盖包内摘要不符。
        AppPackage valid=PackageReader.read(plain); Map<String,byte[]> tampered=valid.entries(); tampered.put("META/sig.json",valid.signature()); tampered.put("app.vue",bytes("<template><text>changed</text></template>"));
        byte[] bad=zip(tampered); reject(() -> UpdateVerifier.verify(bad,release(bad,author,2),installed,1,Set.of(),RevocationPolicy.NONE));
        System.out.println("Appcast 更新断言 " + count + " 条，全部通过");
    }
}
