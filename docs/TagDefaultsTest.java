package com.november.mcphone.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S18 断言：默认标签白名单（§30.4/§30.5/§30.8）。
 *
 * <p>判据来自 §30.8 的 grep 那条：<b>默认标签里没有任何模组物品</b> ——
 * 默认文件里只许出现 {@code minecraft:} 与 {@code #minecraft:}。
 * 这不是"格式检查"：模组物品默认不放行是我们判不了，放行了收不回来。
 *
 * <p>同时钉住组合方式：服主覆盖 {@code #mcphone:giftable}（{@code replace:false}），
 * 默认内容挂在 {@code #mcphone:default_giftable} 后面 —— 改标签不动 App 的 digest。
 *
 * <p>走的是<b>打包到 jar 里的那份资源</b>（classpath），不是直接读源码目录：
 * 资源没被装进 jar 的错，只有这样才看得见。
 */
public class TagDefaultsTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static JsonObject read(String name) throws Exception {
        try (InputStream in = TagDefaultsTest.class.getClassLoader()
                .getResourceAsStream("data/mcphone/tags/item/" + name)) {
            check(in != null, "data/mcphone/tags/item/" + name + " 在 classpath 上（装进 jar 了）");
            if (in == null) return null;
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** 一个值必须写的是原版：{@code minecraft:} 开头的 id，或 {@code #minecraft:} 开头的标签。 */
    static boolean vanillaOnly(String v) {
        return v.startsWith("minecraft:") || v.startsWith("#minecraft:");
    }

    /**
     * 覆盖入口（服主改的那份，如 {@code giftable.json}）比默认内容多一条口子：
     * 可以引用我们自己的 {@code #mcphone:default_*} —— 那条指向的仍是原版清单。
     * 别的命名空间一律不许出现（模组 id 默认不放行）。
     */
    static boolean wrapperValue(String v) {
        return vanillaOnly(v) || v.startsWith("#mcphone:default_");
    }

    static void valuesAreVanillaOnly(JsonObject obj, String name) {
        if (obj == null) return;
        JsonArray values = obj.getAsJsonArray("values");
        check(values != null, name + " 有 values");
        if (values == null) return;
        for (JsonElement e : values) {
            String v = e.getAsString();
            check(wrapperValue(v), name + " 里只许有原版/自己的默认内容（§30.8）：" + v);
        }
    }

    static void giftableComposition() throws Exception {
        JsonObject giftable = read("giftable.json");
        if (giftable != null) {
            check(giftable.has("replace") && !giftable.get("replace").getAsBoolean(),
                    "giftable.json 用 replace:false（服主能往后面加）");
            JsonArray values = giftable.getAsJsonArray("values");
            eq(values == null ? -1 : values.size(), 1, "giftable.json 只引用我们的默认内容");
            if (values != null && values.size() > 0) {
                eq(values.get(0).getAsString(), "#mcphone:default_giftable",
                        "默认内容挂在 #mcphone:default_giftable 后面");
            }
        }
        JsonObject defaults = read("default_giftable.json");
        if (defaults != null) {
            check(defaults.has("replace") && !defaults.get("replace").getAsBoolean(),
                    "default_giftable.json 用 replace:false");
            JsonArray values = defaults.getAsJsonArray("values");
            check(values != null && values.size() > 0, "默认白名单不为空（原版保守放行）");
        }
        valuesAreVanillaOnly(giftable, "giftable.json");
        valuesAreVanillaOnly(defaults, "default_giftable.json");
    }

    /**
     * 整个 {@code data/mcphone/tags/item/} 目录扫一遍：将来谁加一个带模组 id 的默认文件，
     * 这里当场红。走源码目录（classpath 扫不动目录），先确认路径在。
     */
    static void noModdedAnywhere() throws Exception {
        Path dir = Path.of("..", "..", "shared", "src", "main", "resources", "data", "mcphone", "tags", "item")
                .toAbsolutePath().normalize();
        check(Files.isDirectory(dir), "默认标签目录在：" + dir);
        if (!Files.isDirectory(dir)) return;
        int files = 0;
        try (var stream = Files.list(dir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                files++;
                String name = f.getFileName().toString();
                boolean isDefault = name.startsWith("default_");
                JsonObject obj = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray values = obj.getAsJsonArray("values");
                if (values == null) continue;
                for (JsonElement e : values) {
                    String v = e.getAsString();
                    check(isDefault ? vanillaOnly(v) : wrapperValue(v),
                            name + " 里只许有原版" + (isDefault ? "" : "或 #mcphone:default_*") + "（§30.8）：" + v);
                }
            }
        }
        check(files >= 2, "至少 giftable + default_giftable 两个文件");
    }

    /**
     * 判定用的常量在 shared 里（三平台同码，门面会撞双胞胎闸）。类加载会碰 BuiltInRegistries、
     * 没有 Bootstrap 起不来，所以这里读源码核对两处：常量指着哪个标签、判定真的查它。
     */
    static void tagIdShape() throws Exception {
        Path src = Path.of("..", "..", "shared", "src", "main", "java", "com", "november", "mcphone",
                "core", "script", "server", "ServerIntentApplier.java").toAbsolutePath().normalize();
        check(Files.isRegularFile(src), "落地端源码在：" + src);
        if (!Files.isRegularFile(src)) return;
        String text = Files.readString(src, StandardCharsets.UTF_8);
        check(text.contains("ResourceLocation.fromNamespaceAndPath(\"mcphone\", \"giftable\")"),
                "GIFTABLE 常量指着 mcphone:giftable");
        check(text.contains("TagKey.create(Registries.ITEM"), "GIFTABLE 是物品标签");
        check(text.contains("stack.is(GIFTABLE)"), "item.give 落地前真的查这个标签");
    }

    public static void main(String[] args) throws Exception {
        giftableComposition();
        noModdedAnywhere();
        tagIdShape();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
