package com.november.mcphone.feature.hotkey.client;

import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneKeys;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「遥控器」那一页：所有键位的列表，点一下触发一次，点右边的星置顶。
 *
 * <h2>列表里都有什么</h2>
 *
 * {@code mc.options.keyMappings} 装的是<b>全部</b>注册过的键位，不分是原版还是哪个模组的
 * —— 它本来就是原版「按键设置」界面的数据源。这一页把它重新排了一遍：
 *
 * <ul>
 *   <li>先按原版的<b>分类</b>分组（分类名是翻译键，玩家看到的是「移动」「杂项」
 *       或者模组自己的名字）—— 与原版那个界面同一套分组，玩家一眼知道是谁的东西；</li>
 *   <li>被置顶的那几条单独放到最上面那一段，从下面的分类里<b>移走</b>（不重复出现：
 *       同一行在屏幕上出现两次时，玩家会以为点哪个不一样）；</li>
 *   <li>手机上自己那 7 个键位不列 —— 那些是这个 App 自己的开关，列进来只会让人困惑。</li>
 * </ul>
 *
 * <h2>分组可以折叠，默认只放开置顶那一段</h2>
 *
 * 每个分类是一行表头，点一下就展开／收起，表头后面写着这一组有几条。默认<b>只有置顶那一段</b>
 * 是展开的：上百个键位摊开来找不动，而置顶那几条是玩家自己一条条挑出来的，默认藏起来
 * 等于白置顶。展开状态记在 {@link HotkeyGroups} 里，不挂在本页的字段上 ——
 * 收起手机那一下会把这一页销毁（见下），状态挂在页面上就等于"每次回来都是全收起"。
 *
 * <p><b>搜索时一律当展开</b>：玩家打了字就是"我要找它"，还把结果藏在收起的表头后面等于
 * 让搜索框失效。这一步不动玩家自己的展开状态，清空查询就回到原样。
 *
 * <h2>行右边那个小标签是什么</h2>
 *
 * 键位<b>自己声明</b>的生效场景（Forge / NeoForge 的 {@code KeyConflictContext}）：「世界中」
 * 就是"界面开着时它不算数"，也就是点它必然要先把手机收起来；「界面」是只在界面里生效，
 * 在手机上点正合适；「不限」是没声明过；「其它」是模组自定义的上下文，我们不知道它什么意思。
 * 让玩家看得见这一层，比让他靠"点了没反应"去猜好 —— 见 {@link HotkeyContext}。
 *
 * <h2>为什么名字是人话，不是类名</h2>
 *
 * 键位带一个<b>翻译键</b>（{@code KeyMapping.getName()}，如 {@code key.mcphone.open_phone}），
 * 而模组为了让自己的键位能出现在原版「按键设置」界面里，<b>必须</b>给它配一条语言条目。
 * 所以这里直接 {@code Component.translatable(mapping.getName())} 就得到玩家看得懂的字，
 * 与那个界面是同一份数据。模组没给当前语言的条目时会退化成原样显示键名
 * —— 原版界面同样如此，不会更差。
 *
 * <h2>为什么是行号滚动，不是像素滚动</h2>
 *
 * 像素滚动必须配裁剪，而这一页的行高是均匀的，行号滚动压根不需要裁剪
 * —— 少一层会切错坐标系的东西（缩放不是 100% 时 {@code enableScissor} 的坑，
 * 见 {@code PhoneCanvas.clipped} 的注释）。行数上百也不会卡：每帧只画看得见的那几行。
 *
 * <h2>一次点击为什么可能让手机退出</h2>
 *
 * 见 {@link KeyTrigger}：有一类模组在 {@code mc.screen != null} 时跳过轮询，而手机界面
 * 本身就是一个 {@code Screen}。对它们，这一页会先收起手机再注入（声明了「世界中」的
 * 一定如此，见上）。所以点下去之后手机消失是<b>预期行为</b>，不是崩溃。
 *
 * <p>顺带一提：手机挂在副手 HUD 上时它是"一直开着"的，收起全屏那副面孔之后会回到屏幕
 * 角落继续亮着 —— 那是它本来的样子，不是没关掉。
 *
 * <h2>结果那句话为什么不能在这一页关掉时清掉</h2>
 *
 * 因为「触发不了」这个结果<b>只可能</b>在手机已经收起来之后才产生 —— 页面那时已经被销毁，
 * 而 {@code onClose()} 一定会先跑。在这里清一下，玩家就永远看不到那句话了
 * （那条文案会变成死代码）。所以结果留在 {@link KeyTrigger} 里，等这一页下次进来时
 * {@link KeyTrigger#consumeOutcome() 取走}。<b>{@code onClose()} 里什么都不做，是有意的。</b>
 */
public final class HotkeyPage implements IPhonePage {

    /** 触发结果那句话挂多久（毫秒）。到点自己消失，不留一条永远在的提示 */
    private static final long BANNER_MS = 4000;

    /** 行与行之间的间距 */
    private static final int ROW_GAP = 1;

    /**
     * 一行里至少要给名字留这么宽（像素），否则先把上下文标签舍掉。
     *
     * <p>取舍是量的：这个标签是提示，名字才是这一行的主信息。窄屏上绑了修饰键的键名
     * （{@code Ctrl + Shift + 5} 那种）能吃掉半行，那时候还硬画标签，玩家会看到一行
     * 只剩省略号、认不出是哪一条。
     */
    private static final int MIN_NAME_W = 30;

    /**
     * 表头前面那个三角：展开的朝下、收起的朝右。
     *
     * <p>用字符而不是贴图：{@code ▶} 本仓早就在用（商店、音乐的播放键兜底字符），
     * 而折叠箭头只是个指示，不值得为它加两个贴图位。
     */
    private static final String FOLD_OPEN = "▼";
    private static final String FOLD_SHUT = "▶";

    /**
     * 列表的一行：可点的分组表头，或一个键位。
     *
     * @param text  画出来的字（表头那行已经带上三角与条数）
     * @param bound 这个键位现在绑的键（表头那行为空）
     * @param ctx   声明上下文那个小标签（表头那行为 null；{@code available()} 为 false 时也为 null）
     * @param group 表头对应的组名，点击时按它折叠；键位那行为 null
     * @param open  这一组现在展开着吗（只对表头有意义）
     */
    private record Row(String text, String bound, String ctx, KeyMapping mapping,
                       boolean pinned, String group, boolean open) {
        boolean header() {
            return mapping == null;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    private EditBox search;

    /** 上一次用来拼列表的查询串。变了才重拼 —— 每帧重拼上百行是白花钱 */
    private String lastQuery = "";

    private int scrollOffset;
    private int maxScroll;

    /** 渲染期算好，点击期只读。星的点区落在整行里面，所以两个都要记，点击时先问星 */
    private int hoveredRow = -1;
    private int hoveredStar = -1;

    /** 上一帧画布的位置与大小，用来判"这一下是不是点在手机外面" */
    private int canvasX;
    private int canvasY;
    private int canvasW;
    private int canvasH;

    /** 这一次进页面之后已经取到的那条结果，以及它是什么时候开始显示的 */
    private KeyTrigger.Outcome banner = KeyTrigger.Outcome.NONE;
    private long bannerAt;

    /** 上一帧有没有在画那句话。它一出现/一消失，列表整体上下挪，命中缓存就作废 */
    private boolean bannerWasShown;

    @Override
    public void onOpen() {
        scrollOffset = 0;
        lastQuery = "\u0000";          // 强制第一帧重拼
        invalidateHit();
        // 【不碰 KeyTrigger 里的结果】：它可能是上一次触发留下的，正等着这一页来取。
        // 这里只是把本地那条横幅重置，好让 render 去取一次
        banner = KeyTrigger.Outcome.NONE;
    }

    /**
     * 什么都不做 —— <b>有意如此</b>。
     *
     * <p>「触发不了」那句话只可能在手机收起来之后才产生，也就是这一页已经被关掉之后。
     * 在这里回头清掉它，玩家就永远看不到，那条文案变成死代码。理由详见类注释。
     */
    @Override
    public void onClose() {
    }

    @Override
    public void render(PhoneCanvas c) {
        Font font = c.font();
        GuiGraphics g = c.graphics();
        canvasX = c.x();
        canvasY = c.y();
        canvasW = c.width();
        canvasH = c.height();

        final int x = c.x();
        final int w = c.width();
        final int bottom = c.y() + c.height();

        // ---- 搜索框：原版单行 EditBox，全仓的可搜索列表都用它 ----
        final int searchY = c.y() + 1;
        final int searchH = font.lineHeight + 4;
        if (search == null) {
            search = new EditBox(font, x + 3, searchY, w - 6, searchH,
                    Component.translatable("mcphone.hotkey.search"));
            search.setMaxLength(48);
            search.setBordered(false);
            search.setFocused(true);
        } else {
            search.setX(x + 3);
            search.setY(searchY);
            search.setWidth(w - 6);
        }
        // 占位提示是自己画的：EditBox 的 suggestion 不截断，长提示会溢出手机外面
        search.setSuggestion(search.getValue().isEmpty()
                ? GuiUtil.truncate(font, Component.translatable("mcphone.hotkey.search").getString(), w - 12)
                : null);
        search.render(g, c.mouseX(), c.mouseY(), c.partialTick());

        if (!search.getValue().equals(lastQuery)) {
            lastQuery = search.getValue();
            rebuild();
            scrollOffset = 0;               // 查询变了就回到开头，不然会停在一个不存在的行号上
            invalidateHit();
        }
        g.fill(x + 3, searchY + searchH, x + w - 3, searchY + searchH + 1, c.style().subtleColor());

        int listTop = searchY + searchH + 2;

        // ---- 触发结果那一句 ----
        // 结果留在 KeyTrigger 里（这一页可能已经被关过一次），这里"取"一次就够：
        // 取走之后不会再重放，所以每次进这个 App 不会把上一次的旧结果又挂出来。
        //
        // 【只在全屏那一帧取】：副手 HUD 上那部手机不关机，这一页会在屏幕角落被每帧渲染
        // （HUD 那副面孔、以及位置编辑器的预览，都走 PhoneScreen.renderAsHud）。
        // 让角落那一份把结果取走的话，玩家重开手机时它已经没了 —— 那句话就成了只有
        // 缩略图见过的东西。理由详见 KeyTrigger 的类注释。
        if (banner == KeyTrigger.Outcome.NONE && onScreen()) {
            KeyTrigger.Outcome fresh = KeyTrigger.consumeOutcome();
            if (fresh != KeyTrigger.Outcome.NONE) {
                banner = fresh;
                bannerAt = System.currentTimeMillis();
            }
        }
        boolean bannerShown = false;
        if (banner != KeyTrigger.Outcome.NONE) {
            if (System.currentTimeMillis() - bannerAt < BANNER_MS) {
                String msg = Component.translatable(banner == KeyTrigger.Outcome.OK
                        ? "mcphone.hotkey.done" : "mcphone.hotkey.cannot").getString();
                g.drawString(font, GuiUtil.truncate(font, msg, w - 6), x + 3, listTop,
                        c.style().subtleColor(), false);
                listTop += font.lineHeight + 2;
                bannerShown = true;
            } else {
                banner = KeyTrigger.Outcome.NONE;
            }
        }
        // 这句话一出现/一消失，整个列表就上下挪了一段，上一帧记的行号不再对应当前那一行
        if (bannerWasShown != bannerShown) {
            invalidateHit();
            bannerWasShown = bannerShown;
        }

        // ---- 列表 ----
        final int rowH = font.lineHeight + 2;
        final int avail = bottom - listTop;
        // 最后一行底下不需要那点行距，所以按"第一行占 rowH、其余每行占 rowH+ROW_GAP"算；
        // 直接整除会少算一行，滚到底时底下会露出一条空白（与 AppManagerPage 同一条算法）
        final int visible = avail < rowH ? 1 : (avail - rowH) / (rowH + ROW_GAP) + 1;
        maxScroll = Math.max(0, rows.size() - visible);
        final int before = scrollOffset;
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        if (before != scrollOffset) invalidateHit();   // 列表自己挪了，上一帧的行号也作废

        hoveredRow = -1;
        hoveredStar = -1;

        if (rows.isEmpty()) {
            g.drawString(font, GuiUtil.truncate(font,
                            Component.translatable("mcphone.hotkey.empty").getString(), w - 6),
                    x + 3, listTop, c.style().subtleColor(), false);
            return;
        }

        int y = listTop;
        for (int i = scrollOffset; i < rows.size(); i++) {
            if (y + rowH > bottom) break;
            Row row = rows.get(i);

            final boolean onRow = GuiUtil.hit(c.mouseX(), c.mouseY(), x, y, w, rowH);
            if (onRow) {
                hoveredRow = i;
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER);
            }

            final int textY = y + (rowH - font.lineHeight) / 2;
            if (row.header()) {
                g.drawString(font, GuiUtil.truncate(font, row.text(), w - 6), x + 3, textY,
                        c.style().titleColor(), false);
            } else {
                final String star = row.pinned() ? "★" : "☆";
                final int starW = font.width(star);
                final int starX = x + w - starW - 2;
                if (onRow && GuiUtil.hit(c.mouseX(), c.mouseY(), starX - 2, y, starW + 4, rowH)) {
                    hoveredStar = i;
                }
                g.drawString(font, star, starX, textY,
                        row.pinned() ? c.style().accentColor() : c.style().subtleColor(), false);

                // 右边那一段先量出来，名字按剩下的宽度截 —— 不截的话长名字会盖到键名上
                //
                // 【为什么连绑定的键名也要截】：右边这三段是【靠右依次往前排】的，而绑了修饰键
                // 的键名可以很长（NeoForge 会拼成 "Left Control + Left Alt + Numpad 5" 那种）。
                // 本页不做裁剪，无上限地往前排就会把字画到内容区左边以外。最多让它占半行：
                // 超了就截断，玩家看见省略号就知道是这个键名太长，而不是这一行坏了。
                final String bound = GuiUtil.truncate(font, row.bound(), Math.max(0, w / 2));
                final int boundW = font.width(bound);
                final int boundX = starX - boundW - 4;
                g.drawString(font, bound, boundX, textY, c.style().subtleColor(), false);

                // 声明上下文那个小标签排在最左。它是提示，名字才是这一行的主信息 ——
                // 名字放不下时就把它舍掉：宁可少一个标签，也不要一行只剩个省略号
                // （绑了修饰键的键名能长到把半行吃掉，手机上真会出现这种一行）
                int nameRight = boundX - 5;
                final String ctx = row.ctx();
                if (ctx != null) {
                    final int ctxW = font.width(ctx);
                    final int ctxX = boundX - ctxW - 5;
                    if (ctxX - 5 - (x + 3) >= MIN_NAME_W) {
                        g.drawString(font, ctx, ctxX, textY, c.style().subtleColor(), false);
                        nameRight = ctxX - 5;
                    }
                }
                g.drawString(font, GuiUtil.truncate(font, row.text(), Math.max(0, nameRight - x)),
                        x + 3, textY, c.style().bodyColor(), false);
            }

            y += rowH + ROW_GAP;
        }
    }

    /**
     * 重拼列表。查询变了、开了这一页、或者玩家点了星之后调。
     *
     * <p>翻译在这里一次性做完存进 {@link Row}：这些字每帧都要画，而
     * {@code Component.translatable(...).getString()} 每次都要查表。
     */
    private void rebuild() {
        rows.clear();

        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.options.keyMappings == null) return;

        final String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        // 搜索时一律当展开：玩家打了字就是"我要找它"，还把结果藏在收起的表头后面
        // 等于让搜索框失效。这一步不动玩家自己的展开状态，清空查询就回到原样。
        final boolean respectFold = !searching();

        final List<KeyMapping> matched = new ArrayList<>();
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (mapping == null) continue;
            // 手机自己那 7 个键不列：那些是这个 App 自己的开关，列进来只会让人困惑
            if (PhoneKeys.CATEGORY.equals(mapping.getCategory())) continue;
            if (!query.isEmpty() && !matches(mapping, query)) continue;
            matched.add(mapping);
        }

        final List<KeyMapping> pinned = new ArrayList<>();
        for (KeyMapping mapping : matched) {
            if (HotkeyPins.isPinned(mapping)) pinned.add(mapping);
        }
        if (!pinned.isEmpty()) {
            pinned.sort(Comparator.comparing(HotkeyPage::display));
            boolean open = !respectFold || HotkeyGroups.isOpen(HotkeyGroups.PINNED);
            rows.add(header(HotkeyGroups.PINNED,
                    Component.translatable("mcphone.hotkey.pinned").getString(), open, pinned.size()));
            if (open) {
                for (KeyMapping mapping : pinned) rows.add(entry(mapping, true));
            }
        }

        // 分组用 LinkedHashMap：组的先后就是 keyMappings 里的先后（注册顺序），不再自己排一遍
        final Map<String, List<KeyMapping>> byCategory = new LinkedHashMap<>();
        for (KeyMapping mapping : matched) {
            if (HotkeyPins.isPinned(mapping)) continue;      // 已经在置顶那一段里了，不重复
            byCategory.computeIfAbsent(mapping.getCategory(), k -> new ArrayList<>()).add(mapping);
        }
        for (Map.Entry<String, List<KeyMapping>> group : byCategory.entrySet()) {
            List<KeyMapping> members = group.getValue();
            members.sort(Comparator.comparing(HotkeyPage::display));
            boolean open = !respectFold || HotkeyGroups.isOpen(group.getKey());
            rows.add(header(group.getKey(), Component.translatable(group.getKey()).getString(),
                    open, members.size()));
            if (open) {
                for (KeyMapping mapping : members) rows.add(entry(mapping, false));
            }
        }
    }

    /** 表头那一行：三角 + 名字 + 这一组有几条（条数让玩家知道收起之后藏了多少） */
    private static Row header(String group, String name, boolean open, int count) {
        return new Row((open ? FOLD_OPEN : FOLD_SHUT) + " " + name + " (" + count + ")",
                "", null, null, false, group, open);
    }

    private static Row entry(KeyMapping mapping, boolean pinned) {
        return new Row(display(mapping), mapping.getTranslatedKeyMessage().getString(),
                contextLabel(mapping), mapping, pinned, null, false);
    }

    /**
     * 这个键位声明自己在哪儿生效，译成玩家看得懂的两三个字。
     *
     * <p>先问 {@link HotkeyBackend#available()} 那道门：false 意味着这一档压根没接上，
     * 那时 {@code contextOf} 读出来的东西也不可信（见 {@link KeyTrigger#available()}）。
     * 这一问是为了：万一哪天有人在那种目标上把这一页打开，渲染也不会炸在这儿。
     *
     * <p>三个目标的取值来源不同：neoforge 与 forge 读键位自己声明的冲突上下文
     * （{@code KeyConflictContext}），fabric 没有这个概念，一律 {@link HotkeyContext#ANY}。
     */
    private static String contextLabel(KeyMapping mapping) {
        if (!HotkeyBackend.available()) return null;
        return Component.translatable(HotkeyBackend.contextOf(mapping).labelKey()).getString();
    }

    /** 玩家看到的名字 */
    private static String display(KeyMapping mapping) {
        return Component.translatable(mapping.getName()).getString();
    }

    /**
     * 搜索命中。
     *
     * <p>除了名字，还把<b>原始翻译键</b>与<b>分类</b>算进去：模组没给中文条目时名字会退化成
     * {@code key.foo.bar}，那时候玩家至少还能按那串玩意搜到它；按分类搜则是"我想找
     * Mekanism 的东西"这种最自然的用法。
     */
    private static boolean matches(KeyMapping mapping, String query) {
        if (display(mapping).toLowerCase(Locale.ROOT).contains(query)) return true;
        if (mapping.getName().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (mapping.getCategory().toLowerCase(Locale.ROOT).contains(query)) return true;
        return Component.translatable(mapping.getCategory()).getString()
                .toLowerCase(Locale.ROOT).contains(query);
    }

    /** 上一帧记的行号不再对应当前那一行时调。点击只认同一帧渲染出来的命中 */
    private void invalidateHit() {
        hoveredRow = -1;
        hoveredStar = -1;
    }

    /** 搜索框里有东西吗。有的话列表一律当展开（见 {@link #rebuild()}），表头折叠也因此不管用 */
    private boolean searching() {
        return search != null && !search.getValue().trim().isEmpty();
    }

    /**
     * 这一帧是"玩家真的在看这一页"吗。
     *
     * <p>这一页有三个渲染入口：全屏（{@code PhoneScreen.render}）、副手 HUD 那个角落
     * （{@code PhoneScreen.renderAsHud} ← {@code PhoneHud.render}）、以及 HUD 位置编辑器的
     * 预览（同一个 {@code renderAsHud} ← {@code PhoneHud.renderPreview}）。后两条路上这一页
     * <b>不会被销毁</b>（副手那部手机不关机），会一遍一遍地跑 render。
     *
     * <p>只有全屏那副面孔自己是 {@code mc.screen}，所以判据取它。用途见 {@link #render} 里
     * 取结果那一段。
     */
    private static boolean onScreen() {
        return Minecraft.getInstance().screen instanceof PhoneScreen;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 点在手机外面：这一页不管。MCphone 在把点击分发给页面【之前】就判过机身外了
        // （那一下的意思是关机），所以这里的返回值到不了那条路上 —— 不返回 false
        // 也不影响关机。留着这个判断只是为了"不属于我的坐标我不处理"这条语义自洽。
        if (!GuiUtil.hit(mouseX, mouseY, canvasX, canvasY, canvasW, canvasH)) return false;
        if (button != 0) return true;

        if (search != null && search.mouseClicked(mouseX, mouseY, button)) return true;

        // 星的点区落在整行里面，所以必须先判它 —— 否则点置顶会变成触发那个键位
        if (hoveredStar >= 0) {
            HotkeyPins.toggle(rows.get(hoveredStar).mapping());
            rebuild();
            invalidateHit();
            return true;
        }
        if (hoveredRow >= 0) {
            Row row = rows.get(hoveredRow);
            if (row.header()) {
                // 搜索期间列表一律当展开（见 rebuild），这时候折叠是看不见的 ——
                // 那就不许它改状态：改了也是一片"没反应"，清空查询之后才突然冒出来
                if (searching()) return true;
                // 表头整行都是点区：手机上每行都很窄，可点目标做大一点
                HotkeyGroups.toggle(row.group());
                rebuild();
                invalidateHit();
            } else {
                KeyTrigger.trigger(row.mapping());
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // 滚动会立刻改变可见起点，而点击用的是【上一帧】算好的行号。不在这里作废的话，
        // 滚一格之后同一帧内再点，触发的会是相邻那一条键位 —— 而且同样是第三方模组的动作
        invalidateHit();
        if (amount > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (amount < 0 && scrollOffset < maxScroll) {
            scrollOffset++;
            return true;
        }
        return false;      // 到头了返回 false，让上层去做它的事
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return search != null && search.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return search != null && search.charTyped(codePoint, modifiers);
    }

    /**
     * 这一页有输入框，所以按 {@link IPhonePage#capturesKeyboard()} 的约定返回 true。
     *
     * <p>不返回 true 的话，玩家在搜索框里打拼音按到 e 会命中原版的背包键 ——
     * 手机当场关掉、搜索内容全丢，而中文用户躲都躲不开（拼音里 e 太常见了）。
     * 代价是这一页里背包键不再能关手机，玩家用 ESC 或导航栏的 ◁。
     */
    @Override
    public boolean capturesKeyboard() {
        return true;
    }

    /** 导航栏的 ◁ 退回主屏；这一页没有更里的一层要退 */
    @Override
    public boolean onBack() {
        return false;
    }
}
