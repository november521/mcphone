package com.november.mcphone.feature.reader.client.source;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;

/**
 * 白名单里的一本外部手册 —— 压根不是 Patchouli 书的那种。
 *
 * 为什么要有这一类
 *
 * {@link PatchouliSource} 能把书列全，前提是对方【在 Patchouli 里注册过】。有的
 * 模组自己写了一整套手册系统，与 Patchouli 毫无关系——沉浸工程的工程师手册就是，
 * 它有自己的 ManualInstance、自己的界面、自己的条目树。这种书在 Patchouli 的
 * 注册表里一条都查不到，所以扫描扫不出来，而玩家照样要看。
 *
 * 与「书籍特例」的分界线
 *
 * {@link com.november.mcphone.feature.reader.client.compat.BookQuirk} 管的是
 * "这本书扫出来了，但要特殊对待"；这里管的是"这本书根本扫不出来，得我们报上去"。
 * 特例那一层刻意不给"凭空多出一本书"的能力，正是为了把这两件事分开——否则
 * 特例名单会慢慢长成第二个书源，而它的每条规则都要在扫描结果上打补丁。
 *
 * 所以是白名单，不是自动发现
 *
 * 每个这样的模组都有自己一套打开方式，没有任何通用协议可循，只能一个一个适配。
 * 加一条就是写一个实现、往 {@link ExternalBookSource} 的名单里加一行。
 *
 * 实现要守的规矩
 *
 * 1. {@link #bookId()} 一旦发布就【不能改】：玩家的书架按它记（见 ShelfStore），
 *    改了等于把所有人收藏的这本书弄丢。
 *
 * 2. 碰对方的代码一律走反射，别加编译依赖。理由与 ArsNouveauQuirk 那条一样：
 *    为一两个方法搭进去十几 MB 的 jar 不值，而这份名单只会越来越长。
 *
 * 3. {@link #open()} 失败要返回 false 并且自己记一次日志，别抛。上层会把它
 *    当成"这本书打不开"，而不是让整个界面崩掉。
 *
 * 4. "对方装没装"（{@link #isAvailable()}）与"真去调它"必须分在不同方法里，
 *    字段与方法签名里不许出现对方的类型——与 PatchouliSource 同一条规矩。
 */
public interface ExternalBook {

    /** 这本手册出自哪个模组（modid），同时用于判断在不在场 */
    String modId();

    /** 书 id，写进 BookRef。发布之后别改，理由见接口注释 */
    ResourceLocation bookId();

    /**
     * 这本手册在游戏里对应的物品，用来画图标、也用来取书名。
     * 没有对应物品的返回 null，那种情况书名由 {@link #title()} 自己给。
     */
    ResourceLocation item();

    /** 书名。多数实现直接走 {@link #itemTitle} */
    Component title();

    /**
     * 打开它自己的手册界面。
     *
     * @return true 表示真的打开了；false 表示这条路走不通（对方改了 API），
     *         由上层记账，玩家至少不会点到一个假装成功的按钮
     */
    boolean open();

    /** 这个方法里不能出现对方模组的任何类型，否则它自己就先炸了 */
    default boolean isAvailable() {
        return ModList.get().isLoaded(modId());
    }

    /**
     * 拿物品的名字当书名。
     *
     * 本地化是现成的，而且与玩家在背包里、在 JEI 里看到的那一行字完全一致——
     * 自己在语言文件里另写一份"工程师手册"，对方改了名我们就对不上了。
     *
     * @param fallback 物品不在（对方改了注册名）时用的名字
     */
    static Component itemTitle(ResourceLocation itemId, Component fallback) {
        if (itemId == null) return fallback;

        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == Items.AIR) return fallback;

        return new ItemStack(item).getHoverName();
    }
}
