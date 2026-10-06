package com.november.mcphone.core.script.server;

import com.google.gson.JsonParser;
import java.util.*;
public final class RevocationPolicyTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("吊销检查 " + checks); }
    public static void main(String[] args) {
        String digest = "a".repeat(64);
        RevocationPolicy own = new RevocationPolicy(Map.of("test:app", new RevocationPolicy.Rule(60, Set.of(61L), Set.of(digest), "需升级")));
        RevocationPolicy author = new RevocationPolicy(Map.of("test:app", new RevocationPolicy.Rule(56, Set.of(57L), Set.of(), "作者声明")));
        var rule = own.union(author).rejected("test:app", 59, "b".repeat(64));
        check(rule != null && rule.minVersion() == 60);
        check(own.rejected("test:app", 61, "b".repeat(64)) != null);
        check(own.rejected("test:app", 62, digest) != null);
        check(own.rejected("test:app", 62, "b".repeat(64)) == null);
        check(own.rejected("other:app", 1, digest) == null);
        var config = ScriptRuntimeConfig.parse("{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"app_policy\":[{\"id\":\"test:app\",\"min_version\":56,\"reason\":\"升级\"}]}");
        check(config.mailboxOnFull()); check(config.revocations().rejected("test:app", 55, digest) != null);
        for (String bad : List.of("{}", "{\"cost_strategy\":\"charge\"}", "{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"on_full\":\"drop\"}",
                "{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"app_policy\":[{\"id\":\"test:app\",\"min_version\":1.2}]}")) {
            try { ScriptRuntimeConfig.parse(bad); throw new AssertionError("坏策略被接受"); } catch (RuntimeException rejected) { checks++; }
        }
        System.out.println("RevocationPolicyTest: " + checks + " checks passed");
    }
}
