package com.november.mcphone.core.script.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.november.mcphone.MCphone;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 能力与边界配置（施工方案 §18.8 的 {@code [capabilities] disabled}、§30.5 的 {@code [boundary]}、
 * §31 的预设档位）。
 *
 * <h2>为什么是独立文件，而不是各加载器的 ServerConfig</h2>
 *
 * 这两段是<b>可变长数据表</b>（一串能力 id + 六个开关 + 一个预设），E28 已经为此定过口径：
 * 塞进为"少量开关"设计的 {@code ModConfigSpec} / Fabric JSON 里，坏一个字段会连累全文件且静默。
 * 所以走自己的文件、自己的解析器：
 *
 * <pre>&lt;世界目录&gt;/serverconfig/mcphone-capabilities.json</pre>
 *
 * <p>文件不存在时生成一份带 {@code _} 说明键的模板；<b>此后只读不改写</b>。解析器忽略所有
 * {@code _} 开头的键（JSON 没有注释，这是替代品）。坏配置不崩服：用默认值 + 记日志 + 继续启动。
 *
 * <h2>预设与显式项</h2>
 *
 * {@code preset} 决定底稿，任何显式写出的项都覆盖它（§31.2/§31.4）。三个档都不代人决定
 * "服务端脚本"与"跨服信任"（那两项在别处，且三档都是关的）。
 *
 * <h2>⚠ {@code boundary} 与预设的执行面：本步只解析、不生效（对抗 S18-B1）</h2>
 *
 * 六个行为开关（{@link Boundary}）与预设的差异<b>目前没有任何执行面消费者</b>：
 * {@code resource_move} / {@code remote_machine_read} 属 §29 资源 SDK，{@code cross_dimension_transfer}
 * / {@code escrow_freezes_decay} / {@code store_living_entities} 属托管·收件箱那张卡，
 * {@code remote_machine_operate} 按 §30.5 <b>永不实现</b>。{@code hardcore} 底稿里的
 * {@code trade.escrow} 也落在目录的"本步无调用点"白名单里。
 *
 * <p>所以：<b>不要用 preset 推断运行期安全语义</b> —— 三个档现在只有"目录展示 + warning"的差别；
 * 各功能卡接上消费点时，读 {@link #boundary()} 并通过 {@code CapabilityConfigTest.boundaryConsumers()}
 * 的接线白名单签字（本步为空）。模板里的 {@code _boundary_comment} 也写了这句。
 */
public final class CapabilityConfig {

    /** 相对世界根的文件名。 */
    public static final String FILE = "serverconfig/mcphone-capabilities.json";

    /** 预设档位（§31.2）。 */
    public enum Preset {
        HARDCORE, STANDARD, OPEN;

        static Preset of(String raw, List<String> warnings) {
            if (raw == null) return STANDARD;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warnings.add("不认识的 preset '" + raw + "'（认得 hardcore / standard / open），按 standard 处理");
                return STANDARD;
            }
        }
    }

    /** §30.5 的六个行为开关。默认全 false（原版保守）。 */
    public record Boundary(boolean resourceMove, boolean crossDimensionTransfer, boolean escrowFreezesDecay,
                           boolean remoteMachineRead, boolean remoteMachineOperate, boolean storeLivingEntities) {
    }

    private static final Boundary ALL_OFF = new Boundary(false, false, false, false, false, false);

    private final Preset preset;
    private final Set<String> disabled;
    private final Boundary boundary;
    private final List<String> warnings;

    private CapabilityConfig(Preset preset, Set<String> disabled, Boundary boundary, List<String> warnings) {
        this.preset = preset;
        this.disabled = Collections.unmodifiableSet(disabled);
        this.boundary = boundary;
        this.warnings = List.copyOf(warnings);
    }

    /** 原版默认：standard + 什么都不关 + 边界全关。 */
    public static CapabilityConfig defaults() {
        return new CapabilityConfig(Preset.STANDARD, Set.of(), ALL_OFF, List.of());
    }

    public Preset preset() {
        return preset;
    }

    /** 全服被关掉的能力 id（已按目录过滤过：不认识的 id 不会出现在这里）。 */
    public Set<String> disabled() {
        return disabled;
    }

    public Boundary boundary() {
        return boundary;
    }

    /** 解析时的警告（坏字段、不认识的键……）。启动日志会打出来。 */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * 这项能力被服主关掉了吗。<b>关掉包括 plain 档</b>——免审批不等于服主管不了（§18.8）。
     * 不认识的 id 一律 false（它根本没有路径可关）。
     */
    public boolean isDisabled(String capabilityId) {
        return capabilityId != null && disabled.contains(capabilityId);
    }

    // ---------------------------------------------------------------- 装载

    /** 世界根下的配置路径。 */
    public static Path pathIn(Path worldRoot) {
        return worldRoot.resolve(FILE);
    }

    /** 从服务器读。<b>任何失败都不抛</b>：默认值 + ERROR 日志，服务器照常起。 */
    public static CapabilityConfig load(MinecraftServer server) {
        Path world = server.getWorldPath(LevelResource.ROOT);
        return load(pathIn(world));
    }

    /** 从文件读；文件不存在就生成模板。测试用这个重载。 */
    public static CapabilityConfig load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, template(), StandardCharsets.UTF_8);
                    MCphone.LOGGER.info("[MCphone] 能力配置不存在，已生成模板：{}（当前按默认值：standard、无禁用）", file);
                } catch (IOException e) {
                    MCphone.LOGGER.warn("[MCphone] 生成能力配置模板失败（{}），按默认值继续", file, e);
                }
                return defaults();
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            CapabilityConfig cfg = parse(json);
            for (String w : cfg.warnings()) MCphone.LOGGER.warn("[MCphone] 能力配置 {}：{}", file, w);
            return cfg;
        } catch (IOException e) {
            MCphone.LOGGER.error("[MCphone] 能力配置读不出来（{}），按默认值继续", file, e);
            return defaults();
        }
    }

    /**
     * 解析一段 JSON。<b>不抛</b>：结构错、字段错都只记 warning 并用默认值/跳过该条。
     *
     * <p>可自动验的部分都在这：预设、显式覆盖、未知键、未知能力 id、类型错。文件 IO 不在里面。
     */
    public static CapabilityConfig parse(String json) {
        List<String> warnings = new ArrayList<>();
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                warnings.add("顶层必须是一个 JSON 对象，按默认值处理");
                return new CapabilityConfig(Preset.STANDARD, Set.of(), ALL_OFF, warnings);
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            warnings.add("不是合法 JSON（" + e.getMessage() + "），按默认值处理");
            return new CapabilityConfig(Preset.STANDARD, Set.of(), ALL_OFF, warnings);
        }

        Preset preset = Preset.of(str(root, "preset", warnings), warnings);

        // 预设底稿：hardcore 关掉全服市场；open 打开两个边界开关（§31.2）
        Set<String> presetBase = new LinkedHashSet<>();
        if (preset == Preset.HARDCORE) presetBase.add("trade.escrow");
        Set<String> disabled = new LinkedHashSet<>(presetBase);
        Boundary boundary = preset == Preset.OPEN
                ? new Boundary(true, false, false, true, false, false)
                : ALL_OFF;

        // 显式 disabled 覆盖预设底稿（写空数组 = 一个都不关）。覆盖本身是 §31.2 的语义，
        // 但被覆盖掉的那几条要说一声 —— 免得从旧版本沿用的空数组静默放开（对抗 S18-A4）。
        if (root.has("disabled")) {
            JsonElement d = root.get("disabled");
            if (d.isJsonArray()) {
                disabled.clear();
                JsonArray arr = d.getAsJsonArray();
                for (JsonElement e : arr) {
                    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                        warnings.add("disabled 里有一条不是字符串，跳过：" + e);
                        continue;
                    }
                    String id = e.getAsString();
                    if (!CapabilityCatalog.knownDeclared(id)) {
                        warnings.add("disabled 里有不认识的能力 id（不在能力目录里），跳过：" + id);
                        continue;
                    }
                    disabled.add(id);
                }
                for (String base : presetBase) {
                    if (!disabled.contains(base)) {
                        warnings.add("显式 disabled 覆盖了预设 " + preset + " 的底稿：少了 " + base
                                + "（要保留请把它写进 disabled 数组）");
                    }
                }
            } else {
                warnings.add("disabled 必须是字符串数组，按预设值处理");
            }
        }

        // 显式 boundary 逐项覆盖
        if (root.has("boundary")) {
            JsonElement b = root.get("boundary");
            if (b.isJsonObject()) {
                JsonObject o = b.getAsJsonObject();
                boundary = new Boundary(
                        bool(o, "resource_move", boundary.resourceMove(), warnings),
                        bool(o, "cross_dimension_transfer", boundary.crossDimensionTransfer(), warnings),
                        bool(o, "escrow_freezes_decay", boundary.escrowFreezesDecay(), warnings),
                        bool(o, "remote_machine_read", boundary.remoteMachineRead(), warnings),
                        bool(o, "remote_machine_operate", boundary.remoteMachineOperate(), warnings),
                        bool(o, "store_living_entities", boundary.storeLivingEntities(), warnings));
                for (String key : o.keySet()) {
                    if (key.startsWith("_")) continue;
                    if (!BOUNDARY_KEYS.contains(key)) warnings.add("boundary 里不认识的开关（已忽略）：" + key);
                }
            } else {
                warnings.add("boundary 必须是一个对象，按预设值处理");
            }
        }

        // 不认识的顶层键 = 段名写错，明确报出来（E28 口径）
        for (String key : root.keySet()) {
            if (key.startsWith("_")) continue;
            if (!key.equals("preset") && !key.equals("disabled") && !key.equals("boundary")) {
                warnings.add("不认识的段（已忽略）：" + key + " —— 认识的只有 preset / disabled / boundary");
            }
        }

        return new CapabilityConfig(preset, disabled, boundary, warnings);
    }

    private static final Set<String> BOUNDARY_KEYS = Set.of(
            "resource_move", "cross_dimension_transfer", "escrow_freezes_decay",
            "remote_machine_read", "remote_machine_operate", "store_living_entities");

    private static String str(JsonObject o, String key, List<String> warnings) {
        if (!o.has(key)) return null;
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            warnings.add(key + " 必须是字符串，按默认处理");
            return null;
        }
        return e.getAsString();
    }

    private static boolean bool(JsonObject o, String key, boolean dflt, List<String> warnings) {
        if (!o.has(key)) return dflt;
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            warnings.add("boundary." + key + " 必须是 true/false，按预设处理");
            return dflt;
        }
        return e.getAsBoolean();
    }

    /** 首次生成用的模板（只在文件不存在时写一次）。 */
    static String template() {
        return "{\n"
                + "  \"_comment\": [\n"
                + "    \"MCphone 能力与边界配置。文件不存在时自动生成；此后只读，不会被改写。\",\n"
                + "    \"preset: hardcore | standard | open（默认 standard）。下面任何一项显式写出都覆盖预设。\",\n"
                + "    \"disabled: 全服关闭的能力 id 列表，含 plain 档 —— 关掉后所有 App（含已装）都拿不到。\",\n"
                + "    \"能力 id 与档位见能力目录（/mcphone script capabilities 可以列出来）。\"\n"
                + "  ],\n"
                + "  \"_boundary_comment\": \"resource_move / cross_dimension_transfer / escrow_freezes_decay / remote_machine_read / remote_machine_operate / store_living_entities（本步只解析，没有执行面消费者；不要用 preset 推断运行期语义）\",\n"
                + "  \"preset\": \"standard\",\n"
                + "  \"disabled\": [],\n"
                + "  \"boundary\": {\n"
                + "    \"resource_move\": false,\n"
                + "    \"cross_dimension_transfer\": false,\n"
                + "    \"escrow_freezes_decay\": false,\n"
                + "    \"remote_machine_read\": false,\n"
                + "    \"remote_machine_operate\": false,\n"
                + "    \"store_living_entities\": false\n"
                + "  }\n"
                + "}\n";
    }
}
