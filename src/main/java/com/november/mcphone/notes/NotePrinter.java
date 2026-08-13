package com.november.mcphone.notes;

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
 * ============================================================
 * 为什么要有这个
 * ============================================================
 *
 * 笔记跟着玩家走，别人拿到你的手机也看不见——这是它该有的样子。但总有
 * 想给别人看的时候：攻略、坐标、留言。印成一本原版的书与笔，就能像
 * 任何物品一样递给别人、放进箱子、挂在展示框上。
 *
 * 用原版的书而不是自造一种"笔记物品"：书的一切——阅读、复制、署名、
 * 展示框、成书——原版都做好了，自造一个只会得到一个哪儿都不认识的东西。
 *
 * ============================================================
 * 要花一本空白的书与笔
 * ============================================================
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
     * 把正文切成一页页。
     *
     * 按行累积而不是按字符硬切：硬切会把一句话劈在两页上，翻页时读起来
     * 很难受。只有单独一行就超过一页时才不得不硬切。
     *
     * 笔记上限 2000 字，一页 1024 字，正常最多两页，离原版 100 页的上限
     * 很远——但仍然照上限截断，防的是日后有人把笔记长度调大却忘了这里。
     */
    private static List<Filterable<String>> toPages(String body) {
        List<String> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : body.split("\n", -1)) {
            while (line.length() > WritableBookContent.PAGE_EDIT_LENGTH) {
                flush(pages, current);
                pages.add(line.substring(0, WritableBookContent.PAGE_EDIT_LENGTH));
                line = line.substring(WritableBookContent.PAGE_EDIT_LENGTH);
            }

            int extra = current.isEmpty() ? line.length() : line.length() + 1;
            if (current.length() + extra > WritableBookContent.PAGE_EDIT_LENGTH) {
                flush(pages, current);
            }
            if (!current.isEmpty()) current.append('\n');
            current.append(line);
        }
        flush(pages, current);

        if (pages.size() > WritableBookContent.MAX_PAGES) {
            pages = pages.subList(0, WritableBookContent.MAX_PAGES);
        }
        return pages.stream().map(Filterable::passThrough).toList();
    }

    private static void flush(List<String> pages, StringBuilder current) {
        if (current.isEmpty()) return;
        pages.add(current.toString());
        current.setLength(0);
    }
}
