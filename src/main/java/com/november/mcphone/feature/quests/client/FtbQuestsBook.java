package com.november.mcphone.feature.quests.client;

import com.november.mcphone.MCphone;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

/**
 * FTB 任务书 —— 手机里那一格通往的地方。
 *
 * 我们只做一件事：替玩家按一下那本书
 *
 * 章节树、任务节点、进度、领奖、队伍、编辑模式，全是 FTB Quests 的。我们不画
 * 界面、不读任务档案、不碰任何进度，只是在玩家点手机图标时，做一次他右键背包里
 * 那本书时也会做的调用。理由与「阅读」那边一字不差：它的任务界面是整屏的，
 * 手机屏幕只有 120×200，放不下；而自己重画就要把它整套任务系统重新实现一遍。
 *
 * 照抄的就是任务书物品那一句
 *
 * {@code QuestBookItem.use()} 在客户端走的整个就是：
 *
 *     dev.ftb.mods.ftbquests.client.FTBQuestsClient.openGui();
 *
 * 所以在手机里点开与在背包里右键，进的是同一个界面、同一段历史记录、同一个
 * 上次停留的章节。
 *
 * 取 FTBQuestsClient 而不是 ClientQuestFile
 *
 * 两条路最终是同一条 —— FTBQuestsClient.openGui() 的字节码整个只有三行，就是
 * 调 ClientQuestFile.openGui() 然后把返回值丢掉。选前者有两个好处：一是它
 * 【返回 void】，而后者返回 QuestScreen，那是个要靠 FTB Library 才解析得出来的
 * 类型；二是物品走的就是这一条，照抄它比照抄它调的东西更不容易跟丢。
 *
 * 签名核对过两头
 *
 * {@code public static void openGui()}，零参。在 1.21.1 全系的头尾两版
 * （2101.1.1 与 2101.1.34）上都是这个签名，中间没改过。
 *
 * 【不要】自己先检查一遍能不能开
 *
 * 它内部的 openQuestGui() 已经按顺序处理好了三种开不了的情形：任务档案还没从
 * 服务端同步过来（给玩家发聊天提示）、服主在配置里关掉了任务书 GUI（弹 toast
 * 「Quest Book GUI is disabled!」）、队伍被锁（弹 toast，带服主写的锁定原因）。
 *
 * 所以这里不去调它的 exists() / canEdit() 先筛一道。筛了的话，玩家点下去得到的
 * 是【静默无反应】，而直接交给它，玩家得到的是和右键实体书一模一样的那句提示 ——
 * 后者才说得清发生了什么。这一层唯一该拦的是"这个方法根本不存在"，那说明版本
 * 对不上，与玩家无关。
 *
 * 反射，不加编译依赖
 *
 * 我们要的只是一个零参方法。而 FTB Quests 不在 Modrinth 上，只有它自己的
 * maven.ftb.dev；真要加编译依赖，还得把 architectury、FTB Library、FTB Teams
 * 一起加上（它的方法签名里带着这几家的类型），外加一个新的 maven 仓库。
 * 为一句 invoke 搭进去这些不值。
 *
 * 断了的代价也可控：{@link #open()} 返回 false，那一格点了没反应但会在日志里
 * 留一行说明是哪个方法没对上。与沉浸工程、GuideME 那两支是同一个取舍。
 *
 * 这是【客户端】类
 *
 * FTB 的任务界面只在客户端存在，本类只从 client 包下引用。类型隔离的规矩照旧：
 * 字段与方法签名里一个 ftbquests 的类型都不许出现，本文件里连 import 都没有。
 */
public final class FtbQuestsBook {

    public static final String FTBQUESTS_MODID = "ftbquests";

    private static final String CLIENT = "dev.ftb.mods.ftbquests.client.FTBQuestsClient";

    private FtbQuestsBook() {}

    /** 解析过了吗。无论成没成都只做一次 */
    private static boolean resolved;

    private static Method openGui;

    /**
     * FTB Quests 装没装。
     *
     * 这个方法里不能出现任何 ftbquests 的东西 —— 它得在对方缺席时也能安全执行。
     * "判断对方在不在"与"真去调它"必须分在两个方法里，理由见 WaystonesCompat
     * 的类注释，那条规矩在这里一字不差地适用。
     */
    public static boolean isLoaded() {
        return ModList.get().isLoaded(FTBQUESTS_MODID);
    }

    /**
     * 打开任务书。
     *
     * @return 真的把话递过去了才 true。返回 false 只有一个含义：那个方法没对上，
     *         也就是 FTB Quests 换了版本而这一层没跟进。开不开得成【不】由这里
     *         判断，理由见类注释
     */
    public static boolean open() {
        if (!resolve()) return false;

        try {
            openGui.invoke(null);
            return true;
        } catch (Throwable t) {
            // 记一次就把方法丢掉：这条路已经证明走不通，之后每次点都记一行没有意义
            MCphone.LOGGER.error("[MCphone] 打开 FTB 任务书失败", t);
            openGui = null;
            resolved = true;
            return false;
        }
    }

    /** 找不到不是错误，只是这个版本对不上 —— 记一行，这一格就此静默 */
    private static boolean resolve() {
        if (resolved) return openGui != null;
        resolved = true;

        try {
            openGui = Class.forName(CLIENT).getMethod("openGui");
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 没对上 FTB Quests 的 {}.openGui()（{}），"
                    + "「任务书」那一格将点不开。这多半是它改了客户端入口，这一层需要跟进",
                    CLIENT, t.toString());
            openGui = null;
            return false;
        }
    }
}
