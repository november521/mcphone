package com.november.mcphone.feature.reader.client.compat;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.reader.BookRef;

import java.util.ArrayList;
import java.util.List;

/**
 * 书籍特例名单与统一入口。
 *
 * 加一条特例要做什么
 *
 * 写一个 {@link BookQuirk} 实现，把它加进下面的 QUIRKS——就这两步。对方没装时
 * 自动跳过，不必在别处写任何"装没装"的判断。
 *
 * 与 compat 包里那套 CompatModule 的分工
 *
 * 那一套管的是【加载期】要插手的事（对方有 bug，我们绕开），装载失败会拖垮整个
 * 注册流程，所以有一整套兜底机制。这一套只管【界面上】的一本书长什么样、点了
 * 打开什么，全部发生在玩家点进书架之后，最坏结果是这一本书不对劲。
 *
 * 两者都兜 Throwable，而且理由一样：特例代码天然要碰别的模组，最常见的翻车方式
 * 是对方改了类名或方法名，那抛的是 NoClassDefFoundError / NoSuchMethodError，
 * 属于 Error 不是 Exception，用后者接不住。
 *
 * 判断"装没装"只算一次
 *
 * ModList 在运行期不会变，而 {@link #rewrite} 是每次重扫书架都要对每本书调一遍的。
 * 几十本书 × 几条特例，每条都去查一次 ModList 没有必要。
 */
public final class BookQuirks {

    private BookQuirks() {}

    /** 全部特例。加新的就往这里加一行 */
    private static final List<BookQuirk> QUIRKS = List.of(
    );

    /** 当前该启用的那些，算一次存着。null 表示还没算过 */
    private static List<BookQuirk> active;

    /**
     * 改写一本书在书架上的样子。
     *
     * @return 要显示的书；null 表示这本书不该出现在书架上
     */
    public static BookRef rewrite(BookRef book) {
        BookRef current = book;
        for (BookQuirk quirk : active()) {
            try {
                if (!quirk.matches(current)) continue;
                current = quirk.rewrite(current);
                if (current == null) return null;     // 有人说了不显示，后面的不必再问
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] 书籍特例 {} 改写 {} 时出错，这本书按原样显示",
                        quirk.targetModId(), book.bookId(), t);
                return book;
            }
        }
        return current;
    }

    /**
     * 有没有哪条特例接管了"打开这本书"。
     *
     * @return true 表示已经打开了，调用方别再走书源默认那条路
     */
    public static boolean open(BookRef book) {
        for (BookQuirk quirk : active()) {
            try {
                if (quirk.matches(book) && quirk.open(book)) return true;
            } catch (Throwable t) {
                // 不 return true：特例翻车时该退回默认那条路，玩家至少能打开点什么
                MCphone.LOGGER.error("[MCphone] 书籍特例 {} 打开 {} 时出错，改走默认方式",
                        quirk.targetModId(), book.bookId(), t);
            }
        }
        return false;
    }

    private static List<BookQuirk> active() {
        List<BookQuirk> cached = active;
        if (cached != null) return cached;

        List<BookQuirk> out = new ArrayList<>();
        for (BookQuirk quirk : QUIRKS) {
            try {
                if (!quirk.isNeeded()) continue;
                out.add(quirk);
                MCphone.LOGGER.info("[MCphone] 已启用书籍特例：{}", quirk.targetModId());
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] 书籍特例 {} 的启用判断出错，已跳过",
                        quirk.targetModId(), t);
            }
        }

        active = List.copyOf(out);
        return active;
    }
}
