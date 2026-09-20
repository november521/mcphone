package com.november.mcphone.core.script.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.pkg.AppPackage;
import com.november.mcphone.core.script.pkg.FrontendDigest;
import com.november.mcphone.core.script.pkg.PackageError;
import com.november.mcphone.core.script.pkg.PackageReader;
import com.november.mcphone.core.script.pkg.TrustState;
import com.november.mcphone.core.script.pkg.TrustStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 服务端包来源（S17，§14.4 ①②）：把 {@code <世界>/mcphone/store/incoming/} 下的包解包、安全检查、验签，
 * 变成 {@link DeploymentData.Candidate} 进待审队列。<b>扫描本身不给任何特权</b> —— 批准是 OP 的命令。
 *
 * <h2>候选的判据（fail-closed）</h2>
 *
 * <ul>
 *   <li>只收带 {@code server.js} 的包：没有后端的包不需要服务端部署（前端本来就是客户端的事）；</li>
 *   <li>必须<b>带签名</b>且信任判定 {@link TrustState.State#installable}（§14.4 的"验签"）；</li>
 *   <li>manifest 里 {@code deploy: "frontend"} 却带 {@code server.js} 的包直接拒（自相矛盾）；</li>
 *   <li>动作/能力的条数与元素长度在 {@link DeploymentData.Candidate} 构造器里校验，超限即拒。</li>
 * </ul>
 *
 * <h2>前端摘要的谓词（定向对抗 Q8）</h2>
 *
 * {@code frontendDigest = PackageDigest.of(内容条目 − server.js − server/**)}。
 * <b>客户端能加载什么，就必须与这个集合一致</b>：不在摘要集合里的条目一律不加载，否则改名就能
 * "改了前端而摘要不变"。{@code META/}（签名/轮换）本来就不在 {@link AppPackage#entries()} 里，
 * 也不进任何摘要 —— 那是信任轴，不是玩家看到的东西。
 */
public final class ServerPackageScanner {

    /** 相对世界根的待审目录。 */
    public static final String DIR = "mcphone/store/incoming";
    /** 一次扫描最多看几个文件，别让一堆垃圾包把开服拖住。 */
    public static final int MAX_FILES = 64;

    static final String SERVER_ENTRY = FrontendDigest.SERVER_ENTRY;
    static final String SERVER_DIR = FrontendDigest.SERVER_DIR;
    private static final String MANIFEST = "manifest.json";

    private ServerPackageScanner() {
    }

    /** 扫一趟 incoming。返回新增候选数 + 这一趟读到的包（供装配复用，别再解一遍）。 */
    public static Scan scan(MinecraftServer server, DeploymentData deployments) {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve(DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 建不了待审目录 {}：{}", dir, e.toString());
            return new Scan(0, Map.of());
        }
        TrustStore trust = TrustStore.load(configPath(server));
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".mcphone") && !name.endsWith(".zip")) continue;
                files.add(p);
                if (files.size() >= MAX_FILES) {
                    MCphone.LOGGER.warn("[MCphone] 待审目录里的包超过 {} 个，这一趟只看了前 {} 个", MAX_FILES, MAX_FILES);
                    break;
                }
            }
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 读不了待审目录 {}：{}", dir, e.toString());
            return new Scan(0, Map.of());
        }
        Map<String, AppPackage> packages = new LinkedHashMap<>();
        int changed = 0;
        for (Path f : files) if (scanOne(f, deployments, trust, packages)) changed++;
        return new Scan(changed, packages);
    }

    private static boolean scanOne(Path file, DeploymentData deployments, TrustStore trust,
                                   Map<String, AppPackage> packages) {
        AppPackage pkg;
        try {
            pkg = PackageReader.readFile(file);
        } catch (PackageError e) {
            MCphone.LOGGER.warn("[MCphone] 待审包 {} 没过安全检查（{}），跳过", file.getFileName(), e.getMessage());
            return false;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 待审包 {} 读不出，跳过", file.getFileName(), t);
            return false;
        }
        String appId = pkg.manifest().id();
        if (pkg.entry(SERVER_ENTRY) == null) {
            return false;   // 没有后端的包不进部署队列（前端本来就不需要服务端批准）
        }
        if (!pkg.signed()) {
            MCphone.LOGGER.warn("[MCphone] 待审包 {} 没有签名，拒（§14.4 要验签）", appId);
            return false;
        }
        TrustState.Verdict verdict = TrustState.of(pkg, appId, trust);
        if (!verdict.state().installable) {
            MCphone.LOGGER.warn("[MCphone] 待审包 {} 的信任判定是 {}（{}），拒", appId, verdict.state(),
                    verdict.state().messageKey);
            return false;
        }

        byte[] manifestJson = pkg.entry(MANIFEST);
        if (manifestJson == null) return false;   // PackageReader 已经保证在，这里只是防御
        String deploy;
        List<String> actions, capabilities;
        try {
            JsonObject root = JsonParser.parseString(new String(manifestJson, StandardCharsets.UTF_8)).getAsJsonObject();
            deploy = root.has("deploy") ? root.get("deploy").getAsString() : "";
            actions = stringList(root, "actions");
            capabilities = stringList(root, "capabilities");
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 待审包 {} 的 manifest 扩展字段读不出来，拒", appId, t);
            return false;
        }
        if ("frontend".equalsIgnoreCase(deploy)) {
            MCphone.LOGGER.warn("[MCphone] 待审包 {} 声明 deploy=frontend 却带 server.js，自相矛盾，拒", appId);
            return false;
        }

        // 这一趟读到的包留给装配复用（别再解一遍）；已批准的包不再进候选，否则每次开服又重新排队
        packages.put(pkg.digest(), pkg);
        for (Deployment d : deployments.deployments()) {
            if (d.packageDigest().equals(pkg.digest())) return false;
        }

        try {
            DeploymentData.Candidate candidate = new DeploymentData.Candidate(appId, pkg.digest(),
                    frontendDigest(pkg.entries()), System.currentTimeMillis(), actions, capabilities);
            boolean added = deployments.putCandidate(candidate);
            if (added) {
                MCphone.LOGGER.info("[MCphone] 待审候选进队：{}（包摘要 {}…，{} 个动作，{} 个能力，信任 {}）",
                        appId, pkg.digest().substring(0, 8), actions.size(), capabilities.size(), verdict.state());
            }
            return added;
        } catch (IllegalArgumentException e) {
            MCphone.LOGGER.warn("[MCphone] 待审包 {} 的声明超过上限，拒：{}", appId, e.getMessage());
            return false;
        }
    }

    /**
     * 前端摘要：内容条目里**除 {@code server.js} 与 {@code server/**} 之外**的全部。
     * 与线格式无关，纯粹是"玩家看到的那一半"的规范化摘要（Q8）。
     *
     * <p>谓词只有一份，在 {@link FrontendDigest} —— 客户端回显与"界面被改过"的对比也走它。
     */
    public static String frontendDigest(Map<String, byte[]> entries) {
        return FrontendDigest.of(entries);
    }

    /** manifest 里的字符串数组；有非字符串元素就抛（调用方按拒处理）。 */
    static List<String> stringList(JsonObject root, String key) {
        if (!root.has(key)) return List.of();
        JsonElement el = root.get(key);
        if (!el.isJsonArray()) throw new IllegalArgumentException(key + " 必须是数组");
        JsonArray arr = el.getAsJsonArray();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(key + " 里必须全是字符串");
            }
            out.add(e.getAsString());
        }
        return out;
    }

    /** 信任库（§12.4）：与客户端同一个文件布局，只是服务端这一份由服主自己维护。 */
    static Path configPath(MinecraftServer server) {
        // getFile 两版都在，但返回类型不同（1.20.1 是 File、1.21.1 是 Path）：用 toString 收敛，
        // 避免在共用层里出现版本专有的转换。getServerDirectory 在 1.20.1 上不存在，不能用。
        return Path.of(server.getFile("config/mcphone/authors.json").toString());
    }

    /** 一趟扫描的结果：新进队几条 + 这一趟读到的包（按摘要，供装配复用）。 */
    public record Scan(int changed, Map<String, AppPackage> packages) {
        public Scan {
            packages = Map.copyOf(packages);
        }
    }
}
