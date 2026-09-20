package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.TxnReason;
import com.november.mcphone.api.economy.TxnResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 流水（施工方案 §22.10）。<b>一天一个文件，追加写。</b>
 *
 * <pre>world/mcphone/economy/ledger/&lt;yyyy-MM-dd&gt;.log</pre>
 *
 * <p>字段顺序照 §22.10 的原文：
 * {@code 时间 | 货币id | 类型 | from | to | 金额 | appId | kind | ref | 结果}。
 * 以 {@code #} 开头的是注释行（存档点、重启说明），不满 10 格。
 *
 * <h2>这是经济系统唯一的安全网</h2>
 *
 * 没有它，通胀发生了也没人知道从哪来（§22.10）。所以<b>每一笔变动都要进</b>（§22.3 ⑥），
 * 包括失败的那些 —— 失败的尝试正是查"谁在试探"的依据。
 *
 * <h2>appId 由宿主盖章，不采信调用方</h2>
 *
 * {@link TxnReason} 上<b>没有</b> appId 这一格（S11 定的）：让被监督的一方填写自己是谁，
 * 安全网就不成其为网。appId 由调用上下文传进来，写这一行时拼上。
 */
public final class TxnLog {

    /** 保留多少天（§22.10）。 */
    public static final int RETENTION_DAYS = 90;

    /** 单日文件上限，超出轮转（§22.10）。 */
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    /** 分隔符。{@link TxnReason} 的两个字段在构造时就拒了它与换行，所以这一行不会被伪造。 */
    private static final char SEP = '|';

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path dir;
    private final ZoneId zone;
    private final Journal journal;

    public TxnLog(Path economyDir, ZoneId zone) {
        this(economyDir, zone, null);
    }

    public TxnLog(Path economyDir, ZoneId zone, Journal journal) {
        this.dir = economyDir.resolve("ledger");
        this.zone = zone;
        this.journal = journal;
    }

    /**
     * 每记一行都告诉它（实现在 {@link EconomyData}，与余额同一次落盘）：
     * <ul>
     *   <li>成功的 mint / burn 记进累计 —— 对账读累计，不读流水文件：流水 {@link #RETENTION_DAYS} 天后会被清掉，
     *       强杀之后还会比存档超前一截，拿它对账要么早晚不平、要么重启之后不平。</li>
     *   <li>任何一笔成功都标脏 —— 下一次世界保存必定写存档点（{@link #checkpoint}）。计分板档的转账不碰这份存档，
     *       不标脏的话它们永远排在最后一个存档点之后，正常停服再开也会被当成"没进存档"。</li>
     * </ul>
     */
    public interface Journal {
        void recorded(String currencyId, Kind kind, long amount, TxnResult result);
    }

    /** 一笔变动的种类。 */
    public enum Kind {
        TRANSFER, MINT, BURN, HOLD, RELEASE, REFUND
    }

    /** 记一行。成功的 mint / burn 同时记进 {@link Journal} 的累计 —— 与余额的改动在同一个主线程操作里。 */
    public void append(Instant at, String currencyId, Kind kind, UUID from, UUID to,
                       long amount, String appId, TxnReason reason, TxnResult result) {
        if (at == null || kind == null || result == null) {
            throw new IllegalArgumentException("流水必填字段为空");
        }
        requireField(currencyId, "currencyId");
        if (appId != null) requireField(appId, "appId");
        if (reason != null) {
            requireField(reason.kind(), "reason.kind");
            requireField(reason.ref(), "reason.ref");
        }
        if (journal != null) journal.recorded(currencyId, kind, amount, result);
        String line = String.join(String.valueOf(SEP),
                at.toString(),
                currencyId,
                kind.name().toLowerCase(java.util.Locale.ROOT),
                from == null ? "-" : from.toString(),
                to == null ? "-" : to.toString(),
                Long.toString(amount),
                appId == null ? "-" : appId,
                reason == null ? "-" : reason.kind(),
                reason == null ? "-" : reason.ref(),
                result.name());
        write(at, line);
    }

    /** 防御新调用点绕过 TxnReason 等上游校验，破坏竖线分隔的审计格式。 */
    private static void requireField(String value, String name) {
        if (value == null || value.indexOf(SEP) >= 0) {
            throw new IllegalArgumentException(name + " 不是合法流水字段");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\u2028' || c == '\u2029') {
                throw new IllegalArgumentException(name + " 不是合法流水字段");
            }
        }
    }

    /**
     * 存档点那一行的开头。{@link #sumMintAndBurn} 这类按竖线切的解析，切出来不满 10 格，自然跳过。
     * <b>只用 ASCII</b>：流水被人用别的编码另存过，中文标记就成了乱码、再也认不出来。
     */
    static final String CHECKPOINT = "# checkpoint ";

    /**
     * 世界保存时写一行存档点（{@link EconomyData} 在序列化时调）。开服时拿它判断流水比存档超前了几笔。
     *
     * <p>序列化与真正写盘之间被强杀的话，这一行会多说一次"存过了"，那一个保存周期里的变动就漏报了。
     */
    public void checkpoint(Instant at) {
        write(at, CHECKPOINT + at);
    }

    /**
     * 开服时调：上一个存档点之后还有成功的变动，说明它们没进存档（强杀或崩溃），写一行标出来。
     * 不标出来，服主会拿着一行「A 付给 B 100」去对一笔并没有生效的账。
     *
     * <p>最后<b>要补一个存档点</b>：开服这一刻盘上的存档就是现状。不补的话，重启后一直没有成功变动时存档不脏、
     * 不会写存档点，下次开服又把同一批行报一遍。两种情况不补：还没有流水目录（从没用过货币的世界，别为它建目录），
     * 以及流水读不出来（补了就再也报不出那批没进存档的行）。没有目录的世界，第一次写流水时由 {@link #write} 先补一个。
     *
     * <p>往回最多找 {@link #MAX_SCAN_FILES} 个文件：还找不到存档点就是老流水、判断不了，不报。
     *
     * @return 没进存档的成功变动有几笔；流水读不出来是 -1
     */
    public int noteRestart(Instant now) {
        if (!Files.isDirectory(dir)) return 0;
        int unsaved = unsavedSinceCheckpoint();
        if (unsaved < 0) return -1;
        if (unsaved > 0) {
            write(now, "# " + now + " 重启：上一个存档点之后有 " + unsaved
                    + " 笔成功的变动没进存档（强杀或崩溃），以存档为准");
        }
        checkpoint(now);
        return unsaved;
    }

    /** 往回最多找几个流水文件去找存档点。只有失败尝试的日子也会各留一个文件，只看两个不够。 */
    static final int MAX_SCAN_FILES = 32;

    /** 一行最多读多少字符。正常一行不到 1 KB；断电留下的没有换行的尾巴可以有几十兆，不许为它把整段读进内存。 */
    private static final int MAX_LINE_CHARS = 8192;

    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2})(?:\\.(\\d{1,6}))?\\.log");

    /**
     * 最后一个存档点之后的成功变动有几笔；往回找了 {@link #MAX_SCAN_FILES} 个文件都没有存档点是 0（老流水，判断不了），
     * 读不出来是 -1。从新往旧一个文件一个文件地找，每个文件逐行流式读。
     */
    private int unsavedSinceCheckpoint() {
        List<Path> files;
        try {
            files = filesOldestFirst();
        } catch (IOException e) {
            com.november.mcphone.MCphone.LOGGER.warn("[MCphone] 读流水失败: {}", e.toString());
            return -1;
        }
        int newer = 0;
        for (int i = files.size() - 1, seen = 0; i >= 0 && seen < MAX_SCAN_FILES; i--, seen++) {
            int[] r = scan(files.get(i));
            if (r == null) return -1;
            if (r[0] == 1) return r[1] + newer;
            newer += r[1];
        }
        return 0;
    }

    /** {有没有存档点, 最后一个存档点之后（没有存档点就是整个文件）的成功变动数}；读不出来返回 null。 */
    private static int[] scan(Path p) {
        try (Lines r = new Lines(p)) {
            boolean found = false;
            int ok = 0;
            String line;
            while ((line = r.next()) != null) {
                if (line.startsWith(CHECKPOINT)) {
                    found = true;
                    ok = 0;
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length >= 10 && "OK".equals(f[9])) ok++;
            }
            return new int[]{found ? 1 : 0, ok};
        } catch (IOException e) {
            com.november.mcphone.MCphone.LOGGER.warn("[MCphone] 读流水失败: {}", e.toString());
            return null;
        }
    }

    /**
     * 流水文件从旧到新。<b>按文件名里的日期与轮转序号排，不看修改时间</b>：复制、迁移世界时修改时间会被改掉，文件名不会。
     * 名字不是我们写的格式（{@code xxx - 副本.log} 之类）的不算流水，不读：算进来会每次开服都把它报一遍。
     * 日期是写流水那台机器的时区：换了时区之后新的行可能落进日期更早的文件，那一次开服报的数会错 —— 只影响那一行说明，不动钱。
     */
    private List<Path> filesOldestFirst() throws IOException {
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) {
                if (NAME.matcher(p.getFileName().toString()).matches()) files.add(p);
            }
        }
        files.sort(java.util.Comparator.comparing(TxnLog::orderKey));
        return files;
    }

    private static String orderKey(Path p) {
        java.util.regex.Matcher m = NAME.matcher(p.getFileName().toString());
        if (!m.matches()) throw new IllegalArgumentException("不是流水文件名：" + p);   // filesOldestFirst 只收认得的名字
        int idx = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return m.group(1) + "#" + String.format("%06d", idx);
    }

    /**
     * 逐行读：一行超过 {@link #MAX_LINE_CHARS} 的部分丢掉，行尾的 {@code \r} 与行首的 BOM 去掉。
     * 坏字节换成 U+FFFD 接着读（{@code Files.newBufferedReader} 会直接报错）：写到一半断电的文件不至于整个读不出。
     * 按块读进数组再找换行 —— 逐字符调 {@code Reader.read()} 慢近十倍，开服时在主线程上读几十兆就是好几秒。
     */
    private static final class Lines implements java.io.Closeable {
        private final java.io.Reader in;
        private final char[] buf = new char[8192];
        private int pos, len;

        Lines(Path p) throws IOException {
            in = new java.io.InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8);
        }

        /** 读完了返回 null。 */
        String next() throws IOException {
            StringBuilder b = new StringBuilder();
            boolean any = false;
            while (true) {
                if (pos == len) {
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) break;
                    pos = 0;
                    len = n;
                    continue;
                }
                any = true;
                int start = pos;
                while (pos < len && buf[pos] != '\n') pos++;
                b.append(buf, start, Math.min(pos - start, Math.max(0, MAX_LINE_CHARS - b.length())));
                if (pos < len) {
                    pos++;
                    break;
                }
            }
            if (!any) return null;
            if (b.length() > 0 && b.charAt(b.length() - 1) == '\r') b.setLength(b.length() - 1);
            if (b.length() > 0 && b.charAt(0) == '\uFEFF') b.deleteCharAt(0);
            return b.toString();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private void write(Instant at, String line) {
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                // 头一次写：此刻盘上的存档就是现状（之前没有任何变动），先落一个存档点。
                // 不落的话，这之后到第一次世界保存之间被强杀，开服时找不到存档点，那几笔就永远报不出来。
                // 没接存档的流水（没有 Journal）不补：它没有存档可对，CurrencyTest 也钉着它的文件里只有交易行
                if (journal != null && !line.startsWith(CHECKPOINT)) write(at, CHECKPOINT + at);
            }
            Path f = fileFor(at);
            if (Files.exists(f) && Files.size(f) >= MAX_FILE_BYTES) f = rotated(f);
            Files.writeString(f, encodable(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 写不了流水是要命的（对账就断了），但不能因此把这笔交易也搞砸 —— 交易已经做完了
            com.november.mcphone.MCphone.LOGGER.error("[MCphone] 流水写不进去，对账会断: {}", e.toString());
        }
    }

    /**
     * 孤立的代理字符换成 U+FFFD。{@code reason.ref} 是脚本给的，里面一个孤立的 0xD800 就能让 UTF-8 编码抛异常，
     * 而写失败只记日志 —— 一笔成功的转账就这样不进流水了。
     */
    static String encodable(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean lone = Character.isHighSurrogate(c)
                    ? i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))
                    : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
            if (lone) {
                if (b == null) b = new StringBuilder(s.substring(0, i));
                b.append('\uFFFD');
            } else {
                if (b != null) b.append(c);
            }
        }
        return b == null ? s : b.toString();
    }

    Path fileFor(Instant at) {
        return dir.resolve(DAY.format(at.atZone(zone)) + ".log");
    }

    /** 同一天写满 64 MiB 之后换一个带序号的。 */
    private Path rotated(Path base) throws IOException {
        String name = base.getFileName().toString().replace(".log", "");
        for (int i = 1; i < 1000; i++) {
            Path p = dir.resolve(name + "." + i + ".log");
            if (!Files.exists(p) || Files.size(p) < MAX_FILE_BYTES) return p;
        }
        return base;
    }

    /** 扫掉过保留期的（§22.10）。返回删了几个。 */
    public int sweep(Instant now) {
        if (!Files.isDirectory(dir)) return 0;
        long cutoff = now.minusSeconds(RETENTION_DAYS * 86400L).toEpochMilli();
        int n = 0;
        try (var s = Files.list(dir)) {
            List<Path> old = new ArrayList<>();
            for (Path p : s.toList()) {
                if (Files.getLastModifiedTime(p).toMillis() < cutoff) old.add(p);
            }
            for (Path p : old) {
                Files.deleteIfExists(p);
                n++;
            }
        } catch (IOException e) {
            com.november.mcphone.MCphone.LOGGER.warn("[MCphone] 清流水失败: {}", e.toString());
        }
        return n;
    }

    /** 把所有流水里的 mint 与 burn 加起来，给对账用。 */
    public long[] sumMintAndBurn(String currencyId) {
        long mint = 0, burn = 0;
        if (!Files.isDirectory(dir)) return new long[]{0, 0};
        List<Path> files;
        try {
            files = filesOldestFirst();
        } catch (IOException e) {
            com.november.mcphone.MCphone.LOGGER.warn("[MCphone] 读流水失败: {}", e.toString());
            return new long[]{0, 0};
        }
        // 一个文件读不出只跳过它，不把整个合计打断
        for (Path p : files) {
            try (Lines r = new Lines(p)) {
                String line;
                while ((line = r.next()) != null) {
                    String[] f = line.split("\\|", -1);
                    if (f.length < 10 || !currencyId.equals(f[1])) continue;
                    if (!"OK".equals(f[9])) continue;              // 失败的不算进总量
                    long amt;
                    try {
                        amt = Long.parseLong(f[5]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if ("mint".equals(f[2])) mint += amt;
                    else if ("burn".equals(f[2])) burn += amt;
                }
            } catch (IOException e) {
                com.november.mcphone.MCphone.LOGGER.warn("[MCphone] 读流水失败: {}", e.toString());
            }
        }
        return new long[]{mint, burn};
    }
}
