package com.november.mcphone.core.script.server;

import com.google.gson.*;
import java.util.*;

/** 下限、版本集合、摘要集合取并集；每次准入与落地都重新查当前策略。 */
public final class RevocationPolicy {
    public static final RevocationPolicy NONE = new RevocationPolicy(Map.of());
    public record Rule(long minVersion, Set<Long> versions, Set<String> digests, String reason) {
        public Rule { versions = Set.copyOf(versions); digests = Set.copyOf(digests); if (minVersion < 0) throw new IllegalArgumentException("版本下限不能为负"); }
        public boolean rejects(long version, String digest) { return version < minVersion || versions.contains(version) || digests.contains(digest); }
    }
    private final Map<String, Rule> rules;
    public RevocationPolicy(Map<String, Rule> rules) { this.rules = Map.copyOf(rules); }
    public Rule rule(String app){return rules.get(app);}
    public Rule rejected(String app, long version, String digest) {
        Rule rule = rules.get(app); return rule != null && rule.rejects(version, digest) ? rule : null;
    }
    public RevocationPolicy union(RevocationPolicy stricter) {
        Map<String, Rule> all = new LinkedHashMap<>(rules);
        stricter.rules.forEach((app, incoming) -> all.merge(app, incoming, (a, b) -> {
            var versions = new HashSet<>(a.versions()); versions.addAll(b.versions());
            var digests = new HashSet<>(a.digests()); digests.addAll(b.digests());
            String reason=a.reason().equals(b.reason())||b.reason().isEmpty()?a.reason():a.reason().isEmpty()?b.reason():a.reason()+"; "+b.reason();
            return new Rule(Math.max(a.minVersion(), b.minVersion()), versions, digests, reason.substring(0,Math.min(128,reason.length())));
        }));
        return new RevocationPolicy(all);
    }
    public static RevocationPolicy parse(JsonObject config) {
        Map<String, Rule> rules = new LinkedHashMap<>();
        JsonArray rows = config.has("app_policy") ? config.getAsJsonArray("app_policy") : new JsonArray();
        if (rows.size() > 256) throw new IllegalArgumentException("吊销策略最多 256 条");
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject(); String app = row.get("id").getAsString();
            if (!app.matches("[a-z0-9_.-]+:[a-z0-9_.-]+") || app.length() > 64) throw new IllegalArgumentException("吊销 App id 无效");
            long min = row.has("min_version") ? row.get("min_version").getAsBigDecimal().longValueExact() : 0;
            Set<Long> versions = new HashSet<>(); Set<String> digests = new HashSet<>();
            if (row.has("revoked_versions")) for (var value : row.getAsJsonArray("revoked_versions")) {
                long v = value.getAsBigDecimal().longValueExact(); if (v < 0) throw new IllegalArgumentException("吊销版本无效"); versions.add(v);
            }
            if (row.has("revoked_digests")) for (var value : row.getAsJsonArray("revoked_digests")) {
                String d = value.getAsString().replaceFirst("^sha256:", "");
                if (!Deployment.validDigest(d)) throw new IllegalArgumentException("吊销摘要无效"); digests.add(d);
            }
            String reason = row.has("reason") ? row.get("reason").getAsString() : "";
            if (reason.length() > 128 || reason.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("吊销理由必须是 128 字以内可显示文本");
            if (versions.size() > 256 || digests.size() > 256 || rules.putIfAbsent(app, new Rule(min, versions, digests, reason)) != null)
                throw new IllegalArgumentException("吊销条目重复或超额");
        }
        return new RevocationPolicy(rules);
    }
    /** 老包无单调整数版本，记为 0；不能拿展示 semver 猜 build 号。 */
    public static long versionOf(com.november.mcphone.core.script.pkg.AppPackage pkg) {
        JsonObject root = JsonParser.parseString(new String(pkg.entry("manifest.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        long version = root.has("versionCode") ? root.get("versionCode").getAsBigDecimal().longValueExact() : 0;
        if (version < 0) throw new IllegalArgumentException("versionCode 不能为负"); return version;
    }
}
