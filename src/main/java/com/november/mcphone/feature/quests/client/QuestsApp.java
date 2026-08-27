package com.november.mcphone.feature.quests.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 「任务书」App —— 主屏一格，点一下就是整合包的任务书。
 *
 * 它解决的是什么
 *
 * 任务书是玩家一局里翻得最勤的一样东西 —— 每做完一件事就要回去看下一步。而它是
 * 一个物品：要么占着快捷栏一格，要么每次都得回箱子里翻。手机里这一格把它变成
 * 「开机 → 点一下」，书本身还在原处，我们只是不再要求玩家随身带着它。
 *
 * 为什么是主屏单独一格，而不是收进「阅读」的书城
 *
 * 因为它和教程书不是一类东西。教程书是"想查点什么才去翻"，几十本，所以要一个
 * 带搜索的列表；任务书全局只有一本，而且是"随时要看"。塞进书城的话，玩家每次
 * 都要走「开机 → 阅读 → 在几十本里找到它 → 点开」，比拿实体书还慢 —— 这个 App
 * 存在的全部意义就是少走几步，多一层列表就把这个意义抵消掉了。
 *
 * 任务界面仍然是 FTB 在画
 *
 * 点开之后接管屏幕的是它自己的界面，章节、进度、领奖、编辑权限全对得上，和在
 * 背包里右键那本书一模一样。我们只提供入口，细节见 {@link FtbQuestsBook}。
 *
 * FTB Quests 是【联动】，不是前置
 *
 * MCphone 本身不需要任何前置就能跑，装不装别的模组只是功能多少的分别 —— 这一格
 * 也一样：没有 FTB Quests，少的是这一个 App，不是手机开不了机。所以这里声明的是
 * companionMods，与「阅读」那边同一个说法。
 *
 * 但这一格与「阅读」有一处不同：它只通往一个地方。「阅读」缺一个书源还有别的书
 * 可看，这一格缺了 FTB Quests 就没有内容可给。所以必须自己覆盖 isAvailable() ——
 * companionMods 不参与默认的可用性判断（那个默认实现只看 requiredMods），照默认
 * 走就成了"永远可用"，于是没装 FTB Quests 的整合包里，主屏上会多一个点了没反应
 * 的图标。
 *
 * 声明成联动之后玩家仍然找得到它：商店的「联动App」页对当前不可用的 App 会回退
 * 去读 companionMods（见 CompanionApps.refresh），所以没装 FTB Quests 时那一页
 * 照样列着「任务书 …… 需要 FTB Quests」；「设置 → 关于」的联动模组清单也会带上。
 *
 * 预装且免费
 *
 * 任务书在绝大多数整合包里是开局白送、丢了还能再做的，卖它没有对应物。而且这个
 * App 存在的意义就是"少走几步"，把它埋进商店等玩家自己发现，等于第一步就多走了
 * —— 与「阅读」同一个理由。
 *
 * 贴图: assets/mcphone/textures/app/quests.png (20×20)
 */
public final class QuestsApp extends PhoneApp {

    public QuestsApp() {
        super("quests");
    }

    /** modid 取兼容层的常量："可用性"与"缺什么"必须是同一个来源 */
    @Override
    public List<RequiredMod> companionMods() {
        return List.of(new RequiredMod(
                FtbQuestsBook.FTBQUESTS_MODID,
                Component.translatable("mcphone.compat.ftbquests").getString()));
    }

    /**
     * 没有 FTB Quests 就没有内容可给，这一格不该出现。
     *
     * 必须自己判：默认实现只看 requiredMods()，而这个 App 一条都不声明 —— 照默认
     * 走就是"永远可用"，主屏上会多一个点了没反应的图标。
     *
     * 这里问的是 ModList，不是"任务档案同步了没"。档案没同步是【一时】的状态，
     * 拿它当可用性会让这一格在进服的头几秒里闪一下才出现；而那种情形 FTB 自己
     * 会在玩家点下去时把话说清楚，见 {@link FtbQuestsBook} 的类注释。
     */
    @Override
    public boolean isAvailable() {
        return FtbQuestsBook.isLoaded();
    }

    /**
     * 与「阅读」里翻开一本书一样，是直接把屏幕交出去，不在手机里另开一页。
     *
     * 开不成时这里【不】提示玩家：能开不成的三种情形（档案没同步、服主关了 GUI、
     * 队伍被锁）FTB 自己都会发聊天或弹 toast，我们再补一句只会重复。返回 false
     * 的唯一含义是"方法没对上"，那是给日志看的，与玩家无关。
     */
    @Override
    public void onPress() {
        FtbQuestsBook.open();
    }
}
