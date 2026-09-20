package com.november.mcphone.core.script.client;

import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.script.server.store.VaultPassphrase;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「设置 → 保险箱」的口令页（施工方案 §17.4.4）。
 *
 * <h2>这一页必须写明的三件事</h2>
 *
 * <ol>
 *   <li>口令<b>永不离开这台机器</b>，不进网络包、不写盘</li>
 *   <li><b>忘了口令 = 数据永久丢失</b>：没有找回、没有提示问题、服主也重置不了</li>
 *   <li>要<b>二次输入</b>确认</li>
 * </ol>
 *
 * 少了第三条那句，玩家会以为"忘了可以找服主" —— 而那正是这套设计做不到的事，
 * 也正是它安全性的来源。
 *
 * <h2>口令在这里怎么存</h2>
 *
 * 用 {@code char[]} 不用 {@code String}：String 在堆上留副本，内存转储里就看得到。
 * 页面一关就 {@link Arrays#fill} 抹掉。<b>任何路径都不写盘、不进日志。</b>
 */
public final class VaultPage {

    private static final int PAD = 6;
    private static final int ROW = 12;

    /** 两个输入框，各自的字符缓冲。 */
    private final List<Character> first = new ArrayList<>();
    private final List<Character> second = new ArrayList<>();

    /** 光标在哪个框里。 */
    private boolean onSecond;

    private boolean backRequested;

    /** 两个输入框的命中区（渲染时更新，已算进滚动偏移）：点框选框，点别处不该抢焦点。 */
    private int boxX, boxW, firstBoxY, secondBoxY;

    /** 可滚区域（渲染时更新）：输入框滚出可见区之后不该还能点到。 */
    private int clipTop, clipBottom;

    /** 正文往上滚了多少像素。0 ＝ 贴着标题。中文三条警告折行后就装不下一屏，得滚。 */
    private int scrollPx;

    /** 上一帧量出来的滚动上限（内容总高只有画完才知道，与 AboutPage 同一套路）。 */
    private int maxScroll;

    public void open() {
        clear();
        onSecond = false;
        backRequested = false;
        scrollPx = 0;
    }

    /** 关页就抹掉。<b>这是口令在内存里存在的全部时间。</b> */
    public void close() {
        clear();
    }

    private void clear() {
        first.clear();
        second.clear();
    }

    /** 取出来交给 {@code VaultClient.unlock}，取完调用方要自己抹掉。 */
    public char[] passphrase() {
        return toChars(first);
    }

    private static char[] toChars(List<Character> src) {
        char[] out = new char[src.size()];
        for (int i = 0; i < out.length; i++) out[i] = src.get(i);
        return out;
    }

    /** 现在能不能提交。 */
    public boolean acceptable() {
        char[] a = toChars(first);
        char[] b = toChars(second);
        try {
            return VaultPassphrase.acceptable(a, b);
        } finally {
            Arrays.fill(a, '\0');
            Arrays.fill(b, '\0');
        }
    }

    /** 两次输入一不一样（空框不算"不一致"）。 */
    private boolean entriesMatch() {
        if (first.isEmpty() || second.isEmpty()) return true;
        char[] a = toChars(first);
        char[] b = toChars(second);
        try {
            return VaultPassphrase.matches(a, b);
        } finally {
            Arrays.fill(a, '\0');
            Arrays.fill(b, '\0');
        }
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {
        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + PAD;
        int w = screenW - PAD * 2;
        boxX = x;
        boxW = w;

        // 标题固定，正文可滚：三条必须显示的警告在中文下就装不下一屏（英文更长），
        // 原先画到导航栏底下就被盖住、永远看不到（与 AboutPage 同一个修法）
        g.drawString(font, Component.translatable("mcphone.vault.title").getString(),
                x, y, PhoneTheme.FONT_COLOR_STATUS, false);
        y += ROW + 2;

        clipTop = y;
        clipBottom = phoneTop + screenH - navH;
        scrollPx = Mth.clamp(scrollPx, 0, maxScroll);
        y -= scrollPx;

        // 裁掉滚出去的部分，否则正文会画到状态栏和导航栏上
        GuiUtil.enableScissor(g, x, clipTop, x + w, clipBottom);

        firstBoxY = y + ROW;
        y = field(g, font, x, y, w, Component.translatable("mcphone.vault.enter").getString(), first, !onSecond);
        secondBoxY = y + ROW;
        y = field(g, font, x, y, w, Component.translatable("mcphone.vault.confirm").getString(), second, onSecond);

        // 强度提示：只提示，不拦；拦的只有长度与两次一致
        char[] a = toChars(first);
        try {
            g.drawString(font, Component.translatable(VaultPassphrase.strength(a).key).getString(),
                    x, y, PhoneTheme.FONT_COLOR_NAV, false);
        } finally {
            Arrays.fill(a, '\0');
        }
        y += ROW;

        // 两次不一致就要说出来，与长度差没关系（长度不同也是不一致）
        if (!entriesMatch()) {
            g.drawString(font, Component.translatable("mcphone.vault.mismatch").getString(),
                    x, y, PhoneTheme.FONT_COLOR_CHAT_SEND, false);
        }
        y += ROW + 2;

        // 三条必须出现的文案（§17.4.4），一条都不许省
        for (String key : new String[]{VaultPassphrase.KEY_WARN_NEVER_LEAVES,
                VaultPassphrase.KEY_WARN_NO_RECOVERY, VaultPassphrase.KEY_WARN_CONFIRM}) {
            for (var line : font.split(Component.translatable(key), w)) {
                g.drawString(font, line, x, y, PhoneTheme.FONT_COLOR_NAV, false);
                y += font.lineHeight;
            }
            y += 2;
        }

        GuiUtil.disableScissor(g);

        // 这一帧画到哪儿，就是内容有多高；下一帧的滚动上限按它来
        maxScroll = Math.max(0, (y + scrollPx) - clipBottom);
    }

    /** 一个输入框。<b>只画星号</b>，不画明文 —— 旁边站着人也看不到。 */
    private int field(GuiGraphics g, Font font, int x, int y, int w, String label,
                      List<Character> buf, boolean focused) {
        g.drawString(font, label, x, y, PhoneTheme.FONT_COLOR_NAV, false);
        y += ROW;
        g.fill(x, y, x + w, y + ROW, focused ? PhoneTheme.COLOR_BUTTON_HOVER : PhoneTheme.COLOR_BUTTON);
        g.drawString(font, "*".repeat(buf.size()), x + 2, y + 2, PhoneTheme.FONT_COLOR_BUTTON, false);
        return y + ROW + 4;
    }

    /**
     * 点输入框选框。点在别处不该改焦点 —— 以前是"点哪都切换"，在手机里点导航栏也会把
     * 光标切走，回来继续打字就打进了另一个框。滚出可见区的框也点不到（命中区按裁剪区挡一道）。
     */
    public boolean mouseClicked(double mx, double my, int button) {
        if (mx < boxX || mx > boxX + boxW) return false;
        if (my < clipTop || my > clipBottom) return false;
        if (my >= firstBoxY && my <= firstBoxY + ROW) {
            onSecond = false;
            return true;
        }
        if (my >= secondBoxY && my <= secondBoxY + ROW) {
            onSecond = true;
            return true;
        }
        return false;
    }

    /** 滚轮。一次三行，跟原版列表手感一致 */
    public boolean mouseScrolled(double scrollY, Font font) {
        int step = font.lineHeight * 3;
        int before = scrollPx;
        scrollPx = Mth.clamp(scrollPx - (int) (scrollY * step), 0, maxScroll);
        return scrollPx != before;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        List<Character> buf = onSecond ? second : first;
        if (keyCode == 259 && !buf.isEmpty()) {          // backspace
            buf.remove(buf.size() - 1);
            return true;
        }
        if (keyCode == 258) {                             // tab
            onSecond = !onSecond;
            return true;
        }
        if (keyCode == 256) {                             // esc
            backRequested = true;
            return true;
        }
        return false;
    }

    public boolean charTyped(char c, int modifiers) {
        if (c < ' ') return false;
        List<Character> buf = onSecond ? second : first;
        if (buf.size() >= 128) return true;               // 够长了，再长只是负担
        buf.add(c);
        return true;
    }

    public boolean consumeBackRequest() {
        boolean r = backRequested;
        backRequested = false;
        return r;
    }
}
