package com.november.mcphone.feature.notes;

import com.november.mcphone.api.cost.ICost;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条笔记印成一本书。
 *
 * 为什么要有这个
 *
 * 笔记跟着玩家走，别人拿到你的手机也看不见——这是它该有的样子。但总有
 * 想给别人看的时候：攻略、坐标、留言。印成一本原版的书与笔，就能像
 * 任何物品一样递给别人、放进箱子、挂在展示框上。
 *
 * 用原版的书而不是自造一种"笔记物品"：书的一切——阅读、复制、署名、
 * 展示框、成书——原版都做好了，自造一个只会得到一个哪儿都不认识的东西。
 *
 * 要花一本空白的书与笔
 *
 * 不凭空变出一本：打印总得有纸。用 {@link ICost} 表达这个代价，与
 * 将来"下载 App 要交什么"共用同一套机制。
 *
 * 判定要求【空白】：书与笔和写满字的是同一种物品，只认物品种类的话，
 * 玩家写了半天的书会被当耗材烧掉。
 */
public final class NotePrinter {

    private NotePrinter() {}

    /**
     * 打印一次的代价：一本空白的书与笔。
     *
     * 公开出来是为了让界面也能问一句"付得起吗"，好把按钮画成灰的，
     * 而不是让玩家点下去才发现不行。
     */
    public static final ICost COST = ICost.matching(
            NotePrinter::isBlankBook, 1,
            Component.translatable("mcphone.cost.blank_book"));

    /** 空白的书与笔：没写过字，或者写过又删光了 */
    private static boolean isBlankBook(ItemStack stack) {
        if (!stack.is(Items.WRITABLE_BOOK)) return false;

        WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        return content == null || content.pages().isEmpty();
    }

    /**
     * 印一本。
     *
     * @return 成了返回 true；没有空白书、笔记不存在都返回 false
     */
    public static boolean print(ServerPlayer player, Note note) {
        if (note.body().isBlank()) return false;
        if (!COST.canAfford(player)) return false;
        if (!COST.consume(player)) return false;

        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT,
                new WritableBookContent(toPages(note.body())));

        // 背包满了就掉在脚下，而不是把书连同那本空白书一起吞掉
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        return true;
    }

    /**
     * 原版书页里正文的可用宽度与行数。
     *
     * 一页放得下多少，从来不是"多少个字符"——原版按【宽度折行、满 14 行
     * 翻页】。先前按 1024 字符切是错的：那是编辑器允许的字符上限，
     * 一页塞 1024 个字早就超出 14 行了，表现就是内容挤成一坨、后面的
     * 根本显示不出来。
     */
    private static final int PAGE_WIDTH = 114;
    private static final int PAGE_LINES = 14;

    /**
     * 估算一个字符占多宽。
     *
     * 服务端没有字体数据，只能按字符区间猜：拉丁字母一律按 6 算，CJK 与
     * 全角按 9 算。这是【上界】——原版里 i、l 这些窄字符只有 2 到 3 像素，
     * 所以估出来的行只会比实际短，不会超出页宽。
     *
     * 宁可偏保守：页面没填满只是有点空，超出行数则是内容直接看不见。
     */
    private static int charWidth(char c) {
        return c < 0x2E80 ? 6 : 9;
    }

    /**
     * 把正文切成一页页。
     *
     * 两层：先按页宽把每个段落折成若干行，再每满 PAGE_LINES 行翻一页。
     * 段落之间的换行照原样保留，玩家写的分段在书里仍然是分段。
     *
     * 仍照原版 100 页的上限截断，防的是日后有人把笔记长度调大却忘了这里。
     */
    private static List<Filterable<String>> toPages(String body) {
        List<String> pages = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        for (String paragraph : body.split("\n", -1)) {
            for (String line : wrap(paragraph)) {
                lines.add(line);
                if (lines.size() >= PAGE_LINES) {
                    pages.add(String.join("\n", lines));
                    lines.clear();
                }
            }
        }
        if (!lines.isEmpty()) pages.add(String.join("\n", lines));

        if (pages.size() > WritableBookContent.MAX_PAGES) {
            pages = pages.subList(0, WritableBookContent.MAX_PAGES);
        }
        return pages.stream().map(Filterable::passThrough).toList();
    }

    /**
     * 按页宽把一段文字折成若干行。
     *
     * 优先在空格处断，断不了才硬断：英文硬断会把单词劈成两半，中文没有
     * 空格，本来就只能逐字断。
     */
    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            out.add("");   // 空段落也占一行，否则玩家写的空行会被吃掉
            return out;
        }

        int start = 0;
        int width = 0;
        int lastSpace = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') lastSpace = i;
            width += charWidth(c);

            if (width > PAGE_WIDTH) {
                boolean breakAtSpace = lastSpace > start;
                int cut = breakAtSpace ? lastSpace : i;
                out.add(text.substring(start, cut));

                start = breakAtSpace ? cut + 1 : cut;
                lastSpace = -1;
                width = 0;
                for (int j = start; j <= i; j++) width += charWidth(text.charAt(j));
            }
        }
        out.add(text.substring(start));
        return out;
    }
}
