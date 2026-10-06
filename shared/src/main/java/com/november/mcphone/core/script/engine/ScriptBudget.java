package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;

/**
 * 指令预算与墙钟（施工方案 §16.4）。<b>每个 App 一个实例，不共用全局 ContextFactory。</b>
 *
 * <h2>「50 万指令」是什么单位</h2>
 *
 * {@code setInstructionObserverThreshold(N)} 的语义是"每累计 N 个<b>指令单位</b>回调一次，然后清零"，
 * 回调收到的是<b>增量</b>不是累计。Rhino 数的是字节码偏移量的差，不是 JS 语句。实测
 * （{@code var i=0; while(i<200000) i++} 上报累计约 300 万）：
 *
 * <pre>一次最简循环迭代 ≈ 15 个指令单位 → 50 万 ≈ 3.3 万次迭代</pre>
 *
 * 改 {@link #SERVER_INSTRUCTIONS} 之前先看这一行。
 *
 * <h2>墙钟的精度受 threshold 制约</h2>
 *
 * 墙钟只能在观察器回调里检查，所以 threshold 越大越不准：实测 threshold=10000 时 20 ms 的闸
 * 停在 20.2 ms，threshold=1000000 时停在 27.2 ms。{@link #OBSERVER_STEP} 取 10000 是折中。
 *
 * <h2>⚠ 这两道闸只在分支点生效</h2>
 *
 * 单个原生操作里观察器一次都不触发 —— {@code new Int8Array(2e8)} 94 毫秒、
 * {@code JSON.stringify(16M 串)} 419 毫秒，回调都是 0 次。<b>那一类靠 {@link SizeGate} 与
 * {@link ScriptSandbox} 的白名单挡</b>，不是靠这里。
 *
 * <h2>等宿主的时间不算墙钟</h2>
 *
 * 货币调用要回主线程执行（{@code CurrencyGateway}），碰上主线程正在跑 tick 就要等几十毫秒 ——
 * 算进 20 ms 的话，用到货币的脚本几乎都会被当成超时掐掉，而钱已经转出去了。所以等的那段
 * 把截止往后推（{@link #hostWaited}），另记一笔累计，封顶 {@link #SERVER_HOST_WAIT_NANOS}：
 * 墙钟防的是脚本自己算太久，等宿主的上限防的是一次求值把 worker 占住太久。
 *
 * <h2>ClassShutter 在这里设，不在每次求值时设</h2>
 *
 * {@code Context} 是线程绑定的：同一线程第二次 {@code enterContext} 拿到的是同一个 Context，
 * 而它已经有 shutter 了，再设直接 {@code SecurityException}（实测）。
 */
public final class ScriptBudget extends ContextFactory {

    /** 每多少个指令单位回调一次。小了回调开销大，大了墙钟不准。 */
    public static final int OBSERVER_STEP = 10_000;

    /** 服务端每次调用的指令预算（§16.4）。≈ 3.3 万次最简循环迭代。 */
    public static final long SERVER_INSTRUCTIONS = 500_000L;

    /** 客户端每次调用的指令预算（§16.4）。 */
    public static final long CLIENT_INSTRUCTIONS = 2_000_000L;

    /**
     * 服务端每次调用的墙钟（§16.4）。
     *
     * <p>⚠ 一个 tick 只有 50 ms，<b>20 ms 已经是 40% 的 tick 预算，那是上限不是安全值</b>。
     * 求值因此跑在 worker 上而不是主线程，见 {@code RhinoEvaluator}。
     */
    public static final long SERVER_WALL_NANOS = 20_000_000L;

    /** 客户端每次调用的墙钟（§16.4）。 */
    public static final long CLIENT_WALL_NANOS = 50_000_000L;

    /**
     * 服务端一次调用里，累计最多等宿主（主线程）多久。worker 只有两条，一次求值占着它等太久，
     * 别的 App 就排不上。工程常值，实测后再调。
     */
    public static final long SERVER_HOST_WAIT_NANOS = 1_000_000_000L;

    /** 调用栈深度（§16.4）。 */
    public static final int MAX_STACK_DEPTH = 64;

