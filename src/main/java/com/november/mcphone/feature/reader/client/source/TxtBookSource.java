package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.feature.reader.BookRef;
import com.november.mcphone.feature.reader.client.TxtLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 txt 书源 —— 玩家自己丢进 {@code config/mcphone/reader/books/} 的小说。
 *
 * <h2>与另外三个书源不一样的地方</h2>
 *
 * 那三个包的都是别的模组的手册：书是别人写的，点开之后接管屏幕的也是别人的界面，我们
 * 只提供目录。这一个反过来——书是玩家自己的文件，<b>翻书的界面得我们自己画</b>
 * （{@code TxtReaderPage}），因为没有别人可以还给。
 *
 * 也因此它<b>永远可用</b>：不依赖任何模组，一个书源模组都没装的整合包里，阅读 App
 * 照样能用来看小说。这一条让 {@code ReaderApp.isAvailable()} 事实上恒为真，那是有意的
 * ——本地阅读器本身就是这个 App 的功能之一，不该因为玩家没装 Patchouli 就消失。
 *
 * <h2>id 是文件名的哈希</h2>
 *
 * {@link BookRef#bookId()} 是 {@code ResourceLocation}，只认小写字母数字和几个符号，
 * 而小说文件名里几乎必然有中文和空格。所以 id 走 {@link TxtLibrary.Entry#id()}——
 * 文件名的哈希，稳定、合法；改文件名就是换一本书，收藏与进度也跟着分开，那是对的。
 */
public final class TxtBookSource implements BookSource {

    /** 与 {@link BookRef#sourceId()} 对应，别改：玩家书架里的条目按它记 */
    public static final String SOURCE_ID = "txt";

    /** 书 id 的前缀，形如 {@code mcphone:txt/3f2a...} */
    private static final String PATH_PREFIX = "txt/";

    private List<BookRef> books = List.of();

    /** 上一次记进日志的本数，-1 表示还没记过。与另外几个书源同一个理由：别刷屏 */
    private int loggedCount = -1;

    @Override
    public String id() {
        return SOURCE_ID;
    }

    /** 不依赖任何模组，永远在。理由见类注释 */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<BookRef> list() {
        return books;
    }

    @Override
    public void refresh() {
        TxtLibrary.refresh();

        List<TxtLibrary.Entry> entries = TxtLibrary.books();
        List<BookRef> out = new ArrayList<>(entries.size());
        for (TxtLibrary.Entry entry : entries) {
            out.add(new BookRef(SOURCE_ID,
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, PATH_PREFIX + entry.id()),
                    Component.literal(entry.title()),
                    null,
                    ownerLine(entry)));
        }
        books = List.copyOf(out);

        if (books.size() != loggedCount) {
            loggedCount = books.size();
            MCphone.LOGGER.info("[MCphone] {} 里有 {} 本本地小说",
                    TxtLibrary.directory(), loggedCount);
        }
    }

    /**
     * 列表第二行。
     *
     * 别的书写的是"出自哪个模组"，本地书没有模组可写，就写"本地文件"加大小——后者能帮
     * 玩家一眼分出"我下的那本完整的"和"只有前三章的试读"。
     */
    private static String ownerLine(TxtLibrary.Entry entry) {
        return Component.translatable("mcphone.reader.txt.local").getString()
                + " · " + sizeText(entry.bytes());
    }

    /** 人看的大小。不到 1 MB 写 KB，别让"0.1 MB"这种数出现 */
    private static String sizeText(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return Math.max(1, bytes / 1024) + " KB";
    }

    /**
     * 打开一本本地小说 —— 翻到手机自己的阅读页，而不是另开一个 Screen。
     *
     * 与时钟、记事本一致：那是手机里的一页，退出去还是手机。
     */
    @Override
    public void open(BookRef book) {
        TxtLibrary.Entry entry = entryOf(book);
        if (entry == null) {
            // 文件在扫描之后被删了/改名了。不弹提示：下一次刷新它就从列表里消失了
            MCphone.LOGGER.warn("[MCphone] 本地小说 {} 已经不在了，打不开", book.bookId());
            return;
        }

        if (Minecraft.getInstance().screen instanceof PhoneScreen phone) {
            phone.openTxtBook(entry);
        }
    }

    /** 从 BookRef 找回它对应的文件，找不到返回 null */
    public static TxtLibrary.Entry entryOf(BookRef book) {
        if (book == null || !SOURCE_ID.equals(book.sourceId())) return null;

        String path = book.bookId().getPath();
        if (!path.startsWith(PATH_PREFIX)) return null;

        return TxtLibrary.byId(path.substring(PATH_PREFIX.length()));
    }
}
