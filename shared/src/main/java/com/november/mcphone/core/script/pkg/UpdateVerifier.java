package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.server.*;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.*;

/** 更新的三道签名与能力增量闸。调用方只在全部验证成功后切换当前包。 */
public final class UpdateVerifier {
    public enum Decision { AUTOMATIC, APPROVAL_REQUIRED, RESTRICTED_APPROVAL }
    public record Verified(AppPackage app, long version, Decision decision, Set<String> addedCapabilities) {}
    private UpdateVerifier() {}
    /** 历史候选只用于见证，不授予安装许可；仍先验证 ZIP 外签名，再验证包内完整作者链。 */
    public static Verified evidence(byte[] zip,Appcast.Release release,AppPackage installed)throws Exception{return verify(zip,release,installed,release.version()-1,Set.of(),RevocationPolicy.NONE);}
    /** 同版本只核对签名证据，调用方绝不能把这条路径当作安装或降级许可。 */
    public static Verified witness(byte[] zip,Appcast.Release release,AppPackage installed,long highest)throws Exception {
        if(highest<1||release.version()!=highest)throw new IllegalArgumentException("不是最高版本的签名证据");
        return verify(zip,release,installed,highest-1,Set.of(),RevocationPolicy.NONE);
    }
    public static Verified verify(byte[] zip, Appcast.Release release, AppPackage installed,
                                  long highest, Set<String> approved, RevocationPolicy policy) throws Exception {
        if (!installed.signed() || zip.length != release.length() || zip.length > SafeFetch.MAX_BYTES
                || release.version() <= highest) throw new IllegalArgumentException("长度、签名身份或防降级闸拒绝更新");
        SigManifest original = SigManifest.parse(installed.signature());
        if (!original.verify(installed.digest())) throw new IllegalArgumentException("已安装身份无效");
        // 先验证原始 ZIP 字节。失败的字节绝不进入 ZIP 解析器。
        Signature outer = Signature.getInstance("Ed25519"); outer.initVerify(Signatures.publicKey(original.pubkey()));
        outer.update(zip); if (!outer.verify(release.zipSignature())) throw new IllegalArgumentException("ZIP 外签名无效");
        AppPackage incoming = PackageReader.read(zip);
        if (!incoming.signed() || !incoming.manifest().id().equals(installed.manifest().id())) throw new IllegalArgumentException("更新身份不符");
        SigManifest inner = SigManifest.parse(incoming.signature());
        if (!Arrays.equals(original.pubkey(), inner.pubkey()) || !inner.verify(incoming.digest()))
            throw new IllegalArgumentException("作者密钥改变或包内签名无效");
        // feed 本身不受签名保护，不能让攻击者把旧包标成无限大的新版本，永久冻结后续更新。
        long signedVersion = RevocationPolicy.versionOf(incoming);
        if (signedVersion != release.version() || !incoming.manifest().version().equals(release.displayVersion()))
            throw new IllegalArgumentException("feed 版本必须等于签名覆盖的 versionCode / version");
        if (policy.rejected(incoming.manifest().id(), signedVersion, incoming.digest()) != null)
            throw new IllegalArgumentException("此更新版本已停用");
        return capabilities(incoming,approved);
    }
    /** 已验过作者链的服务器前端，也沿用同一能力增量判据。 */
    public static Verified capabilities(AppPackage incoming,Set<String> approved) {
        JsonObject manifest = JsonParser.parseString(new String(incoming.entry("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> added = new LinkedHashSet<>(); Decision decision = Decision.AUTOMATIC;
        JsonArray capabilities = manifest.has("capabilities") ? manifest.getAsJsonArray("capabilities") : new JsonArray();
        if (capabilities.size() > 64) throw new IllegalArgumentException("能力数超额");
        for (JsonElement value : capabilities) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("能力类型无效");
            String id = value.getAsString(); if (!CapabilityCatalog.knownDeclared(id)) throw new IllegalArgumentException("未知能力");
            CapabilityCatalog.Entry entry = CapabilityCatalog.of(id.startsWith(CapabilityCatalog.COMMAND_TEMPLATE_PREFIX) ? "command.template" : id);
            if (!approved.contains(id)) {
                added.add(id);
                if (entry.tier() == CapabilityTier.RESTRICTED) decision = Decision.RESTRICTED_APPROVAL;
                if (entry.tier() == CapabilityTier.GRANTED && decision == Decision.AUTOMATIC) decision = Decision.APPROVAL_REQUIRED;
            }
        }
        return new Verified(incoming, RevocationPolicy.versionOf(incoming), decision, Set.copyOf(added));
    }
}