    /** 这一次调用的账。每次求值前 {@link #begin} 一次。 */
    private static final ThreadLocal<long[]> BUDGET = new ThreadLocal<>();
    // [0]=已用指令 [1]=指令上限 [2]=截止纳秒 [3]=已等宿主纳秒 [4]=等宿主上限

    private final long instructions;
    private final long wallNanos;
    private final long hostWaitNanos;

    public ScriptBudget(long instructions, long wallNanos) {
        this(instructions, wallNanos, 0);
    }

    public ScriptBudget(long instructions, long wallNanos, long hostWaitNanos) {
        this.instructions = instructions;
        this.wallNanos = wallNanos;
        this.hostWaitNanos = hostWaitNanos;
    }

    /** 服务端档。 */
    public static ScriptBudget server() {
        return new ScriptBudget(SERVER_INSTRUCTIONS, SERVER_WALL_NANOS, SERVER_HOST_WAIT_NANOS);
    }

    /** 客户端档。客户端没有要回主线程等的宿主调用，等宿主的上限是 0。 */
    public static ScriptBudget client() {
        return new ScriptBudget(CLIENT_INSTRUCTIONS, CLIENT_WALL_NANOS);
    }

    @Override
    protected Context makeContext() {
        Context cx = super.makeContext();
        // 解释模式，指令观察器才生效（§16.3）
        cx.setOptimizationLevel(-1);
        cx.setLanguageVersion(Context.VERSION_ES6);
        cx.setInstructionObserverThreshold(OBSERVER_STEP);
        cx.setMaximumInterpreterStackDepth(MAX_STACK_DEPTH);
        // 断掉一切 Java 类访问。【无条件】—— 放行任何包都等于把沙箱交出去
        cx.setClassShutter(name -> false);
        return cx;
    }

    @Override
    protected void observeInstructionCount(Context cx, int delta) {
        long[] b = BUDGET.get();
        if (b == null) return;
        b[0] += delta;
        if (b[0] > b[1]) {
            throw new ScriptAbort(ScriptAbort.Reason.INSTRUCTIONS, b[0] + " / " + b[1] + " 指令单位");
        }
        if (System.nanoTime() > b[2]) {
            throw new ScriptAbort(ScriptAbort.Reason.WALL_CLOCK, (b[2] - System.nanoTime()) / -1_000_000 + " 毫秒");
        }
    }

    /**
     * 开一次调用的账。
     *
     * <p><b>不许嵌套</b>：{@code Context} 是线程绑定的，内层会静默继承外层的预算与 shutter（实测）。
     */
    public void begin() {
        begin(wallNanos);
    }
    public void begin(long maximumWallNanos) {
        // 判据是"这条线程上已经有一本账"，不是 Context.getCurrentContext() != null ——
        // 后者在 enterContext() 之后必然非空，那样每一次正常求值都会被自己拦下
        if (BUDGET.get() != null) {
            throw new IllegalStateException(
                    "脚本求值不许嵌套：Context 是线程绑定的，内层会静默继承外层的指令预算与 ClassShutter");
        }
        BUDGET.set(new long[]{0, instructions, System.nanoTime() + Math.min(wallNanos,maximumWallNanos), 0, hostWaitNanos});
    }

    /** 平账。{@code finally} 里调。 */
    public void end() {
        BUDGET.remove();
    }

    /** 这一次求值还能等宿主多久（纳秒）。不在求值里（主线程直调、测试）就是不限。 */
    public static long hostWaitLeftNanos() {
        long[] b = BUDGET.get();
        return b == null ? Long.MAX_VALUE : b[4] - b[3];
    }

    /** 等了宿主这么久：记进累计，并把墙钟截止往后推同样的时长 —— 这段时间脚本没在算。 */
    public static void hostWaited(long nanos) {
        long[] b = BUDGET.get();
        if (b == null || nanos <= 0) return;
        b[3] += nanos;
        b[2] += nanos;
    }

    /** 这一次求值离墙钟截止还有多久（纳秒）。只给测试用。 */
    public static long wallLeftNanos() {
        long[] b = BUDGET.get();
        return b == null ? Long.MAX_VALUE : b[2] - System.nanoTime();
    }

    /** 这一次用了多少指令单位。只给审计与测试用。 */
    public static long used() {
        long[] b = BUDGET.get();
        return b == null ? 0 : b[0];
    }
}
