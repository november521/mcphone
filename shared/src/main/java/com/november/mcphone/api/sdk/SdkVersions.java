package com.november.mcphone.api.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 本机装着的各个 SDK 是第几版（施工方案 §23.4）。<b>这是唯一一张表</b>，
 * 各 {@code XxxApi.VERSION} 都从这里取。
 *
 * <h2>为什么每个 SDK 各有一个版本号，而不是共用 MCphoneApi.VERSION</h2>
 *
 * 共用一个号的话，"我要收件箱第 2 版"只能写成"我要 MCphone API 第 37 版"，
 * 而第 37 版里绝大多数东西那个 App 根本不碰。于是每加一个不相干的方法，
 * 所有声明过版本的包都要重新发一遍。
 *
 * <h2>为什么号存在这里而不是各写各的</h2>
 *
 * 11 个各自独立的常量加上 manifest 认得的键集合，就是两份判据 ——
 * 仓库自己在 {@code gradle/mcphone-checks.gradle} 里写过这种坏法："两份判据长得像但不一样，
 * 是最难发现的一种坏：两处都在，都像在工作，只有把它们并排放才看得出少了什么"。
 * 收成一张表之后，{@code docs/SdkGateTest.java} 能直接断言"这张表 ↔ 11 个 Api 类 ↔ manifest 认得的键"三者双射。
 *
 * <h2>为什么这些 VERSION 都在静态块里赋值</h2>
 *
 * {@code static final int X = 1;} 是<b>编译期常量</b>，javac 会把它内联进读它的那个类 ——
 * 附属编译完，字节码里连一条读这个字段的指令都不剩，于是 {@code MailboxApi.VERSION >= 2}
 * 判断的是"附属编译时看到的 MCphone"而不是运行时装着的那个。
 *
 * <p><b>这个常量真的有 Java 读者</b>，不是仪式：{@code ICurrencyProvider} 按 §22.7 的 adapter 档
 * 就是给附属/经济模组实现的，而 {@code layers/loader/neoforge/docs/AddonApiExamples.java} 里
 * 正教着附属写 {@code if (MCphoneApi.VERSION >= 2)} 这一句。manifest 那条门控不经过 Java，
 * 但 {@code api/} 这个面按定义就是给附属 Java 编译的。
 *
 * <p>顺带一条：<b>版本常量不许挂在接口上</b>。接口字段必须在声明处初始化，没有静态块可用，
 * 挂上去就只能写成字面量，也就只能退回被内联的那个坑。所以 {@code XxxApi} 都是 final class。
 */
public final class SdkVersions {

    private SdkVersions() {
    }

    /** 没有这个 SDK。不用 0：0 与"第 0 版"长得一样，而版本号最小是 1。 */
    public static final int ABSENT = -1;

    private static final Map<String, Integer> V;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        // A 档六项（§23.2）。键就是 manifest 的 sdk 段里写的那个
        m.put("item", 1);
        m.put("player", 1);
        m.put("economy", 1);
        m.put("mailbox", 1);
        m.put("notify", 1);
        m.put("cycle", 1);
        // B 档五项：接口还是空的，但键与版本号现在就要在 —— P2 填实现时不必改 sdk 段的格式（§23.5）
        m.put("groups", 1);
        m.put("waypoints", 1);
        m.put("escrow", 1);
        m.put("stats", 1);
        m.put("resources", 2);
        V = Map.copyOf(m);
    }

    /** 本机这个 SDK 是第几版，没有返回 {@link #ABSENT}。 */
    public static int of(String key) {
        Integer v = key == null ? null : V.get(key);
        return v == null ? ABSENT : v;
    }

    /** 本机有哪些 SDK。 */
    public static Set<String> keys() {
        return V.keySet();
    }
}
