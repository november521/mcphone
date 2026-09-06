package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.reader.BookRef;
import com.november.mcphone.feature.reader.client.compat.BookQuirks;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 书源名单 —— 书架页看到的东西，就是这里所有书源拼起来的。
 *
 * 加一个书源要改的全部东西
 *
 * 写一个 {@link BookSource} 实现，在下面 SOURCES 里加一行。完。
 *
 * 但多数时候不必写新书源：某个模组的手册不是 Patchouli 书时，往
 * {@link ExternalBookSource} 的白名单里加一条就够了，那正是它存在的理由。
 * 真正需要新书源的是"一整类书"——比如将来玩家自己传进来的书。
 *
 * 走一份看得见的名单而不是 SPI，与音乐那边的 MusicSources 是同一个取舍：
 * App 与商店来源走 SPI 是因为那是开给别的模组用的；书源眼下只有我们自己会加，
 * 名单比注册表好排查。真有第三方要接，换成 SpiLoader 是十几行的事，
 * {@link BookSource} 本身不用动。
 *
 * 顺序就是书城里的顺序
 *
 * 拼表时不重新排——各书源自己排好自己的（Patchouli 那边按模组名再按书名）。
 * 跨书源则按名单顺序，"帕秋莉手册在前、白名单里的外部手册在后"是可预期的，
 * 总比每次进来顺序都不一样强；真要找某一本，顶上就是搜索框。
 *
 * 每个书源都单独兜住 Throwable
 *
 * 兜的是 Throwable 而不是 Exception：书源包着的是别人的模组，最常见的翻车
 * 方式是对方改了类名或方法签名，那抛出来的是 NoClassDefFoundError /
 * NoSuchMethodError，属于 Error，用 Exception 接不住。一个书源坏了应该是
 * "这一类书不见了"，不是整部手机打不开。
 */
public final class BookSources {

    private BookSources() {}

    /** 全部书源。加新的就往这里加一行 */
    private static final List<BookSource> SOURCES = List.of(
            new PatchouliSource(),
            new GuideMeSource(),
            new ExternalBookSource()
    );

    /**
     * 拼好的那张表，缓存着。null 表示要重拼。
     *
     * 只在客户端线程上读写（界面绘制与点击都在那条线程），不加锁也不用 volatile。
     */
    private static List<BookRef> cached;

    /**
     * 有没有任何一个书源能出书。
     *
     * 「阅读」App 靠它决定自己在不在（见 ReaderApp.isAvailable）：一个只装了
     * 沉浸工程、没装 Patchouli 的整合包里，书城照样有东西可看，App 就不该藏起来。
     *
     * 只问各书源的 isAvailable()，那几个方法只碰 ModList，不会把别人的类拖进来。
     */
    public static boolean anyAvailable() {
        for (BookSource source : SOURCES) {
            if (isAvailable(source)) return true;
        }
        return false;
    }

    /** 重扫所有书源。打开书架页时调 */
    public static void refreshAll() {
        for (BookSource source : SOURCES) {
            if (!isAvailable(source)) continue;
            try {
                source.refresh();
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] 书源 {} 扫描失败，这一类书这次不显示", source.id(), t);
            }
        }
        cached = null;
    }

    /**
     * 所有书源的书拼成一张表，就是书架上显示的顺序。
     *
     * 这张表必须缓存：界面每帧都问一次，而每帧新建一个 ArrayList 再拷两遍，
     * 拷出来的全是一帧之内就扔掉的垃圾。音乐那边曾经因为这个，光是开着 App
     * 站着不动每秒就要拷两万多次引用，这里不重蹈一遍。
     */
    public static List<BookRef> allBooks() {
        List<BookRef> books = cached;
        if (books != null) return books;

        List<BookRef> out = new ArrayList<>();
        for (BookSource source : SOURCES) {
            if (!isAvailable(source)) continue;
            try {
                out.addAll(source.list());
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] 书源 {} 取书失败，这一类书这次不显示", source.id(), t);
            }
        }

        // 特例最后过一遍：有的书要改名，有的书压根不该出现在架子上
        List<BookRef> shelved = new ArrayList<>(out.size());
        for (BookRef book : out) {
            BookRef fixed = BookQuirks.rewrite(book);
            if (fixed != null) shelved.add(fixed);
        }

        cached = List.copyOf(shelved);
        return cached;
    }

    /** 按书找回它的书源，找不到返回 null。打开一本书要靠它 */
    public static BookSource of(BookRef book) {
        for (BookSource source : SOURCES) {
            if (source.id().equals(book.sourceId())) return source;
        }
        return null;
    }

    /**
     * 打开一本书：特例优先，没人接管才走书源。
     *
     * 找不到书源、或书源自己抛了，都只记日志——玩家点了没反应已经够糟，
     * 再崩一次界面更糟。
     */
    public static void open(BookRef book) {
        // 先问特例：有的模组早就不用这本 Patchouli 书了，真正的手册在它自己的界面里。
        // 这一句必须在找书源【之前】——特例接管的前提是"这本书归它管"，与书源在不在
        // 名单里无关。放在后面的话，将来哪本书的书源没了，本来能靠特例打开的也打不开了
        if (BookQuirks.open(book)) return;

        BookSource source = of(book);
        if (source == null) {
            MCphone.LOGGER.error("[MCphone] 书 {} 的书源 {} 不在名单里，打不开",
                    book.bookId(), book.sourceId());
            return;
        }

        try {
            source.open(book);
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 打开书 {} 失败", book.bookId(), t);
        }
    }

    /**
     * 画一本书的图标，由它的书源来画。
     *
     * @return 真的画了才 true；返回 false 时由界面兜底（换肤贴图，再不行纯色）
     */
    public static boolean renderIcon(GuiGraphics g, BookRef book, int x, int y, int size) {
        BookSource source = of(book);
        if (source == null) return false;
        try {
            return source.renderIcon(g, book, x, y, size);
        } catch (Throwable t) {
            // 这里【不】记日志：绘制是每帧一次的，一本坏书能在一分钟里刷出上千行。
            // 画不出来的后果只是退回兜底图，玩家看得见、也不影响他翻书
            return false;
        }
    }

    /** 书源自己判断在不在场时也可能抛，这里一并兜住 */
    private static boolean isAvailable(BookSource source) {
        try {
            return source.isAvailable();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 书源 {} 的可用性判断抛了异常，按不可用处理", source.id(), t);
            return false;
        }
    }
}
