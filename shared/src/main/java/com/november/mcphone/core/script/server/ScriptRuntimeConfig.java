package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 跨加载器的可变长度运行期配置。坏配置拒绝替换现有快照。 */
public record ScriptRuntimeConfig(boolean mailboxOnFull, ZoneId zone, LocalTime dailyAt, RevocationPolicy revocations,
                                  Set<UUID> giftEditors, Set<UUID> storeReviewers, Set<UUID> deploymentApprovers, FetchCache.Policy net, Set<String> ignoreAuthorRevocations,Map<String,String> frontendUpdates,boolean serverScripts,Set<String> blockedAuthors,Set<String> authorUpdates) {
    public ScriptRuntimeConfig(boolean mailboxOnFull,ZoneId zone,LocalTime dailyAt,RevocationPolicy revocations,Set<UUID> giftEditors,Set<UUID> storeReviewers,Set<UUID> deploymentApprovers,FetchCache.Policy net,Set<String> ignored,Map<String,String> updates,boolean scripts,Set<String> blocked){this(mailboxOnFull,zone,dailyAt,revocations,giftEditors,storeReviewers,deploymentApprovers,net,ignored,updates,scripts,blocked,Set.of());}
    public ScriptRuntimeConfig(boolean mailboxOnFull,ZoneId zone,LocalTime dailyAt,RevocationPolicy revocations,Set<UUID> giftEditors,Set<UUID> storeReviewers,Set<UUID> deploymentApprovers,FetchCache.Policy net,Set<String> ignored,Map<String,String> updates){this(mailboxOnFull,zone,dailyAt,revocations,giftEditors,storeReviewers,deploymentApprovers,net,ignored,updates,false,Set.of());}
    public ScriptRuntimeConfig(boolean mailboxOnFull,ZoneId zone,LocalTime dailyAt,RevocationPolicy revocations,Set<UUID> giftEditors,Set<UUID> storeReviewers,Set<UUID> deploymentApprovers,FetchCache.Policy net,Set<String> ignoreAuthorRevocations){this(mailboxOnFull,zone,dailyAt,revocations,giftEditors,storeReviewers,deploymentApprovers,net,ignoreAuthorRevocations,Map.of());}
    public ScriptRuntimeConfig(boolean mailboxOnFull, ZoneId zone, LocalTime dailyAt, RevocationPolicy revocations,Set<UUID> giftEditors,Set<UUID> storeReviewers,Set<UUID> deploymentApprovers,FetchCache.Policy net){this(mailboxOnFull,zone,dailyAt,revocations,giftEditors,storeReviewers,deploymentApprovers,net,Set.of());}
    public ScriptRuntimeConfig(boolean mailboxOnFull, ZoneId zone, LocalTime dailyAt, RevocationPolicy revocations,
                               Set<UUID> giftEditors, Set<UUID> storeReviewers, Set<UUID> deploymentApprovers) {
        this(mailboxOnFull, zone, dailyAt, revocations, giftEditors, storeReviewers, deploymentApprovers, FetchCache.Policy.DEFAULT);
    }
    public ScriptRuntimeConfig(boolean mailboxOnFull, ZoneId zone, LocalTime dailyAt, RevocationPolicy revocations) {
        this(mailboxOnFull, zone, dailyAt, revocations, Set.of(), Set.of(), Set.of());
    }
    public ScriptRuntimeConfig { giftEditors = Set.copyOf(giftEditors); storeReviewers = Set.copyOf(storeReviewers); deploymentApprovers = Set.copyOf(deploymentApprovers);ignoreAuthorRevocations=Set.copyOf(ignoreAuthorRevocations);frontendUpdates=Map.copyOf(frontendUpdates);blockedAuthors=Set.copyOf(blockedAuthors);authorUpdates=Set.copyOf(authorUpdates);if(frontendUpdates.size()>256||authorUpdates.size()>256||frontendUpdates.values().stream().anyMatch(mode->!Set.of("optional","forced","off").contains(mode)))throw new IllegalArgumentException("前端更新策略无效"); }
    public boolean followsAuthor(String app){return !ignoreAuthorRevocations.contains(app);}
    public boolean followsUpdates(String app){return authorUpdates.contains(app);}
    public String frontendUpdate(String app){return frontendUpdates.getOrDefault(app,"optional");}
    public static final String FILE = "serverconfig/mcphone-script-runtime.json";
    public static ScriptRuntimeConfig load(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
        try {
            if (Files.notExists(file)) ScriptStateData.atomicWrite(file, ("""
                    {
                      "_comment": "cost 首版选择 A：不支持、不收费。on_full 仅 mailbox 或 reject，绝不丢地上。",
                      "cost_strategy": "unsupported",
                      "server_scripts": false,
                      "cross_server_trust": false,
                      "blocked_authors": [],
                      "on_full": "mailbox",
                      "cycle": {"zone": "UTC", "daily_at": "04:00"},
                      "app_policy": [],
                      "net": {"server_fetch": false, "allowed_hosts": [], "max_bytes": 262144, "cache_ttl_ms": 21600000, "allow_client_fetch": true},
                      "admin": {"gift_editors": [], "store_reviewers": [], "deployment_approvers": []}
                    }
                    """).getBytes(StandardCharsets.UTF_8));
            if (Files.size(file) > 65536) throw new IllegalArgumentException("运行期配置超过 64 KiB");
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) { throw new IllegalStateException("运行期配置无法读取", ex); }
    }
    public static ScriptRuntimeConfig parse(String json) {
        if (json.length() > 65536 || JsonScan.check(json, 8) != null) throw new IllegalArgumentException("运行期配置结构无效");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("cost_strategy") || !root.get("cost_strategy").getAsString().equals("unsupported"))
            throw new IllegalArgumentException("cost_strategy 必须显式选 unsupported（方案 A）");
        boolean serverScripts=flag(root,"server_scripts",false);
        if(flag(root,"cross_server_trust",false))throw new IllegalArgumentException("跨服信任首版不提供，必须为 false");
        Set<String> blocked=new LinkedHashSet<>();if(root.has("blocked_authors")){JsonArray list=root.getAsJsonArray("blocked_authors");if(list.size()>128)throw new IllegalArgumentException("作者禁用表超额");for(JsonElement value:list){String key=value.getAsString();byte[] bytes=Base64.getDecoder().decode(key);com.november.mcphone.core.script.pkg.Signatures.publicKey(bytes);if(!Base64.getEncoder().encodeToString(bytes).equals(key)||!blocked.add(key))throw new IllegalArgumentException("作者公钥重复或不规范");}}
        String onFull = root.has("on_full") ? root.get("on_full").getAsString() : "mailbox";
        if (!Set.of("mailbox", "reject").contains(onFull)) throw new IllegalArgumentException("on_full 只认 mailbox / reject");
        JsonObject cycle = root.has("cycle") ? root.getAsJsonObject("cycle") : new JsonObject();
        if(!cycle.has("zone")||!cycle.get("zone").isJsonPrimitive()||!cycle.getAsJsonPrimitive("zone").isString()||cycle.get("zone").getAsString().isBlank())throw new IllegalArgumentException("cycle.zone 必须显式填写 IANA 时区");
        ZoneId zone;LocalTime at;
        try{zone=ZoneId.of(cycle.get("zone").getAsString());at=LocalTime.parse(cycle.has("daily_at")?cycle.get("daily_at").getAsString():com.november.mcphone.api.sdk.cycle.ITimeCycle.DEFAULT_DAILY_AT);}
        catch(java.time.DateTimeException bad){throw new IllegalArgumentException("周期时区或刷新时间无效",bad);}
        JsonObject admin = root.has("admin") ? root.getAsJsonObject("admin") : new JsonObject();
        Set<String> ignored=new HashSet<>();if(root.has("app_policy"))for(JsonElement element:root.getAsJsonArray("app_policy")){JsonObject row=element.getAsJsonObject();if(row.has("follow_author_revocations")){JsonElement follow=row.get("follow_author_revocations");if(!follow.isJsonPrimitive()||!follow.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("follow_author_revocations 必须是布尔值");if(!follow.getAsBoolean())ignored.add(row.get("id").getAsString());}}
        Map<String,String> updates=new LinkedHashMap<>();if(root.has("app_policy"))for(JsonElement element:root.getAsJsonArray("app_policy")){JsonObject row=element.getAsJsonObject();if(row.has("frontend_update")){if(!row.get("frontend_update").isJsonPrimitive()||!row.getAsJsonPrimitive("frontend_update").isString())throw new IllegalArgumentException("frontend_update 必须是 optional / forced / off");updates.put(row.get("id").getAsString(),row.get("frontend_update").getAsString());}}
        Set<String> followed=new LinkedHashSet<>();if(root.has("app_policy"))for(JsonElement element:root.getAsJsonArray("app_policy")){JsonObject row=element.getAsJsonObject();if(flag(row,"follow_author_updates",false))followed.add(row.get("id").getAsString());}
        return new ScriptRuntimeConfig(onFull.equals("mailbox"), zone, at, RevocationPolicy.parse(root),
                players(admin, "gift_editors"), players(admin, "store_reviewers"), players(admin, "deployment_approvers"), FetchCache.Policy.parse(root),ignored,updates,serverScripts,blocked,followed);
    }
    private static boolean flag(JsonObject root,String key,boolean initial){if(!root.has(key))return initial;JsonElement value=root.get(key);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException(key+" 必须是布尔值");return value.getAsBoolean();}
    private static Set<UUID> players(JsonObject admin, String key) {
        Set<UUID> result = new HashSet<>();
        if (!admin.has(key)) return result;
        JsonArray list = admin.getAsJsonArray(key); if (list.size() > 128) throw new IllegalArgumentException("管理名单超额");
        for (JsonElement value : list) {
            String text = value.getAsString(); UUID uuid = UUID.fromString(text);
            if (!uuid.toString().equalsIgnoreCase(text) || !result.add(uuid)) throw new IllegalArgumentException("管理名单 UUID 不规范或重复");
        }
        return result;
    }
}
