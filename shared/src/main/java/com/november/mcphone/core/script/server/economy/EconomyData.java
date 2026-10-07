package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.economy.TxnResult;
import com.november.mcphone.core.PhoneSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * 货币的权威状态（施工方案 §22.4、§22.10）：每种货币的余额（UUID → 最小单位）、铸造与销毁的累计、
 * 托管账本。世界级存档，挂主世界 —— 余额挂在玩家身上的话，离线玩家就收不到钱（§22.4）。
 *
 * <p><b>只许服务端主线程碰，没有锁</b>（{@link CurrencyGateway}）。改动与 {@code setDirty()} 在同一个主线程操作里；
 * 什么时候写盘归 MC 的世界保存。NeoForge 写 SavedData 是原子的；Forge 1.20.1 与 Fabric（原版）就地截断重写，
 * 写到一半被杀那一份就坏了。所以同一次序列化出来的 tag 另写一份原子快照（{@link EconomySnapshot}），开服时优先读它；
 * 两份都完整就比代数（{@code generation}，每次保存加一），用新的那份 —— 快照写失败过的话它是旧的，无条件优先它就等于回滚。
 * 两份都读不出来才整份锁住（见下）。
 *
 * <p><b>读坏了绝不拿一本空账顶上</b>：原版读档出错会新建一份空的，下次保存就把原文件盖掉，等于清空所有人的钱。
 * <ul>
 *   <li>整份读不出来（文件在但没读进来、版本认不得）→ 整个锁住：凡是要记进这份存档的操作一律 {@code UNAVAILABLE}，
 *       {@link #isDirty()} 恒为 false，原文件一个字节都不动。</li>
 *   <li>某一种货币读坏了 → 只锁那一种，它的原始数据原样写回。</li>
 * </ul>
 * 字段缺失取默认、多出来的字段忽略；类型不对才算读坏。<b>例外是缺了会动到钱的字段</b>（托管的 settled、amount）：
 * settled 缺了按"没结清"读，放过款的那笔就能再放一次 —— 这类缺失同样算读坏。
 */
public final class EconomyData extends PhoneSavedData implements BalanceStore, TxnLog.Journal {

    static final String FILE_NAME = MCphone.MODID + "_economy";

    /** 存档格式的版本。改格式就 +1，并在 {@link #MIGRATIONS} 里补一步；认不得的版本一律锁住，不猜。 */
    public static final int DATA_VERSION = 1;

    /** 从第 n 版升到第 n+1 版的那一步。目前只有第 1 版，所以是空的。 */
    private static final Map<Integer, UnaryOperator<CompoundTag>> MIGRATIONS = Map.of();

    /** 锁住时给调用方的原因（翻译键）。细节在日志里。 */
    public static final String KEY_LOCKED = "mcphone.economy.unavailable.data_locked";

    private final Map<String, Map<UUID, Long>> balances = new HashMap<>();
    /** 货币 id → {铸造累计, 销毁累计} */
    private final Map<String, long[]> supply = new HashMap<>();
    /** 读坏了的货币 → 它在存档里的原样，存档时原样写回；null = 存档里本来就没有这一段 */
    private final Map<String, Tag> lockedCurrencies = new LinkedHashMap<>();
    /** 属于锁住的货币的托管条目，原样写回 */
    private final List<Tag> lockedEscrow = new ArrayList<>();
    /** 整份锁住的原因；null = 没锁 */
    private final String wholeLock;
    private final EscrowLedger escrow;
    /** 世界保存、序列化这一份时调：给流水写存档点（{@link TxnLog#checkpoint}） */
    private Runnable onSave = () -> { };
    /** 第几次保存。两份存档都完整时靠它挑新的那份 */
    private long generation;
    /** 原子快照写到哪；null = 不写（测试） */
    private Path snapshotFile;

    private final LongSupplier clock;
    /** 读档时建立时刻晚于读档时刻一个 {@link #CLOCK_SLACK_MS} 以上、还没结清的托管有几笔（开服报出来；给测试看） */
    int aheadOfClock;
    /** 时钟差这么多以内不报：NTP 校时通常只差几秒 */
    private static final long CLOCK_SLACK_MS = 3_600_000L;
    /**
     * 历次保存里最晚的时刻（存档里的 savedAt）。只增不减：时钟慢的机器上保存一次就把它拉回去的话，下次开服照样提前退款。
     * 代价是时钟快过之后它停在快的那一刻，那段时间建的托管要等到那一刻再过一个超时周期才自动退（开服时报出来）
     */
    private long savedAt;

    private EconomyData(LongSupplier clock, String wholeLock) {
        this.clock = clock;
        this.wholeLock = wholeLock;
        this.escrow = new EscrowLedger(clock, EscrowLedger.DEFAULT_TIMEOUT_MS, this::setDirty, this::unavailableReasonKey);
    }

    /** 新世界、或者测试用的一本空账。 */
    public static EconomyData empty(LongSupplier clock) {
        return new EconomyData(clock, null);
    }

    private static final String SAVED_DATA = "SavedData 那份（<世界>/data/" + FILE_NAME + ".dat）";

    /** 整份锁住时怎么解：每一条锁住原因后面都跟着它。 */
    static final String UNLOCK_HINT = "。修好或换回备份后重启（只挪走其中一份的话，开服会用另一份 —— 另一份不在、也没有 economy.dat.stale 的话"
            + "就按新世界开；它也可能更旧、或者同样读不通，程序没替你核对过）。确认这些钱都不要了，把 <世界>/data/" + FILE_NAME
            + ".dat 与 <世界>/mcphone/economy/ 下的 economy.dat、economy.dat.stale 都挪走再开服，就按新世界开："
            + "这份存档里记的余额与托管全部清零（计分板、外部钱包里的余额不记在这里，不受影响；从那里押进托管的钱随托管一起没了）";

    /** 整份锁住的一本。原因要醒目地进日志：服主不看日志就只会看到「货币用不了」。 */
    static EconomyData locked(String reason, LongSupplier clock) {
        String why = reason + UNLOCK_HINT;
        MCphone.LOGGER.error("[MCphone] ⚠⚠ 货币存档锁住了，凡是要记进这份存档的货币操作都会返回 UNAVAILABLE，存档文件不会被改写 ⚠⚠ 原因：{}", why);
        return new EconomyData(clock, why);
    }

    /** 在、不在、看不到（没权限之类）：看不到的一律按"在"办 —— 当成不在就可能拿空账盖掉它，或者该挪开的旧快照没挪开还不报。 */
    private static boolean present(Path p) {
        return pathState(p) != 0;
    }

    private static String state(Path p, String ifThere) {
        return switch (pathState(p)) {case 1 -> ifThere; case 0 -> " 不在"; default -> " 看不到（权限或路径不对？）";};
    }

    /** Windows 会把 ENOTDIR 折成 notExists=true；只有父路径能穿过时才可判为空账。 */
    private static int pathState(Path p) {
        if (Files.exists(p)) return 1;
        if (!Files.notExists(p)) return 2;
        for (Path parent = p.toAbsolutePath().getParent(); parent != null; parent = parent.getParent()) {
            if (Files.exists(parent)) return Files.isDirectory(parent) ? 0 : 2;
            if (!Files.notExists(parent)) return 2;
        }
        return 0;
    }

    /** 必须挂在主世界的 DataStorage：它按维度分，挂错了玩家去下界钱就「没了」且不报错。 */
    public static EconomyData get(MinecraftServer server) {
        LongSupplier clock = System::currentTimeMillis;
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_NAME + ".dat");
        Path snapshot = EconomySnapshot.path(server);
        EconomyData d = getOrCreate(server, FILE_NAME,
                () -> createFor(file, snapshot, clock), tag -> loadPreferring(tag, snapshot, clock));
        d.snapshotTo(snapshot);
        return d;
    }

    /**
     * 原版要一本新的时候给什么：SavedData 那份不在，或者在却读不出来（原版读档抛异常时不报给我们，而是回头来这里要一本新的）。
     * 有完整的快照就用快照；没有的话，文件在就整份锁住，绝不给空账；文件也不在才是新世界。
     */
    static EconomyData createFor(Path file, Path snapshot, LongSupplier clock) {
        CompoundTag snap = readSnapshot(snapshot);
        if (snap != null) {
            MCphone.LOGGER.warn("[MCphone] 货币存档 {}{}，改用原子快照 {}", file, state(file, " 读不出来"), snapshot);
            // 走到这里 SavedData 那份不在或读不出：说出来 —— 否则服主只挪走快照，就静默按新世界开了
            EconomyData d = load(snap, clock, "原子快照 " + snapshot + "（SavedData 那份 " + file + state(file, " 读不出来") + "）");
            // 下次保存把 SavedData 那份重写好：不然这段时间快照是唯一的好副本，删错一个目录就没了
            d.setDirty();
            return d;
        }
        // 三份里有一份在就不是新世界：快照在但读不出也一样，给空账的话第一次保存就把它盖掉了
        Path stale = EconomySnapshot.stalePath(snapshot);
        if (present(file) || present(snapshot) || present(stale)) {
            return locked("存档文件 " + file + state(file, " 在但没读出来")
                    + "，原子快照 " + snapshot + state(snapshot, " 在但没读出来")
                    + (present(stale) ? "；" + describeStale(stale, snapshot) : ""), clock);
        }
        return empty(clock);
    }

    /** 锁住时怎么说 .stale：读得出就说它是第几次保存、什么时候存的（快照一直写不进去的话它可能是几周前的）、改名回去会丢什么。 */
    private static String describeStale(Path stale, Path snapshot) {
        if (!Files.exists(stale)) return "看不到 " + stale + " 在不在（权限或路径不对？），不知道有没有上一份快照";
        CompoundTag t;
        String at;
        try {
            t = EconomySnapshot.read(stale);
            // 用文件的写入时刻，不用 savedAt：savedAt 只增不减，时钟快过之后它说的不是这一次保存
            at = "文件写于 " + Files.getLastModifiedTime(stale).toInstant();
        } catch (java.io.IOException e) {
            return "写快照失败时挪开的 " + stale + " 也读不出来（" + e + "）";
        }
        String gen = t.contains("generation", Tag.TAG_LONG) ? "第 " + t.getLong("generation") + " 次保存" : "不知道第几次保存";
        return "上一份完整快照在 " + stale + "（" + gen + "，" + at + "，写快照失败时挪开的）。确认后改名成 " + snapshot.getFileName()
                + " 再开服就回到那一次保存：那之后的变动全部作废，流水里那之后的行不会被自动标出来";
    }

    /** SavedData 那份读出来了：快照也完整、而且不比它旧，就用快照；否则用 SavedData 那份并记日志。 */
    static EconomyData loadPreferring(CompoundTag saved, Path snapshot, LongSupplier clock) {
        CompoundTag snap = readSnapshot(snapshot);
        if (snap == null) {
            if (pathState(snapshot) == 0) MCphone.LOGGER.info("[MCphone] 还没有货币的原子快照，用 SavedData 那份");
            // 说出快照的状态：否则服主只挪走 SavedData，开服就静默按新世界开了
            return load(saved, clock, SAVED_DATA + "（原子快照 " + snapshot + state(snapshot, " 读不出来") + "）");
        }
        long gs = generationOf(snap), gm = generationOf(saved);
        if (gm > gs) {
            MCphone.LOGGER.warn("[MCphone] 货币的原子快照比 SavedData 旧（{}对{}），用 SavedData 那份", genText(snap), genText(saved));
            return load(saved, clock, SAVED_DATA + "（原子快照 " + snapshot + "：" + genText(snap) + "，比它旧）");
        }
        EconomyData d = load(snap, clock, "原子快照 " + snapshot);
        if (gm < gs) {
            MCphone.LOGGER.warn("[MCphone] 货币的 SavedData 比原子快照旧（{}对{}），用快照 {}", genText(saved), genText(snap), snapshot);
            d.setDirty();
        } else if (!snap.equals(saved)) {
            // 代数一样、内容不一样：多半是有人手改了其中一份。以快照为准，说清楚改 SavedData 不算数
            MCphone.LOGGER.warn("[MCphone] 货币的 SavedData 与原子快照 {} 代数相同但内容不同，以快照为准 —— "
                    + "手改存档要连快照一起改，或者先删掉快照", snapshot);
            d.setDirty();
        }
        return d;
    }

    /** 没有返回 null；有但读不出来记一条并返回 null。 */
    private static CompoundTag readSnapshot(Path snapshot) {
        if (pathState(snapshot) == 0) return null;
        try {
            return EconomySnapshot.read(snapshot);
        } catch (java.io.IOException e) {
            MCphone.LOGGER.warn("[MCphone] 货币的原子快照 {} 读不出来（{}），回退 SavedData 那份", snapshot, e.toString());
            return null;
        }
    }

    /** "第 N 次保存"；没写就说没写，不编一个第 0 次。 */
    private static String genText(CompoundTag tag) {
        return tag.contains("generation", Tag.TAG_LONG) ? "第 " + tag.getLong("generation") + " 次保存" : "没写第几次保存";
    }

    private static long generationOf(CompoundTag tag) {
        return tag.contains("generation", Tag.TAG_LONG) ? tag.getLong("generation") : 0;
    }

    /** 之后每次保存都把同一个 tag 原子写到这里。 */
    void snapshotTo(Path file) {
        this.snapshotFile = file;
    }

    // ---------------------------------------------------------------- 状态

    /** 整份锁住的原因；没锁就是 null。 */
    public String wholeLock() {
        return wholeLock;
    }

    /** 锁住的那几种货币。 */
    public Set<String> lockedCurrencies() {
        return Set.copyOf(lockedCurrencies.keySet());
    }

    public EscrowLedger escrow() {
        return escrow;
    }

    /** 账上出现过的所有货币，对账用：有余额的、有累计的、有托管的、锁住的。 */
    public Set<String> currencyIds() {
        Set<String> out = new TreeSet<>(balances.keySet());
        out.addAll(supply.keySet());
        out.addAll(lockedCurrencies.keySet());
        for (EscrowLedger.Entry e : escrow.snapshot().values()) out.add(e.currencyId());
        return out;
    }

    /** {铸造累计, 销毁累计}，对账用。 */
    public long[] supply(String currencyId) {
        long[] s = supply.get(currencyId);
        return s == null ? new long[]{0, 0} : s.clone();
    }

    /** 开服时接上流水的存档点。 */
    public void onSave(Runnable hook) {
        this.onSave = hook;
    }

    /** 整份锁住时永远不脏：MC 就不会拿这一份去盖原文件。 */
    @Override
    public boolean isDirty() {
        return wholeLock == null && super.isDirty();
    }

    // ---------------------------------------------------------------- BalanceStore

    @Override
    public String unavailableReasonKey(String currencyId) {
        return wholeLock != null || lockedCurrencies.containsKey(currencyId) ? KEY_LOCKED : null;
    }

    @Override
    public long get(UUID player, String currencyId) {
        requireUnlocked(currencyId);
        Map<UUID, Long> m = balances.get(currencyId);
        return m == null ? 0 : m.getOrDefault(player, 0L);
    }

    /** 余额为 0 的条目不留：离线玩家收过一次钱又花光，不该在存档里占一行一辈子。 */
    @Override
    public void set(UUID player, String currencyId, long value) {
        requireUnlocked(currencyId);
        Map<UUID, Long> m = balances.computeIfAbsent(currencyId, k -> new HashMap<>());
        if (value == 0) m.remove(player);
        else m.put(player, value);
        if (m.isEmpty()) balances.remove(currencyId);
        setDirty();
    }

    @Override
    public Map<UUID, Long> all(String currencyId) {
        requireUnlocked(currencyId);
        Map<UUID, Long> m = balances.get(currencyId);
        return m == null ? Map.of() : Map.copyOf(m);
    }

    // provider 在动账之前就判过锁；走到这里说明有人绕过了它，宁可炸也不许往锁住的账上写
    private void requireUnlocked(String currencyId) {
        if (unavailableReasonKey(currencyId) != null) {
            throw new IllegalStateException("货币 " + currencyId + " 的存档锁住了，不许读写");
        }
    }

    // ---------------------------------------------------------------- TxnLog.Journal

    @Override
    public void recorded(String currencyId, TxnLog.Kind kind, long amount, TxnResult result) {
        if (result != TxnResult.OK) return;
        setDirty();
        if (kind != TxnLog.Kind.MINT && kind != TxnLog.Kind.BURN) return;
        long[] s = supply.computeIfAbsent(currencyId, k -> new long[2]);
        int i = kind == TxnLog.Kind.MINT ? 0 : 1;
        // 累计撑破 long 时封顶而不是抛：钱已经动了，这里再抛只会让余额与累计一个改了一个没改
        long next = s[i] + amount;
        if (((s[i] ^ next) & (amount ^ next)) < 0) {
            MCphone.LOGGER.error("[MCphone] 货币 {} 的{}累计撑破了 long，封顶；对账会显示不平", currencyId,
                    i == 0 ? "铸造" : "销毁");
            next = Long.MAX_VALUE;
        }
        s[i] = next;
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected CompoundTag write(CompoundTag tag) {
        tag.putInt("dataVersion", DATA_VERSION);
        tag.putLong("generation", ++generation);
        savedAt = Math.max(savedAt, clock.getAsLong());
        tag.putLong("savedAt", savedAt);

        CompoundTag currencies = new CompoundTag();
        Set<String> ids = new TreeSet<>(balances.keySet());
        ids.addAll(supply.keySet());
        for (String id : ids) {
            CompoundTag c = new CompoundTag();
            CompoundTag bal = new CompoundTag();
            balances.getOrDefault(id, Map.of()).forEach((p, v) -> bal.putLong(p.toString(), v));
            c.put("balances", bal);
            long[] s = supply(id);
            c.putLong("minted", s[0]);
            c.putLong("burned", s[1]);
            currencies.put(id, c);
        }
        lockedCurrencies.forEach((id, raw) -> {
            if (raw != null) currencies.put(id, raw.copy());
        });
        tag.put("currencies", currencies);

        ListTag list = new ListTag();
        escrow.snapshot().forEach((id, e) -> {
            CompoundTag t = new CompoundTag();
            t.putString("id", id.toString());
            t.putString("owner", e.owner().toString());
            t.putString("beneficiary", e.beneficiary().toString());
            t.putString("currency", e.currencyId());
            t.putLong("amount", e.amount());
            t.putLong("createdAt", e.createdAt());
            t.putBoolean("settled", e.settled());
            if (e.settled()) t.putLong("settledAt", e.settledAt());
            list.add(t);
        });
        for (Tag raw : lockedEscrow) list.add(raw.copy());
        tag.put("escrow", list);
        if (snapshotFile != null) writeSnapshot(tag);
        onSave.run();
        return tag;
    }

    /**
     * 同一个 tag 先原子写快照，再交给 MC 写 SavedData。写失败不抛：SavedData 那份照写，它的代数更新，下次开服会选它。
     * 失败时上一份快照挪成 {@code .stale}：留在原处，SavedData 之后再被写坏时开服会拿它当"完整的那份"静默回滚；
     * 删掉，Forge 1.20.1 / Fabric 这一次就地写 SavedData 被杀的话就一份完整副本都不剩了。
     */
    private void writeSnapshot(CompoundTag tag) {
        Path stale = EconomySnapshot.stalePath(snapshotFile);
        try {
            EconomySnapshot.write(snapshotFile, tag);
        } catch (java.io.IOException e) {
            String what;
            if (present(snapshotFile)) {
                try {
                    Files.move(snapshotFile, stale, StandardCopyOption.REPLACE_EXISTING);
                    what = "上一份快照挪到 " + stale;
                } catch (java.io.IOException moved) {
                    what = "上一份快照 " + snapshotFile + " 挪不开（" + moved + "）：SavedData 再坏的话开服会回滚到它";
                }
            } else {
                what = present(stale) ? "上一份快照留在 " + stale + "（没核对它读不读得出）" : "盘上没有别的快照";
            }
            MCphone.LOGGER.error("[MCphone] 货币的原子快照写不进去（{}），这次只有 SavedData 那份；{}", e.toString(), what);
            return;
        }
        try {
            Files.deleteIfExists(stale);
        } catch (java.io.IOException e) {
            MCphone.LOGGER.warn("[MCphone] 过时的快照 {} 删不掉（{}）", stale, e.toString());
        }
    }

    /** 给测试用：不经过 MC 的存档机制，拿到这一份写出去会是什么样。 */
    public CompoundTag toTag() {
        return write(new CompoundTag());
    }

    /**
     * 从存档读回来。<b>不抛</b>：读不懂的部分按上面的规则锁住，原样留着写回去。
     * （抛了也兜得住 —— 原版会转头调 {@link #get} 里那个 create，而文件在，于是整份锁住 —— 但那样就丢了"只锁一种"的精度。）
     */
    public static EconomyData load(CompoundTag tag, LongSupplier clock) {
        return load(tag, clock, null);
    }

    /** {@code source} 说这份 tag 是从哪个文件读的，写进锁住原因：只有一份坏时，服主据此只挪走坏的那份。 */
    static EconomyData load(CompoundTag tag, LongSupplier clock, String source) {
        String origin = source == null ? "" : source + "：";
        if (!tag.contains("dataVersion", Tag.TAG_INT)) {
            return locked(origin + "存档里没有 dataVersion（这一版起每份都写），认不出是什么格式，不猜", clock);
        }
        int v = tag.getInt("dataVersion");
        if (v > DATA_VERSION) {
            return locked(origin + "存档是更新版本的 MCphone 写的（格式第 " + v + " 版，这一版只认到第 " + DATA_VERSION
                    + " 版）；不降级、不覆盖，装回新版本就好", clock);
        }
        CompoundTag t = tag;
        for (int from = v; from < DATA_VERSION; from++) {
            UnaryOperator<CompoundTag> step = MIGRATIONS.get(from);
            if (step == null) return locked(origin + "存档格式第 " + from + " 版没有迁移到第 " + (from + 1) + " 版的办法", clock);
            try {
                t = step.apply(t.copy());
            } catch (RuntimeException e) {
                return locked(origin + "存档格式从第 " + from + " 版迁移失败：" + e, clock);
            }
        }

        EconomyData d = new EconomyData(clock, null);
        d.generation = generationOf(t);
        d.savedAt = t.contains("savedAt", Tag.TAG_LONG) ? t.getLong("savedAt") : 0;

        Map<String, Tag> rawCurrencies = new LinkedHashMap<>();
        if (t.contains("currencies")) {
            if (!t.contains("currencies", Tag.TAG_COMPOUND)) return locked(origin + "currencies 不是一张表", clock);
            CompoundTag cs = t.getCompound("currencies");
            for (String id : cs.getAllKeys()) {
                Tag raw = cs.get(id);
                rawCurrencies.put(id, raw);
                String why = canonicalCurrency(id) ? d.readCurrency(id, raw) : "货币 id 不是规范写法";
                if (why != null) d.lockCurrency(id, raw, why);
            }
        }

        if (t.contains("escrow")) {
            if (!t.contains("escrow", Tag.TAG_LIST)) return locked(origin + "escrow 不是一个列表", clock);
            ListTag list = t.getList("escrow", Tag.TAG_COMPOUND);
            if (list.size() != ((ListTag) t.get("escrow")).size()) {
                return locked(origin + "escrow 里有不是表的条目", clock);
            }
            Map<UUID, EscrowLedger.Entry> ok = new LinkedHashMap<>();
            List<CompoundTag> pending = new ArrayList<>();
            // 重新计时的基准：读档时刻与历次保存最晚时刻里晚的那个 —— 读档时钟慢了（主板电池没电、世界搬到时钟偏后的机器上）
            // 也不会把托管的建立时刻往前挪，往前挪就是提前退款
            long now = clock.getAsLong();
            long base = Math.max(now, d.savedAt);
            Map<UUID, Long> fixed = new LinkedHashMap<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                if (!e.contains("currency", Tag.TAG_STRING)) {
                    return locked(origin + "第 " + (i + 1) + " 笔托管读不出是哪种货币，不知道该锁哪一种", clock);
                }
                String currency = e.getString("currency");
                // 大写之类的 id 不锁的话，这笔就成了哪种货币都不认领的孤儿
                String why = canonicalCurrency(currency) ? readEscrow(e, ok, base, fixed) : "的货币 id 不是规范写法";
                if (why != null) d.lockCurrency(currency, rawCurrencies.get(currency), "第 " + (i + 1) + " 笔托管" + why);
                pending.add(e);
            }
            // 锁住的货币（不管是余额读坏还是托管读坏）它的托管条目一条都不放进账本，原样留着写回
            Map<UUID, EscrowLedger.Entry> live = new LinkedHashMap<>();
            for (CompoundTag e : pending) {
                String currency = e.getString("currency");
                if (d.lockedCurrencies.containsKey(currency)) {
                    d.lockedEscrow.add(e);
                } else {
                    UUID id = UUID.fromString(e.getString("id"));
                    live.put(id, ok.get(id));
                }
            }
            d.escrow.restore(live);
            // 改正过的建立时刻要落盘：不落的话每次开服都按那一刻重新计时，这笔永远等不到期。
            // 只算真正进了账本的：锁住的货币原样写回，改了也不落盘，标脏只会每次开服白白重写一遍
            fixed.keySet().retainAll(live.keySet());
            if (!fixed.isEmpty()) {
                MCphone.LOGGER.warn("[MCphone] {} 笔托管的建立时刻比基准晚一个超时周期以上或不是正数（手改、写坏，或建它们时时钟快过、"
                        + "保存前又被拨回），按 {} 重新计时：{}", fixed.size(), base, fixed);
                d.setDirty();
            }
            // 时钟慢了与之前时钟快过分不出来。按不提前动钱的那边办，但要说出来：不说的话服主只会看到托管一直不退
            long latest = 0;
            for (EscrowLedger.Entry e : live.values()) {
                if (!e.settled() && e.createdAt() - CLOCK_SLACK_MS > now) {
                    d.aheadOfClock++;
                    latest = Math.max(latest, e.createdAt());
                }
            }
            if (d.aheadOfClock > 0) {
                MCphone.LOGGER.error("[MCphone] ⚠ {} 笔托管的建立时刻晚于读档时刻 {}（最晚 {}）：要么这台机器的时钟慢了，要么建它们时时钟快过。"
                        + "托管不提前退款；时钟快过的话，它们要等到建立时刻再过 {} 天才自动退。请核对系统时钟",
                        d.aheadOfClock, java.time.Instant.ofEpochMilli(now), java.time.Instant.ofEpochMilli(latest),
                        EscrowLedger.DEFAULT_TIMEOUT_MS / 86_400_000L);
            }
        }
        return d;
    }

    /** 读一种货币。读不懂返回原因，读懂了返回 null。 */
    private String readCurrency(String id, Tag raw) {
        if (!(raw instanceof CompoundTag c)) return "不是一张表";
        Map<UUID, Long> m = new HashMap<>();
        if (c.contains("balances")) {
            if (!c.contains("balances", Tag.TAG_COMPOUND)) return "balances 不是一张表";
            CompoundTag bal = c.getCompound("balances");
            for (String k : bal.getAllKeys()) {
                UUID p = canonicalUuid(k);
                // 非规范写法（大写、省了前导零）会被 fromString 归一：两个键落到同一个人，后一个静默盖掉前一个
                if (p == null) return "balances 里 " + k + " 不是规范写法的 UUID";
                if (!bal.contains(k, Tag.TAG_LONG)) return "balances 里 " + k + " 的余额不是 long";
                long value = bal.getLong(k);
                if (value != 0) m.put(p, value);
            }
        }
        long[] s = new long[2];
        String[] keys = {"minted", "burned"};
        for (int i = 0; i < 2; i++) {
            if (!c.contains(keys[i])) continue;
            if (!c.contains(keys[i], Tag.TAG_LONG)) return keys[i] + " 不是 long";
            s[i] = c.getLong(keys[i]);
            if (s[i] < 0) return keys[i] + " 是负数";
        }
        if (!m.isEmpty()) balances.put(id, m);
        if (s[0] != 0 || s[1] != 0) supply.put(id, s);
        return null;
    }

    /** 读一笔托管。读不懂返回原因。 */
    private static String readEscrow(CompoundTag e, Map<UUID, EscrowLedger.Entry> out, long base, Map<UUID, Long> fixed) {
        UUID id = canonicalUuid(e.getString("id"));
        UUID owner = canonicalUuid(e.getString("owner"));
        UUID beneficiary = canonicalUuid(e.getString("beneficiary"));
        if (id == null || owner == null || beneficiary == null) return "的 id / owner / beneficiary 不是规范写法的 UUID";
        if (!e.contains("amount", Tag.TAG_LONG) || !e.contains("createdAt", Tag.TAG_LONG)) {
            return "缺 amount 或 createdAt，或者不是 long";
        }
        if (e.getLong("amount") <= 0) return "的金额不是正数";
        long createdAt = e.getLong("createdAt");
        // settled 缺了不取默认：按"没结清"读，放过款的那笔就能再放一次
        if (!e.contains("settled", Tag.TAG_BYTE)) return "缺 settled，或者不是布尔";
        if (out.containsKey(id)) return "的号 " + id + " 重复了";
        boolean settled = e.getBoolean("settled");
        // 超时判断是 now - createdAt ≥ 超时：建立时刻远在将来或不是正数（负得离谱会溢出），这笔就永远不会被退款。
        // 没结清的改成基准时刻、从那时起再等满一个超时周期；不锁 —— 为一笔时间戳停掉整种货币代价太大。
        // 基准不早于历次保存的最晚时刻，托管总是建好之后才被存进盘：只要时钟没在两次保存之间来回跳，改成基准不会比真实建立时刻后
        // 一个超时周期更早退款。走到这一支的是手改、写坏的时刻，
        // 或者建托管时时钟快过、保存前又被拨回；时钟快着就存过档的，savedAt 跟着快，不走这一支（见 savedAt）。
        // 晚于基准一个超时周期以内的原样收。已结清的不会再到期，不管它。比较写成减法：基准接近 long 上限时 base + 超时会溢出成负数
        if (!settled && (createdAt <= 0 || createdAt - EscrowLedger.DEFAULT_TIMEOUT_MS > base)) {
            fixed.put(id, createdAt);
            createdAt = base;
        }
        // settledAt 只决定已结清的条目留多久，缺了按建立时刻算，不动钱；但写了就得是 long
        if (e.contains("settledAt") && !e.contains("settledAt", Tag.TAG_LONG)) return "的 settledAt 不是 long";
        long settledAt = !settled ? 0 : e.contains("settledAt") ? e.getLong("settledAt") : e.getLong("createdAt");
        out.put(id, new EscrowLedger.Entry(owner, beneficiary, e.getString("currency"),
                e.getLong("amount"), createdAt, settled, settledAt));
        return null;
    }

    /** 货币 id 要是规范写法的 ResourceLocation（带命名空间、全小写），与 {@code CurrencySpec} 读进来的一致。 */
    private static boolean canonicalCurrency(String id) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        return rl != null && rl.toString().equals(id);
    }

    /** 只认 {@link UUID#toString()} 的规范写法；别的写法（大写、省了前导零）返回 null。 */
    private static UUID canonicalUuid(String s) {
        try {
            UUID u = UUID.fromString(s);
            return u.toString().equals(s) ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 把一种货币锁住：已经读进来的余额与累计撤掉，原样留着写回。 */
    private void lockCurrency(String id, Tag raw, String why) {
        if (lockedCurrencies.containsKey(id)) return;
        MCphone.LOGGER.error("[MCphone] ⚠⚠ 货币 {} 的存档读坏了（{}），这种货币锁住：操作一律 UNAVAILABLE，原始数据原样写回 ⚠⚠",
                id, why);
        balances.remove(id);
        supply.remove(id);
        // 只有托管、没有余额段的货币也可能被锁：那时原样就是"没有这一段"，写回时也不写
        lockedCurrencies.put(id, raw == null ? null : raw.copy());
    }
}
