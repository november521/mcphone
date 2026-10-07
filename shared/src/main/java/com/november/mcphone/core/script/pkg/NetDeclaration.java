package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.server.SafeFetch;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 客户端外网许可只来自已验签的清单。整个包摘要变化就必须重新授权。 */
public record NetDeclaration(Set<String> hosts, String why, String consentKey) {
    public NetDeclaration { hosts = Set.copyOf(hosts); }
    public static NetDeclaration of(AppPackage pkg) {
        if (pkg == null || !pkg.signed()) return null;
        SigManifest signature = SigManifest.parse(pkg.signature());
        if (!signature.verify(pkg.digest())) return null;
        JsonObject manifest = JsonParser.parseString(new String(pkg.entry("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!manifest.has("net")) return null;
        JsonObject net = manifest.getAsJsonObject("net");
        if (net.size() != 2 || !net.has("hosts") || !net.has("why")) throw new IllegalArgumentException("net 只接受 hosts 与 why");
        var hosts = new LinkedHashSet<String>();
        JsonArray declared = net.getAsJsonArray("hosts");
        if (declared.isEmpty() || declared.size() > 8) throw new IllegalArgumentException("外网域名要 1–8 个");
        for (JsonElement entry : declared) {
            String host = entry.getAsString();
            if (host.length() > 253 || !host.equals(host.toLowerCase(Locale.ROOT)) || !hosts.add(host))
                throw new IllegalArgumentException("域名必须小写且不重复");
            SafeFetch.validate("https://" + host + "/", hosts);
        }
        if (!net.get("why").isJsonPrimitive() || !net.getAsJsonPrimitive("why").isString()) throw new IllegalArgumentException("why 必须是文字");
        String why = net.get("why").getAsString();
        if (why.isBlank() || why.length() > 256 || why.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("网络用途无效");
        return new NetDeclaration(hosts, why, pkg.manifest().id() + "|" + pkg.digest() + "|"
                + Base64.getEncoder().encodeToString(signature.pubkey()));
    }
}
