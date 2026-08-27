package com.november.mcphone.feature.reader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** BookSearch 的断言测试，用 javac 单独编，不需要 Minecraft。 */
public class BookSearchTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    /** 一本书：书名、模组名、id */
    record Book(String title, String owner, String id) {}

    static final Book NOTEBOOK = new Book("破旧的笔记本", "Ars Nouveau", "ars_nouveau:worn_notebook");
    static final Book LEXICA   = new Book("植物学词典", "Botania", "botania:lexicon");
    static final Book GUIDE    = new Book("Mekanism Guide", "Mekanism", "mekanism:guide");
    static final List<Book> SHELF = Arrays.asList(NOTEBOOK, LEXICA, GUIDE);

    static int score(String q, Book b) {
        return BookSearch.score(q, b.title(), b.owner(), b.id());
    }

    static boolean hit(String q, Book b) {
        return score(q, b) != BookSearch.NO_MATCH;
    }

    /** 按分数排出来的书名顺序，与界面里那一列一致 */
    static List<String> results(String q) {
        List<Book> matched = new ArrayList<>();
        for (Book b : SHELF) if (hit(q, b)) matched.add(b);
        matched.sort(Comparator.comparingInt((Book b) -> score(q, b)).reversed());
        List<String> out = new ArrayList<>();
        for (Book b : matched) out.add(b.title());
        return out;
    }

    public static void main(String[] args) {
        byTitle();
        byModName();
        byModId();
        emptyQueryIsNotAMatch();
        caseAndSpace();
        titleBeatsOwner();
        multipleTerms();
        noFalsePositives();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    static void byTitle() {
        check(hit("笔记", NOTEBOOK), "打书名中间几个字要能找到");
        check(hit("破旧", NOTEBOOK), "打书名开头也要能找到");
        check(hit("破旧的笔记本", NOTEBOOK), "打全名当然要找到");
        check(hit("词典", LEXICA), "另一本也一样");
    }

    static void byModName() {
        check(hit("ars", NOTEBOOK), "打模组名要能找到它的书");
        check(hit("nouveau", NOTEBOOK), "模组名的后半段也算");
        check(hit("botania", LEXICA), "英文模组名整个打出来");
    }

    /** 中文客户端里模组名可能被本地化，modid 是最后那条兜底 */
    static void byModId() {
        check(hit("worn_notebook", NOTEBOOK), "打 id 的路径段要能找到");
        check(hit("ars_nouveau", NOTEBOOK), "打 modid 要能找到");
        check(hit("lexicon", LEXICA), "id 里的词也算");
    }

    /** 空查询不是"全都匹配"：那种情况该整架书按原顺序显示，由调用方处理 */
    static void emptyQueryIsNotAMatch() {
        eq(score("", NOTEBOOK), BookSearch.NO_MATCH, "空串不该算命中");
        eq(score("   ", NOTEBOOK), BookSearch.NO_MATCH, "全是空格也不该算命中");
        eq(score(null, NOTEBOOK), BookSearch.NO_MATCH, "null 不该崩，也不该算命中");
    }

    static void caseAndSpace() {
        check(hit("ARS", NOTEBOOK), "大写要能搜到");
        check(hit("ArS", NOTEBOOK), "大小写混着也要能搜到");
        check(hit("  ars  ", NOTEBOOK), "前后空格要被吃掉");
        eq(score("ars", NOTEBOOK), score("ARS", NOTEBOOK), "大小写不该影响排序");
    }

    /**
     * 坑：模组名命中若与书名命中同分，"某个模组的所有书"会压在"名字就叫这个的
     * 那本书"前面，而后者才是打这几个字的人要找的。
     */
    static void titleBeatsOwner() {
        Book named = new Book("Mekanism Guide", "Mekanism", "mekanism:guide");
        Book other = new Book("附加机器手册", "Mekanism", "mekanismadditions:guide");
        check(BookSearch.score("mekanism", named.title(), named.owner(), named.id())
                > BookSearch.score("mekanism", other.title(), other.owner(), other.id()),
                "书名里带这个词的，要排在只是同一个模组出品的前面");

        check(BookSearch.score("破旧", NOTEBOOK.title(), NOTEBOOK.owner(), NOTEBOOK.id())
                > BookSearch.score("nouveau", NOTEBOOK.title(), NOTEBOOK.owner(), NOTEBOOK.id()),
                "书名前缀命中要高于模组名中间命中");
    }

    static void multipleTerms() {
        check(hit("ars 笔记", NOTEBOOK), "两个词分别命中模组名与书名，算命中");
        check(!hit("ars 词典", NOTEBOOK), "有一个词没命中，整本就不算");
        check(!hit("botania 笔记", LEXICA), "同上，反过来也是");

        // 整条的分数取最弱那一环：一个词只靠 id 蹭上，不该排到两个词都命中书名的前面
        int weak = BookSearch.score("ars 笔记", NOTEBOOK.title(), NOTEBOOK.owner(), NOTEBOOK.id());
        int strong = BookSearch.score("破旧 笔记", NOTEBOOK.title(), NOTEBOOK.owner(), NOTEBOOK.id());
        check(strong > weak, "两个词都命中书名的，要比一个词靠模组名蹭上的分高");
    }

    static void noFalsePositives() {
        check(!hit("thermal", NOTEBOOK), "毫不相干的词不能命中");
        check(!hit("笔记", LEXICA), "别的书的名字不能命中这一本");
        eq(results("笔记"), List.of("破旧的笔记本"), "只该出来一本");
        eq(results("guide").size(), 1, "guide 只命中 Mekanism 那本");
        eq(results("zzz"), List.of(), "搜不到就是空表，不是全部");
    }
}
