package com.november.mcphone.core.script.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 能力目录（施工方案 §18.8）：<b>能力 id → 档位的唯一查表</b>。App 在 manifest 里自称什么档
 * 都不作数，宿主只认这张表。
 *
 * <h2>与 §18.8 的对齐口径</h2>
 *
 * §18.8 原表加 predicate、资源读取/移动与通知条目，目前是 <b>38 个条目</b>。
 * 三种档位与开放数分别为：
 * <ul>
 *   <li>plain 20 条，全部开放；</li>
 *   <li>granted 9 条，开放 8，container.read 尚不开放；</li>
 *   <li>restricted 9 条，只开放已审查命令模板及其 affect_others 门；</li>
 *   <li><b>合计 38 条，开放 30 条</b>（{@link #open()}）。</li>
 * </ul>
 *
 * <p>step26 卡里"22 个"指的就是这组开放项去掉 {@code predicate.test} 的那 22 条；
 * 未开放的 8 条仍保留确定档位，管理界面可以显示其不可用原因。
 *
 * <h2>{@code open} 不等于"能关"：{@link #enforced()}</h2>
 *
 * {@code open} 是首版可用目录，{@link #enforced()} 是实际挂门集合。
 * 当前 30 个开放项均已接门，两集合相等；disabled 在读取或效果入口前拒绝。
 * 钉死它的是<b>挂载时登记</b>：受门成员只许走 {@code CtxBuilder.gated()}/{@code gatedGetter()}
 * 两个帮助函数，挂载时就把 id 记进 {@code Result.gatedMembers} ——
 * {@code ScriptEngineTest.gatedMountRegistry()} 断言"登记集合 = ENFORCED"，
 * {@code enforcedDenyProbe()} 再用永远拒绝的门证明"拒了就一个后端都没碰"。
 * 间接写法（变量/帮助函数）不影响这两条，因为登记与拦截都在帮助函数里做。
 *
 * <p><b>纪律（PM 裁定，S18 之后）</b>：后续卡片给某个开放项接上调用点时，<b>必须同时把它挪进
 * {@link #ENFORCED}</b> —— 否则它会长期停在"可审批、不可关、也不生效"的中间态。
 * 忘了登记也没法混过去：{@code gatedMountRegistry} 两个方向都红（代码挂了门、目录没登记；
 * 或目录登记了、却没挂门）。{@code CapabilityCatalogTest.gatingOnlyInHelpers()} 另外钉死
 * "CtxBuilder 里只许有两个 {@code gate.require} 调用点（都在帮助函数里）、不许有字面量"。
 * 同理，新增 {@code ActionIntent} 种类时，它映射到的能力必须已在 ENFORCED 里
 * （{@code InventoryFitTest} 反射枚举全部种类常量，新增自动进闸）。
 *
 * <p>原文里成组出现的（{@code read.self.position / inventory / stats / gamemode}、
 * {@code read.world.time / weather}、{@code read.players.online_count / list}、
 * {@code storage.global.read / write}）在这里都展开成<b>独立 id</b> —— 审批与拒绝原因
 * 都按 id 走，成组会让"批了一半"没有表示。「22 vs 31」的差异说明进 PR 描述。
 *
 * <h2>改名与增删</h2>
 *
 * 目录是冻结面的一部分：新增一个 id ＝ 新增一条要逐条审批的路径。只许追加，
 * 不许改成已有 id 的语义（§23.4）。
 */
public final class CapabilityCatalog {

    /**
     * 一条能力。
     *
     * @param id   §18.8 的逐字 id
     * @param tier 档位
     * @param open 首版有没有执行路径（{@code ctx} 上会出现它）
     * @param note 为什么这个档 / 为什么首版不开放
     */
    public record Entry(String id, CapabilityTier tier, boolean open, String note) {
    }

    /** {@code command.template:<模板 id>} 是参数化的一族：目录里用这个前缀表示。 */
    public static final String COMMAND_TEMPLATE_PREFIX = "command.template:";

    private static final Map<String, Entry> ENTRIES = build();

    private CapabilityCatalog() {
    }

    private static Map<String, Entry> build() {
        Map<String, Entry> m = new LinkedHashMap<>();
        // ---- plain（18 条：17 开放 + net.fetch 暂不开放）----
        add(m, "read.self.position", CapabilityTier.PLAIN, true, "玩家自己屏幕上就看得到");
        add(m, "read.self.inventory", CapabilityTier.PLAIN, true, "玩家自己屏幕上就看得到");
        add(m, "read.self.stats", CapabilityTier.PLAIN, true, "玩家自己屏幕上就看得到");
        add(m, "read.self.gamemode", CapabilityTier.PLAIN, true, "玩家自己屏幕上就看得到");
        add(m, "read.world.time", CapabilityTier.PLAIN, true, "同上");
        add(m, "read.world.weather", CapabilityTier.PLAIN, true, "同上");
        add(m, "read.players.online_count", CapabilityTier.PLAIN, true, "原版 Tab 列表就有");
        add(m, "read.players.list", CapabilityTier.PLAIN, true, "只给名字；Tab 列表本来就有");
        add(m, "storage.self", CapabilityTier.PLAIN, true, "自己的数据（§17.3 配额）");
        add(m, "storage.global.read", CapabilityTier.PLAIN, true, "每 App 4 MiB、单值 2 KiB");
        add(m, "storage.global.write", CapabilityTier.PLAIN, true, "写 10 次/秒、内容转义");
        add(m, "sealed.store", CapabilityTier.PLAIN, true, "只搬字节，服务端解不开（§17.4.5）");
        add(m, "item.take.self", CapabilityTier.PLAIN, true, "玩家本来就能扔掉自己的东西");
        add(m, "trade.escrow", CapabilityTier.PLAIN, true, "总量守恒、双方各自确认");
        add(m, "score.rw", CapabilityTier.PLAIN, true, "限 myapp_* 前缀；跨前缀那一档本版不提供");
        add(m, "message.self", CapabilityTier.PLAIN, true, "只发给自己");
        add(m, "predicate.test", CapabilityTier.PLAIN, true,
                "§18.3 谓词判定（§32.6 恢复的 ctx.predicate.test）；只读、可关（对抗 S18-A3）");
        add(m, "net.fetch", CapabilityTier.PLAIN, true,
                "S24 非阻塞外网缓存出口；运行期默认关闭且精确域名白名单");
        add(m,"resource.read.item",CapabilityTier.PLAIN,true,"S29 只读本人背包或副手资源，BigInteger 防累加溢出");
        add(m,"resource.read.block",CapabilityTier.GRANTED,true,"S29 距离不超过8格、已加载区块、服主指定目标谓词");
        add(m,"resource.move",CapabilityTier.RESTRICTED,false,"首版不提供资源移动，避免绕过线缆与管道");
        add(m,"notify.self",CapabilityTier.PLAIN,true,"只发给本人；服务端订阅、存量及频率闸");
        add(m,"notify.subscribers",CapabilityTier.GRANTED,true,"占用他人注意力；每 App 每分钟两次");

        // ---- granted（6 个开放 + container.read/write 暂不开放）----
        add(m, "item.give", CapabilityTier.GRANTED, true, "凭空造物品（Q1）");
        add(m, "loot.roll", CapabilityTier.GRANTED, true, "凭空造物，且表可能很值钱（Q1）");
        add(m, "currency.mint", CapabilityTier.GRANTED, true, "凭空造货币（Q1）");
        add(m, "attr.grant", CapabilityTier.GRANTED, true, "改玩家自身能力上限（Q1）");
        add(m, "effect.give", CapabilityTier.GRANTED, true, "同上");
        add(m, "item.give.other", CapabilityTier.GRANTED, true, "给别人东西（Q2）");
        add(m, "container.read", CapabilityTier.GRANTED, false,
                "S18 不做 ctx.container.read；将来开放也要 range ≤ 8 + 必须过谓词");
        add(m, "container.write", CapabilityTier.RESTRICTED, false,
                "隔空搬空别人箱子的风险太高，首版不开放（§18.7）");

        // ---- restricted（7 个全不开放）----
        add(m, "command.template", CapabilityTier.RESTRICTED, true,
                "S26 参数化逐模板审批；默认关闭，仅服主本地 reviewed 模板可运行");
        add(m, "command.affect_others", CapabilityTier.RESTRICTED, true,
                "S26 模板影响他人的独立审批；运行期默认关闭");
        add(m, "block.set", CapabilityTier.RESTRICTED, false, "改世界（Q1+Q2）");
        add(m, "message.broadcast", CapabilityTier.RESTRICTED, false, "刷屏面、钓鱼面（Q2）");
        add(m, "read.nearby.entities", CapabilityTier.RESTRICTED, false, "雷达 / 透视（Q3）");
        add(m, "item.take.other", CapabilityTier.RESTRICTED, false, "隔空搬空别人箱子（Q2）");
        add(m, "ability.fly", CapabilityTier.RESTRICTED, false, "有归属冲突，首版不开放（§18.4）");
        add(m, "storage.cross_player.read", CapabilityTier.RESTRICTED, false, "别人的数据（Q3）");
        return Map.copyOf(m);
    }

    private static void add(Map<String, Entry> m, String id, CapabilityTier tier, boolean open, String note) {
        m.put(id, new Entry(id, tier, open, note));
    }

    /** 精确查表；没有就是 null。<b>不要用它判参数化的 {@code command.template:xxx}</b>，用 {@link #knownDeclared}。 */
    public static Entry of(String id) {
        return id == null ? null : ENTRIES.get(id);
    }

    /** manifest 里声明的名字认不认得（含 {@code command.template:<id>} 这一族）。 */
    public static boolean knownDeclared(String declared) {
        if (declared == null) return false;
        if (ENTRIES.containsKey(declared)) return true;
        if (!declared.startsWith(COMMAND_TEMPLATE_PREFIX)) return false;
        String templateId = declared.substring(COMMAND_TEMPLATE_PREFIX.length());
        // 模板 id 与动作 id 同一套线格式限制（非空、≤64、无控制字符）
        if (templateId.isEmpty() || templateId.length() > 64) return false;
        for (int i = 0; i < templateId.length(); i++) {
            if (Character.isISOControl(templateId.charAt(i))) return false;
        }
        return true;
    }

    /** 全部条目，顺序 = §18.8 的书写顺序。 */
    public static List<Entry> all() {
        return List.copyOf(new ArrayList<>(ENTRIES.values()));
    }

    /** 首版开放的 23 个。能不能关看 {@link #enforced(String)} —— 里面一部分还没有调用点。 */
    public static List<Entry> open() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ENTRIES.values()) if (e.open()) out.add(e);
        return List.copyOf(out);
    }

    /** 全部 id（含不开放的）。 */
    public static Set<String> ids() {
        return ENTRIES.keySet();
    }

    /**
     * 调用点已接门、<b>{@code disabled} 对它们真的生效</b>的开放 id。
     *
     * <p>这张表必须和 {@code CtxBuilder} 的挂载登记一字不差
     * （{@code ScriptEngineTest.gatedMountRegistry()} 建一次 ctx 不调成员、逐条对；
     * {@code CapabilityCatalogTest.gatingOnlyInHelpers()} 钉住"只许两个登记/拦截点"）。
     */
    private static final Set<String> ENFORCED = Set.of(
            "message.self","item.give.other",
            "read.self.position","read.self.inventory","read.self.stats","read.world.time","read.world.weather","read.players.online_count","read.players.list",
            "trade.escrow","item.take.self",
            "currency.mint",
            "notify.self","notify.subscribers",
            "read.self.gamemode",
            "storage.self",
            "storage.global.read",
            "storage.global.write",
            "sealed.store",
            "score.rw",
            "predicate.test",
            "net.fetch",
            "resource.read.item",
            "resource.read.block",
            "command.template",
            "command.affect_others",
            "item.give",
            "loot.roll",
            "attr.grant",
            "effect.give");

    /** 这个开放能力关掉之后，调用点真的会拒吗。 */
    public static boolean enforced(String id) {
        return id != null && ENFORCED.contains(id);
    }

    /** 有调用点、{@code disabled} 会生效的那一组（见 {@link #enforced(String)}）。 */
    public static Set<String> enforcedIds() {
        return ENFORCED;
    }

    /** 首版开放的 id。 */
    public static Set<String> openIds() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Entry e : ENTRIES.values()) if (e.open()) out.add(e.id());
        return java.util.Collections.unmodifiableSet(out);
    }
}
