package com.november.mcphone.feature.notes;

import com.november.mcphone.api.cost.ICost;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** 把一条笔记印成一本原版的书与笔，代价是一本空白的书与笔（{@link ICost}） */
public final class NotePrinter {

    private NotePrinter() {}

    /** 公开给界面预先判断"付得起吗" */
    public static final ICost COST = ICost.matching(
            NotePrinter::isBlankBook, 1,
            Component.translatable("mcphone.cost.blank_book"));

    /**
     * 书页在 NBT 里的键，与原版 WritableBookItem 用的是同一个。
     *
     * 1.21.1 那边是 DataComponents.WRITABLE_BOOK_CONTENT 组件；1.20.1 上
     * 书与笔的内容就是物品 NBT 里的一个字符串列表。
     */
    private static final String KEY_PAGES = "pages";

    /** 原版书页数上限。1.21 有 WritableBookContent.MAX_PAGES 这个常量，1.20.1 没有，写死在这 */
    private static final int MAX_PAGES = 100;

    // 必须判空白：写过字的书与笔是同一种物品，不判会把玩家写的书当耗材烧掉
    private static boolean isBlankBook(ItemStack stack) {
        if (!stack.is(Items.WRITABLE_BOOK)) return false;

        CompoundTag tag = stack.getTag();
        if (tag == null) return true;
        // 判类型而不是只判有没有这个键：NBT 是自由格式的，别人往 pages 写了
        // 别的类型时 getList 会返回空列表，那就会把一本写坏了的书当空白书烧掉
        if (!tag.contains(KEY_PAGES, Tag.TAG_LIST)) return true;
        return tag.getList(KEY_PAGES, Tag.TAG_STRING).isEmpty();
    }

    /** 印一本；正文为空或没有空白书返回 false */
    public static boolean print(ServerPlayer player, Note note) {
        if (note.body().isBlank()) return false;
        if (!COST.canAfford(player)) return false;
        if (!COST.consume(player)) return false;

        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        ListTag pages = new ListTag();
        for (String page : toPages(note.body())) pages.add(StringTag.valueOf(page));
        book.getOrCreateTag().put(KEY_PAGES, pages);

        // 背包满了就掉在脚下，而不是把书连同那本空白书一起吞掉
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        return true;
    }

    // 原版书页按像素宽度折行、满 14 行翻页，不是按字符数切页
    private static final int PAGE_WIDTH = 114;
    private static final int PAGE_LINES = 14;

    /** 服务端没有字体数据，按区间估上界：拉丁 6、CJK 与全角 9，只会偏短不会超页宽 */
    private static int charWidth(char c) {
        return c < 0x2E80 ? 6 : 9;
    }

    /** 先按页宽折行，再每 PAGE_LINES 行翻页；超过原版页数上限截断 */
    private static List<String> toPages(String body) {
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

        if (pages.size() > MAX_PAGES) {
            pages = pages.subList(0, MAX_PAGES);
        }
        return pages;
    }

    /** 优先在空格处断，断不了才硬断 */
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
