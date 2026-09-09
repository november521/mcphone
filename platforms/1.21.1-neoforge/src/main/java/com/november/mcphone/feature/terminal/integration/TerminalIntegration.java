package com.november.mcphone.feature.terminal.integration;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 一家存储模组的接入点 —— 「这台终端是不是你的，是的话请你自己把它打开」。
 *
 * 这个接口是「终端」这一格的全部业务
 *
 * 范围、耗电、绑的是哪个网络、增幅卡加了多远、界面长什么样，全部在对方的实现里。我们只做
 * 两件事：挑出玩家想开的那一台，然后按<b>它自己的</b>方式打开。
 *
 * <b>接口窄是刻意的</b>：它窄，"联动，不发明"这条原则才守得住。想往里加"这台终端能看到
 * 多少物品""它连上了没有"这类方法之前，先问一句那是不是又要开始自己实现一个终端了。
 * 各家查实过的入口见 docs/terminal-integration.md。
 *
 * 实现类的类加载纪律
 *
 * 每个实现都会引用对方模组的类型，所以<b>实例化必须被 {@code ModPresence.isLoaded} 挡在外面</b>
 * ——这件事由 {@link Terminals#discover()} 统一负责，实现类自己不判断。反过来说，实现类里
 * 可以随便引用对方的类型，因为走到那儿时对方一定在。
 *
 * 这个接口本身的签名里<b>不许</b>出现任何外部模组的类型，只有 Minecraft 与我们自己的
 * ——否则加载这个接口就要解析到那些类，三家里少装一家整个模组就崩了。
 */
public interface TerminalIntegration {

    /** 对方的 modid。判断它在不在场用这个值，别在各处敲字面量 */
    String modId();

    /** 对方的显示名，写死不查。App 详情页与「设置 → 关于」要显示它时，它多半正是没装的那一个 */
    String displayName();

    /**
     * 这台终端归我管吗。
     *
     * 只做类型判断，不问"现在开不开得了"——后者是 {@link #canOpen}。分开是因为两个问题的
     * 答案会不一样：Tom's 的基础无线终端是终端（我们认得），但它<b>远程打不开</b>。
     */
    boolean claims(ItemStack stack);

    /**
     * 这台终端能被"隔空"打开吗。
     *
     * 默认能。会答"不能"的只有 Tom's 的基础无线终端：它的设计是瞄准范围内的终端方块右键，
     * {@code open(Player, ItemStack)} 的方法体是一句 return，从手机上点它<b>什么都不会
     * 发生也不会报错</b>。所以这一问必须存在，而且必须在放进卡槽之前就问（见
     * {@code TerminalSlotMenu} 的 mayPlace）。
     *
     * 这一问只关乎"这一类终端支不支持远程"，不关乎电量、绑没绑定这些会变的状态——那些由
     * 对方在 {@link #open} 里自己查、自己给玩家发消息，我们不替它判断。
     */
    default boolean canOpen(ItemStack stack) {
        return true;
    }

    /**
     * 按这台终端自己的方式打开它。
     *
     * @param source 这台终端在哪儿。实现负责把它翻译成对方认的那种"位置"
     * @return 开成功了没有。false 表示对方拒绝了（电量不足之类），此时<b>对方已经给玩家
     *         发过原因了</b>，调用方不要再补一条
     */
    boolean open(ServerPlayer player, ItemStack stack, TerminalSource source);

    /**
     * 一次性的注册：把「它在手机卡槽里」这种位置登记到对方的注册表里。
     *
     * 由 {@link Terminals#discover()} 在 FMLCommonSetup 里统一调用，两端都调，且在任何菜单
     * 打开之前。Tom's 那家不需要这一步（它没有位置的概念），所以这里有默认实现。
     */
    default void setup() {}
}
