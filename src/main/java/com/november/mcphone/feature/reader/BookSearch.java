package com.november.mcphone.feature.reader;

import java.util.Locale;

/**
 * 书架搜索的算术 —— 一条查询对上一本书，该不该显示、排多前。
 *
 * 为什么单独一个类，而且【不 import 任何 Minecraft 类型】
 *
 * 与 {@link com.november.mcphone.core.client.HomeLayout} 同一个理由：这里全是
 * 字符串比较与打分，而这类错误在游戏里靠肉眼极难分辨——你只看见"搜出来的顺序
 * 有点怪"，看不出是大小写没归一、还是模组名压过了书名。单独摆出来、不碰
 * Minecraft，就能用 javac 编出来跑断言（{@code docs/BookSearchTest.java}），
 * 不必开游戏。
 *
 * 三个字段都参与匹配，理由是玩家不知道自己记得的是哪一个
 *
 *   书名   —— 客户端语言下的名字，中文客户端里多半是中文
 *   模组名 —— 模组自己的显示名，几乎总是英文（"Ars Nouveau"）
 *   书 id  —— 形如 ars_nouveau:worn_notebook，命名空间就是 modid
 *
 * 中文客户端里，一本书的名字是中文、出处是英文，这很常见。只搜其中一个字段，
 * 就会出现"我明明记得这个模组叫什么，却搜不到它的书"。id 那一条是兜底：模组的
 * 显示名被本地化成中文、或者起了个跟 modid 毫不相干的花名时，打 modid 仍然找得到。
 *
 * 分数决定顺序，不只决定显不显示
 *
 * 书名命中排在模组名命中前面：打"新生"的人要的是那本书，不是"出自某个名字里
 * 带新生的模组"的一堆书。同理前缀命中排在中间命中前面。
 *
 * 多个词是【与】的关系
 *
 * "ars 笔记"要求两个词都命中，各自可以命中不同字段——这正是"我记得是新生魔艺
 * 的某本笔记"这种找法。整条的分数取各词里【最低】的那个：一个词靠书名命中、
 * 另一个词只靠 id 蹭上，这本书不该排在两个词都命中书名的那本前面。
 *
 * 没做拼音
 *
 * 打 "xsmy" 找"新生魔艺"要一张拼音表（多音字还得挑），那是另一件事。眼下中文
 * 靠书名、英文靠模组名与 id，覆盖得住绝大多数找法。
 */
public final class BookSearch {

    private BookSearch() {}

    /** 没命中 */
    public static final int NO_MATCH = 0;

    private static final int TITLE_EXACT = 100;
    private static final int TITLE_PREFIX = 80;
    private static final int TITLE_CONTAINS = 60;
    private static final int OWNER_PREFIX = 45;
    private static final int OWNER_CONTAINS = 35;
    private static final int ID_CONTAINS = 20;

    /**
     * 这本书对这条查询的分数，越大越靠前。
     *
     * 空查询返回 {@link #NO_MATCH}，【不是】"全都匹配"：查询为空时该显示整架书、
     * 按原顺序排，那是调用方的事，不该在这里冒充成一个分数。调用方必须先判空。
     *
     * @param query 玩家打的字，可以带多个空格分开的词
     * @param title 书名，客户端语言
     * @param owner 出自哪个模组，显示名；null 当空串
     * @param id    书的 id，形如 {@code ars_nouveau:worn_notebook}；null 当空串
     * @return 分数，{@link #NO_MATCH} 表示这本书不该出现在结果里
     */
    public static int score(String query, String title, String owner, String id) {
        if (query == null) return NO_MATCH;

        String q = query.trim();
        if (q.isEmpty()) return NO_MATCH;

        String lowerTitle = lower(title);
        String lowerOwner = lower(owner);
        String lowerId = lower(id);

        int weakest = Integer.MAX_VALUE;
        for (String term : q.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (term.isEmpty()) continue;

            int score = termScore(term, lowerTitle, lowerOwner, lowerId);
            if (score == NO_MATCH) return NO_MATCH;     // 有一个词没命中，整本就不算命中
            weakest = Math.min(weakest, score);
        }

        return weakest == Integer.MAX_VALUE ? NO_MATCH : weakest;
    }

    /** 一个词能拿到的最好分数。书名优先于模组名，前缀优先于中间 */
    private static int termScore(String term, String title, String owner, String id) {
        if (title.equals(term)) return TITLE_EXACT;
        if (title.startsWith(term)) return TITLE_PREFIX;
        if (title.contains(term)) return TITLE_CONTAINS;
        if (owner.startsWith(term)) return OWNER_PREFIX;
        if (owner.contains(term)) return OWNER_CONTAINS;
        if (id.contains(term)) return ID_CONTAINS;
        return NO_MATCH;
    }

    /**
     * 归一化。
     *
     * 用 Locale.ROOT 而不是默认区域：土耳其语区域下 "I".toLowerCase() 得到的是
     * "ı"（无点小写 i），于是土耳其玩家搜 "Iron" 会一本都搜不到，而且我们这边
     * 复现不出来。查询与被查的字段必须用同一个区域归一，这里统一钉死 ROOT。
     */
    private static String lower(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }
}
