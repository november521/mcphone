package com.november.mcphone.core.script.engine;

/**
 * 进日志与异常文案之前的字符串过滤（S15h/S15i 任务 9、10，勘误 E35⑤）。
 *
 * <h2>为什么日志里也要过滤</h2>
 *
 * 一行日志不是纯数据：{@code U+2028} / {@code U+2029} 是 JS 与部分编辑器眼里的换行，
 * 写进去之后"一条日志"会变成两条（把伪造的下一行塞进别人的日志里）；
 * {@code U+202E}（RIGHT-TO-LEFT OVERRIDE）能把后面的字倒过来写，
 * 让"provider 抛了 XXX"看起来像另一件事；{@code U+00A7} 是 Minecraft 的样式控制符
 * （游戏内与日志着色器都认它），它会改掉整行显示。
 *
 * <p>来源都是<b>不可信的</b>：provider 的 {@code getMessage()}、脚本递进来的 appId / 货币 id、
 * 异常文本。所以统一在这里过一道，而不是在十几个调用点各写一次。
 *
 * <p>过滤是<b>替换不是删除</b>：删掉会让"这里有东西"这条信息消失。
 * 换成可见的转义写法，读到日志的人一眼就知道原来是什么。
 */
public final class LogText {

    private LogText() {
    }

    /** 超过这个长度就截断：一条日志不该被一个第三方字符串撑到几百 KB。 */
    public static final int MAX = 512;

    /** 被换掉的字符，写法与替换结果。 */
    private static final char[] DANGEROUS = {'\u2028', '\u2029', '\u202E', '\u00A7', '\n', '\r', '\t'};
    private static final String[] REPLACEMENT = {
            "\\u2028", "\\u2029", "\\u202E", "\\u00A7", "\\n", "\\r", "\\t"};

    /**
     * 过一遍。{@code null} 当作空串 —— 调用点大多是从异常或第三方对象上取值，null 是常态。
     */
    public static String filter(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX));
        int n = Math.min(raw.length(), MAX);
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            String rep = replacementOf(c);
            if (rep != null) {
                out.append(rep);
                continue;
            }
            // 其余 C0/C1 控制字符（含 DEL）也换成可见写法：它们同样能改掉一行的显示
            if ((c < 0x20) || (c >= 0x7F && c <= 0x9F)) {
                out.append(String.format("\\u%04X", (int) c));
                continue;
            }
            out.append(c);
        }
        if (raw.length() > MAX) out.append("…（截断，原长 ").append(raw.length()).append("）");
        return out.toString();
    }

    private static String replacementOf(char c) {
        for (int i = 0; i < DANGEROUS.length; i++) {
            if (DANGEROUS[i] == c) return REPLACEMENT[i];
        }
        return null;
    }
}
