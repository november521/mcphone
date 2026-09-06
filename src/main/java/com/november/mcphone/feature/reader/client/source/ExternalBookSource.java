package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.feature.reader.BookRef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部手册书源 —— 一份写死的白名单，收那些压根不在 Patchouli 里的手册。
 *
 * 加一条要做什么
 *
 * 写一个 {@link ExternalBook} 实现，往下面 WHITELIST 里加一行。对方没装时自动
 * 跳过，别处一个字都不用改。为什么只能靠白名单、而不是自动发现，见
 * {@link ExternalBook} 的接口注释——这类手册没有任何通用协议，只能一个一个适配。
 *
 * 顺序
 *
 * 这一源的书排在 Patchouli 那一堆之后（{@link BookSources} 按名单顺序拼表）。
 * 不打散混排是刻意的：几十本帕秋莉手册中间突然插进来一本别的，玩家会以为自己
 * 看错了；而真要找它，顶上就是搜索框。
 */
public final class ExternalBookSource implements BookSource {

    /** 与 {@link BookRef#sourceId()} 对应，别改：玩家书架里的条目按它记 */
    private static final String SOURCE_ID = "external";

    /** 全部外部手册。加新的就往这里加一行 */
    private static final List<ExternalBook> WHITELIST = List.of(
            new ImmersiveEngineeringManual()
    );

    /** 扫出来的书，类型必须是中性的——上层引用本类时不该被牵连着加载别人的类 */
    private List<BookRef> books = List.of();

    /** 上一次记进日志的本数，-1 表示还没记过。与 PatchouliSource 同一个理由 */
    private int loggedCount = -1;

    @Override
    public String id() {
        return SOURCE_ID;
    }

    /**
     * 白名单里有任何一条在场就算可用。
     *
     * 这个方法只碰 ModList，不碰任何外部模组的类型——{@link ExternalBook#isAvailable()}
     * 的默认实现同样如此。
     */
    @Override
    public boolean isAvailable() {
        for (ExternalBook book : WHITELIST) {
            if (available(book)) return true;
        }
        return false;
    }

    @Override
    public List<BookRef> list() {
        return books;
    }

    @Override
    public void refresh() {
        List<BookRef> out = new ArrayList<>();

        for (ExternalBook book : WHITELIST) {
            if (!available(book)) continue;
            try {
                out.add(new BookRef(SOURCE_ID, book.bookId(), book.title(), null,
                        BookSource.modName(book.modId())));
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] 读外部手册 {} 的信息失败，跳过", book.bookId(), t);
            }
        }

        books = List.copyOf(out);

        if (books.size() != loggedCount) {
            loggedCount = books.size();
            MCphone.LOGGER.info("[MCphone] 书城收进 {} 本白名单里的外部手册", loggedCount);
        }
    }

    @Override
    public void open(BookRef book) {
        ExternalBook entry = find(book);
        if (entry == null) {
            MCphone.LOGGER.error("[MCphone] 外部手册 {} 不在白名单里，打不开", book.bookId());
            return;
        }
        // 打不开由实现自己记日志：只有它知道是哪一步断的
        entry.open();
    }

    /**
     * 画那本手册对应的物品。
     *
     * 与 Patchouli 那边同一条规矩：带自定义渲染器的物品不画，交回界面兜底——
     * 那种物品在 GUI 里会伤到后面画的东西，理由见 {@link GuiUtil#canDrawItemIcon}。
     */
    @Override
    public boolean renderIcon(GuiGraphics g, BookRef book, int x, int y, int size) {
        ExternalBook entry = find(book);
        if (entry == null || entry.item() == null) return false;

        Item item = BuiltInRegistries.ITEM.get(entry.item());
        if (item == Items.AIR) return false;

        return GuiUtil.drawItemIcon(g, new ItemStack(item), x, y, size);
    }

    private static ExternalBook find(BookRef book) {
        for (ExternalBook entry : WHITELIST) {
            if (entry.bookId().equals(book.bookId())) return entry;
        }
        return null;
    }

    /** 判断在不在场也可能抛（对方的类加载不出来），一并兜住 */
    private static boolean available(ExternalBook book) {
        try {
            return book.isAvailable();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 外部手册 {} 的可用性判断出错，按不可用处理",
                    book.modId(), t);
            return false;
        }
    }

}
