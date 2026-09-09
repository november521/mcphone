package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.feature.reader.BookRef;

import java.util.List;

/**
 * 本地 txt 书源 —— 这个目标上<b>还没有</b>。
 *
 * <h2>为什么类要在，实现却是空的</h2>
 *
 * 共用代码点名要它：{@code BookSources} 的 SOURCES 里写着 {@code new TxtBookSource()}，
 * {@code BookList} 拿 {@link #SOURCE_ID} 判一本书是不是本地小说。按
 * {@code shared/PLATFORM-SEAMS.md} 的规矩，被共用代码引用的类型（带 ★ 的那些）是硬约束：
 * 全限定名与签名各平台必须一致，方法体各异。少了它，编不过的是 {@code shared/}。
 *
 * <h2>为什么方法体是空的</h2>
 *
 * 看本地小说这件事，翻书的界面得模组自己画（{@code TxtReaderPage}），而那一页要挂在
 * {@code PhoneScreen} 的页面管线上：一个 Mode、一处渲染分发、返回键、拖 txt 进来收进
 * 目录、HUD 上翻页。1.21.1 那侧是连着这一整套一起做的，这个目标上那套管线还没有。
 *
 * 所以这里<b>不是</b>"实现了但坏掉"，而是"这个目标还没有这个功能"：
 * {@link #isAvailable()} 返回 false、{@link #list()} 空，阅读 App 在 1.20.1 上就和从前
 * 一模一样——只有整合包里那几本教程书。玩家不会在书架上看见一本点不开的小说。
 *
 * <b>移植到这个目标时，把这份换成真的实现</b>，照 1.21.1 那一份抄：它除了
 * {@code PhoneScreen.openTxtBook} 那一句，其余（扫目录、id 用文件名哈希、大小那一行）
 * 与版本、加载器都无关。
 *
 * 同一趟要一起补的（漏了都不会报错，只会静默少一块）：
 * <ul>
 *   <li>{@code PhoneScreen} 的阅读页管线：一个 Mode、渲染分发、返回键、全屏、拖 txt 进来收书</li>
 *   <li>翻页处理器——{@code PhoneKeys.READER_PREV/READER_NEXT} 已经在这个目标上注册出去了，
 *       在此之前它们是按了没反应的死键</li>
 *   <li>{@code ShelfStore.ensureShelved}：本地书不经过书城，不调这一句就上不了架，
 *       表现是"书在、两个页签都看不见"</li>
 * </ul>
 */
public final class TxtBookSource implements BookSource {

    /** 与 {@link BookRef#sourceId()} 对应，别改：玩家书架里的条目按它记 */
    public static final String SOURCE_ID = "txt";

    @Override
    public String id() {
        return SOURCE_ID;
    }

    /** 这个目标上还没有本地阅读器，理由见类注释 */
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<BookRef> list() {
        return List.of();
    }

    @Override
    public void refresh() {
        // 没有目录要扫
    }

    @Override
    public void open(BookRef book) {
        // list() 恒为空，走不到这儿
    }
}
