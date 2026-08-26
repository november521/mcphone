package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.feature.reader.BookRef;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * 一个书源 —— 书是从哪儿来的。
 *
 * 这个接口存在的全部理由
 *
 * 书架页只认 {@link BookRef}，不知道这本书是哪套体系的。多接一种书＝写一个
 * 实现 + 在 {@link BookSources} 的名单里加一行，界面一个字都不用改。
 *
 * 眼下只有 Patchouli 一个。留着口子是因为教程书这件事天然不止一家：别的手册
 * 模组、任务书、玩家自己传进来的书，形状都一样——列出来、画个图标、点开。
 *
 * 这是【客户端】接口
 *
 * {@link #renderIcon} 的签名里有 GuiGraphics，实现类只能在客户端加载。整包
 * 都在 client 包下就是这个意思，别从网络包或物品里引用它。
 *
 * 实现要守的规矩
 *
 * 1. {@link #list()} 必须便宜。界面每帧都可能问一次，所以它只能返回缓存好的
 *    东西，真正的扫描放进 {@link #refresh()}。这条与音乐那边的 MusicSource
 *    是同一条，理由也一样。
 *
 * 2. 包着别的模组的实现，"判断对方在不在"与"真去调它"必须分在两个方法里。
 *    写在同一个方法里的话，那句 if 还没轮到执行，方法本身就会因为解析不到
 *    对方的类而抛 NoClassDefFoundError——那是 Error 不是 Exception。
 *    详见 WaystonesCompat 的类注释，那条规矩在这里一字不差地适用。
 *
 * 3. 出错要自己兜住并记日志，别把异常抛给界面。一个书源坏了应该是"这一类书
 *    不见了"，不是整个手机打不开。
 *
 * 将来的搜索挂在哪儿
 *
 * 挂在这个接口上，不在界面上——每个书源只有自己知道怎么搜自己的书。Patchouli
 * 那边现成的是 BookEntry.isFoundByQuery 与 openBookEntry(书, 条目, 页)，
 * 也就是说搜出来能直接跳到那一页；别的书源未必有这个能力，所以它该是个带默认
 * 实现（返回空）的方法，而不是所有人都得实现的抽象方法。
 */
public interface BookSource {

    /** 稳定标识，写进 {@link BookRef#sourceId()}。别改，打开一本书要靠它找回书源 */
    String id();

    /**
     * 这个书源现在能不能用——通常就是"对方模组装没装"。
     *
     * 返回 false 时 {@link BookSources} 连 {@link #refresh()} 都不会调，
     * 所以实现里可以放心假设：轮到别的方法执行时，对方一定在场。
     */
    boolean isAvailable();

    /** 当前的书。必须便宜，理由见接口注释 */
    List<BookRef> list();

    /** 重新扫一遍。由书架页在打开时调用 */
    void refresh();

    /** 打开一本书。翻书的界面由书源自己决定——Patchouli 的书就还给 Patchouli 画 */
    void open(BookRef book);

    /**
     * 画这本书在列表里的小图标。
     *
     * @return 真的画了才 true。返回 false 时由界面兜底（换肤贴图，再不行纯色），
     *         与 {@link com.november.mcphone.core.client.PhoneSkin#draw} 的约定一致
     */
    default boolean renderIcon(GuiGraphics g, BookRef book, int x, int y, int size) {
        return false;
    }

    /**
     * 一个模组的显示名，列表第二行写的就是它。
     *
     * 从 ModList 现拿而不是写死：这种名字对方自己会改，有的模组还会本地化它。
     * 查不到就退回 modid——总比空着强，至少玩家还认得出是哪一个。
     */
    static String modName(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(modId);
    }
}
