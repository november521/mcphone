package com.november.mcphone.feature.reader.client.compat;

import com.november.mcphone.feature.reader.BookRef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.fml.ModList;

/**
 * 一条书籍特例 —— 某个模组的书跟别人不一样，这里专门照顾它。
 *
 * 为什么需要这一层
 *
 * {@link com.november.mcphone.feature.reader.client.source.BookSource} 回答的是
 * "书从哪儿来"，它按一套通用规则把书列出来。但总有模组不按那套规则来：
 *
 *   把手册整个换成了自己的界面，Patchouli 里只剩一本没人维护的旧书
 *   （新生魔艺就是，见 {@link ArsNouveauQuirk}）；
 *   在注册表里塞了一本给自己用的空书，不该出现在玩家的书架上；
 *   书名或出处取出来是错的，得改写一下才能看。
 *
 * 这三件事都不是"书源"该操心的——它们与书从哪儿来无关，只与【这一本书】有关。
 * 混进书源里会让通用逻辑长满 if (是某某模组)，而那正是这一层要避免的。
 *
 * 它能做四件事
 *
 * 隐藏（{@link #rewrite} 返回 null）、改写显示（返回一个新的 BookRef）、
 * 接管打开（{@link #open} 返回 true）、自己画图标（{@link #renderIcon} 返回 true）。
 * 够用了：目前遇到的所有"魔改"都落在这四样里。
 *
 * 这是【客户端】接口
 *
 * {@link #renderIcon} 的签名里有 GuiGraphics，实现类只能在客户端加载。整包都在
 * client 包下就是这个意思，别从网络包或物品里引用它。
 *
 * 不能做的是【凭空多出一本书】——那种情况说明对方根本没在 Patchouli 里注册，
 * 该给它写一个自己的 {@code BookSource}，而不是在这里硬造一条。
 *
 * 写一条特例的规矩
 *
 * 1. {@link #matches} 要尽量窄。按书的 id 认，别按命名空间一网打尽——同一个模组
 *    完全可能既有一本魔改的书，又有几本正常的。
 *
 * 2. {@link #rewrite} 不许改 {@link BookRef#sourceId()}。图标还要靠它找回书源，
 *    改了就画不出来了。
 *
 * 3. 碰对方的代码用反射，并且失败要能退回默认行为。理由见 {@link ArsNouveauQuirk}
 *    的类注释——特例名单会越来越长，而它们多半是我们【不愿意】为之加一条编译依赖的模组。
 *
 * 4. 别在这里抛异常。抛了由 {@link BookQuirks} 兜住，但那时这本书已经"半特例"了，
 *    行为不好预期。
 */
public interface BookQuirk {

    /** 这条特例针对哪个模组（modid）。同时用于装载判断与日志 */
    String targetModId();

    /**
     * 现在需不需要启用。默认是"对方装了就启用"。
     *
     * 碰上只存在于对方某个版本区间的毛病，可以覆盖本方法收窄——但要当心：
     * 这个判断只算一次并缓存，别放会随时间变化的条件。
     */
    default boolean isNeeded() {
        return ModList.get().isLoaded(targetModId());
    }

    /** 这本书归这条特例管吗。要尽量窄，理由见类注释 */
    boolean matches(BookRef book);

    /**
     * 改写这本书在书架上的样子。
     *
     * @return 要显示的书；返回传进来那个表示不改；返回 null 表示【别显示这本书】
     */
    default BookRef rewrite(BookRef book) {
        return book;
    }

    /**
     * 接管"打开这本书"。
     *
     * @return true 表示已经打开了，别再走书源默认那条路；false 表示走默认。
     *         用反射打对方的界面时，失败一定要返回 false —— 那样最坏也只是
     *         回到没有这条特例时的行为，而不是一个点了没反应的死按钮
     */
    default boolean open(BookRef book) {
        return false;
    }

    /**
     * 自己画这本书在列表里的图标。
     *
     * 通用那条路（书源画那本书自己的物品）有时走不通——比如物品换成了 3D 模型，
     * 那种在 GUI 里画会伤到整个界面，被 {@code GuiUtil.canDrawItemIcon} 挡掉。
     * 挡掉之后退回的是一张通用书图，认不出是哪本书。这个口子就是给那种情况留的：
     * 模组多半还留着老版本那张平面小图，直接画它，玩家反而更认得。
     *
     * 每帧每行都会问一次，所以这里只能画，别在里头查资源、算路径。
     *
     * @return 真的画了才 true；false 则继续往下退（书源 → 换肤贴图 → 纯色）
     */
    default boolean renderIcon(GuiGraphics g, BookRef book, int x, int y, int size) {
        return false;
    }
}
