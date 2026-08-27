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
 * 声明成硬前置，不是联动
 *
 * 与「阅读」不同 —— 那个 App 有三个书源，缺一个还有别的书可看，所以它声明的是
 * companionMods。这一格只通往一个地方，FTB Quests 没装它就是彻底不可用，那正是
 * requiredMods 的定义。声明一次，三件事自动发生：对方没装时这一格不进目录、
 * 商店的「联动App」页会列出它缺什么、「设置 → 关于」的联动模组清单也会带上它。
 *
 * 不覆盖 isAvailable()：默认实现就是按 requiredMods() 回答的，覆盖了反而可能
 * 与「联动App」页对不上。
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
    public List<RequiredMod> requiredMods() {
        return List.of(new RequiredMod(
                FtbQuestsBook.FTBQUESTS_MODID,
                Component.translatable("mcphone.compat.ftbquests").getString()));
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
