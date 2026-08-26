package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.feature.reader.BookRef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import vazkii.patchouli.api.PatchouliAPI;
import vazkii.patchouli.client.book.BookIcon;
import vazkii.patchouli.common.book.Book;
import vazkii.patchouli.common.book.BookRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Patchouli 书源 —— 整合包里所有帕秋莉手册，一次列全。
 *
 * 我们只做两件事：列出来、还回去
 *
 * 书名、图标、条目、多方块预览、配方页、进度与条目锁定，全是 Patchouli 的。
 * 我们不解析 book.json、不画一页书、不碰它的进度存档，只是把它已经装好的那份
 * 目录端到手机上，玩家点哪本，就替他做一次他自己右键那本书也会做的调用。
 *
 * 为什么翻书不在手机里画
 *
 * 它的书界面是 272×180 的一整块 Screen，手机屏幕只有 120×200，放不下。而
 * 自己重画就要重新实现它几十种页面类型与模板系统，它一改版就断——那是把
 * 别人的核心功能抄一遍，不是联动。所以书架归我们，翻书归它。
 *
 * 代价是打开书的那一刻手机会退下去（Patchouli 接管整个屏幕），这躲不掉；
 * 合上书自动回到书架由 {@code ReaderReturn} 补上。
 *
 * 接触面刻意只有四样
 *
 * BookRegistry.INSTANCE.books（有哪些书）、Book 的几个公开字段与
 * getOwnerName/getIcon（书名、出处、图标）、PatchouliAPI.openBookGUI（翻书）。
 * 其中只有最后一个属于它的 api 包，前三样是内部类——所以编译对着【最老】的
 * 1.21.1 版本（见 gradle.properties），并且这几样从 1.16 起就没动过。
 * 真碰上它改版，抛的是 NoSuchFieldError / NoSuchMethodError 这类 Error，
 * 由 {@link BookSources} 兜住，最坏结果是书架空着，不是手机打不开。
 *
 * 类型隔离的规矩，和 WaystonesCompat 一模一样
 *
 * Patchouli 是可选依赖，编译期有类、运行期可能没有。JVM 准备执行一个方法时会
 * 解析它引用到的类型，碰上不存在的类当场抛 NoClassDefFoundError——所以
 * "装没装"的判断（{@link #isAvailable()}）与"真去调它"（下面那几个 private
 * 方法）必须分在不同方法里。写在一起的话，那句 if 还没轮到执行，方法本身就炸了。
 *
 * 同理，字段与方法签名里一个 vazkii.* 都不许出现：{@link #list()} 返回的是
 * {@link BookRef}，{@link #books} 存的也是它。这样上层（书架页、BookSources）
 * 引用本类时不会被牵连着去加载 Patchouli 的类。
 */
public final class PatchouliSource implements BookSource {

    public static final String PATCHOULI_MODID = "patchouli";

    /** 与 {@link BookRef#sourceId()} 对应，别改：将来落盘的收藏、置顶都拿它当键 */
    private static final String SOURCE_ID = "patchouli";

    /** 扫出来的书，扫描前是空表。类型必须是中性的，理由见类注释 */
    private List<BookRef> books = List.of();

    /**
     * 上一次记进日志的本数，-1 表示还没记过。
     *
     * 书架每打开一次就重扫一次，每次都记一行日志等于刷屏；而"扫到几本"这个数
     * 恰恰是出问题时第一个要知道的——书架空着，是它一本都没扫到，还是扫到了
     * 没画出来？两者的排查方向完全相反。所以只在数目【变了】的时候记。
     */
    private int loggedCount = -1;

    @Override
    public String id() {
        return SOURCE_ID;
    }

    /** 这个方法里【不能】出现任何 vazkii.* 的类型，否则它自己就先炸了 */
    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded(PATCHOULI_MODID);
    }

    @Override
    public List<BookRef> list() {
        return books;
    }

    @Override
    public void refresh() {
        books = scan();

        if (books.size() != loggedCount) {
            loggedCount = books.size();
            MCphone.LOGGER.info("[MCphone] 书架扫到 {} 本 Patchouli 手册", loggedCount);
        }
    }

    @Override
    public void open(BookRef book) {
        openBook(book);
    }

    @Override
    public boolean renderIcon(GuiGraphics g, BookRef book, int x, int y, int size) {
        return drawBookIcon(g, book, x, y, size);
    }

    /**
     * 扫一遍它的注册表。
     *
     * 不过滤 noBook 的书：那种书没有对应物品，玩家【只能】从这里打开，
     * 正是这个 App 最有价值的一类。它们没有图标，由界面兜底画一本书。
     */
    private static List<BookRef> scan() {
        List<BookRef> out = new ArrayList<>();

        for (Book book : BookRegistry.INSTANCE.books.values()) {
            try {
                out.add(new BookRef(SOURCE_ID, book.id,
                        Component.translatable(book.name),
                        subtitleOf(book),
                        book.getOwnerName()));
            } catch (Throwable t) {
                // 一本书读不出来不该拖掉整架书。常见于书 json 写坏了，
                // 那是那个模组的事，我们跳过它继续
                MCphone.LOGGER.error("[MCphone] 读 Patchouli 手册 {} 的信息失败，跳过", book.id, t);
            }
        }

        // 按模组名再按书名排：几百个模组的整合包里，同一个模组的书挨在一起找起来最快。
        // 排序用 getString() 会把当下的语言腌进顺序里，但这张表每次打开书架都重扫，
        // 换语言之后下次进来就是新顺序，可以接受
        out.sort(Comparator.comparing((BookRef b) -> b.owner() == null ? "" : b.owner())
                .thenComparing(b -> b.title().getString()));

        return List.copyOf(out);
    }

    /**
     * 副标题。
     *
     * 用 book.subtitle 而不是它的 getSubtitle()：后者会把 version 字段拼成
     * "第 N 版"，那是它书封面上的花字，列表里显示"第 3 版"没有任何信息量。
     */
    private static Component subtitleOf(Book book) {
        String subtitle = book.subtitle;
        return subtitle == null || subtitle.isEmpty() ? null : Component.translatable(subtitle);
    }

    /** 把书交还给 Patchouli 打开。这一句之后我们的手机界面就被顶掉了 */
    private static void openBook(BookRef book) {
        PatchouliAPI.get().openBookGUI(book.bookId());
    }

    /**
     * 画那本书自己的图标。
     *
     * getIcon() 认书里配的 index_icon，没配则退回那本书的物品——也就是说
     * 绝大多数书画出来就是玩家熟悉的那本书的样子，比我们自己画一个统一的
     * 书本图标有用得多。
     *
     * 两种情况交回界面兜底：
     *
     *   空物品。noBook 的书没有对应物品也多半没配图标，硬画会画出一个空的
     *   ItemStack——也就是什么都不画，而界面还以为画上了。
     *
     *   带自定义渲染器的物品。那等于把别人的一段渲染代码请进手机界面里跑，
     *   理由与后果见 {@link GuiUtil#canDrawItemIcon}——最坏的一种是整块屏幕
     *   什么都不显示，而根因只是列表里某一行的一张小图标。
     */
    private static boolean drawBookIcon(GuiGraphics g, BookRef book, int x, int y, int size) {
        Book found = BookRegistry.INSTANCE.books.get(book.bookId());
        if (found == null) return false;

        BookIcon icon = found.getIcon();
        if (icon == null) return false;

        // 物品图标必须过这道检查。检查的是 BookIcon 里真正要画的那一个物品，
        // 而不是 getBookItem()：书里配了 index_icon 时两者可以是不同的物品
        if (icon instanceof BookIcon.StackIcon stackIcon
                && !GuiUtil.canDrawItemIcon(stackIcon.stack())) {
            return false;
        }

        // 它固定按 16×16 画。缩放交给矩阵，别去改它的实现
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        if (size != 16) g.pose().scale(size / 16f, size / 16f, 1f);
        icon.render(g, 0, 0);
        g.pose().popPose();
        return true;
    }
}
