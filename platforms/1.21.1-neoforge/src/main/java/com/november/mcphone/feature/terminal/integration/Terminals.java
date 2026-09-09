package com.november.mcphone.feature.terminal.integration;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.terminal.integration.ae2.Ae2Integration;
import com.november.mcphone.feature.terminal.integration.refinedstorage.RefinedStorageIntegration;
import com.november.mcphone.feature.terminal.integration.toms.TomsStorageIntegration;
import com.november.mcphone.platform.ModPresence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 这局游戏里装了哪几家存储模组 —— 全模组唯一的分派点。
 *
 * 判断在不在场的地方只有这一处
 *
 * {@link #discover()} 里那三句 {@code isLoaded} 是整个模组仅有的三次判断。判断通过才
 * {@code new} 出对应的联动类，于是<b>没装的那一家，它的联动类一次都不会被加载</b>——
 * 类加载器碰不到它，也就不会 NoClassDefFoundError。判断留在外面这一条是纪律：一旦挪进
 * 联动类内部（哪怕只是个静态方法），判断本身就要求先加载那个类，而那正是要避免的事。
 *
 * 所以这个类的代码里<b>不许</b>出现任何外部模组的类型，连 import 都不许。它认识的只有
 * {@link TerminalIntegration} 这个接口。
 *
 * 谁来调 discover
 *
 * {@link #onCommonSetup} 挂在模组总线上（MCphone 构造函数里挂的），两端都跑。
 *
 * 一个都没装怎么办
 *
 * {@link #anyPresent()} 答 false，「终端」那一格就不上主屏（见 {@code TerminalApp.isAvailable}）。
 * 三家全是软前置，所以这是真会发生的情况，不是理论上的。
 */
public final class Terminals {

    private Terminals() {}

    /**
     * 装了的那几家，构造完之后就不再变。
     *
     * volatile：写在 setup 的主线程上，读在服务端线程与客户端渲染线程上。列表本身是不可变的
     * （{@code List.copyOf}），所以只要保证"看得到写入"就够了。
     */
    private static volatile List<TerminalIntegration> active = List.of();

    /**
     * FMLCommonSetup：挑出装了的那几家。
     *
     * 为什么不在模组构造里做
     *
     * RS 的 {@code RefinedStorageApi.INSTANCE} 是个<b>代理对象</b>，真正的实现由 RS 自己的
     * 初始化挂上去。在构造里调它，靠的是"依赖写了 ordering=AFTER 所以我在它后面"这条推理；
     * setup 阶段则是<b>所有</b>模组都构造完了，不需要推理。
     *
     * {@code enqueueWork} 不能省：setup 是并行派发的，而我们要往 AE2 与 RS 的静态注册表里写。
     */
    public static void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(Terminals::discover);
    }

    /** 挑出装了的那几家，各自做一次性注册。只该被调一次 */
    public static void discover() {
        List<TerminalIntegration> candidates = new ArrayList<>(3);

        if (ModPresence.isLoaded(Ae2Integration.MODID)) candidates.add(new Ae2Integration());
        if (ModPresence.isLoaded(RefinedStorageIntegration.MODID)) candidates.add(new RefinedStorageIntegration());
        if (ModPresence.isLoaded(TomsStorageIntegration.MODID)) candidates.add(new TomsStorageIntegration());

        // 每一家单独兜住 Throwable：一家的注册翻车不该带走另外两家，更不该带走整个模组。
        //
        // 捕 Throwable 而不是 Exception 是刻意的：联动最常见的翻车方式是引用了对方模组里
        // 改了名或已删除的类，那抛出来的是 NoClassDefFoundError / NoSuchMethodError——属于
        // Error，用 Exception 接不住。CompatModules 那边同一个理由。
        List<TerminalIntegration> ready = new ArrayList<>(candidates.size());
        for (TerminalIntegration integration : candidates) {
            try {
                integration.setup();
                ready.add(integration);
            } catch (Throwable t) {
                MCphone.LOGGER.error("「终端」App 接 {} 失败，已跳过这一家", integration.modId(), t);
            }
        }
        active = List.copyOf(ready);

        if (ready.isEmpty()) {
            MCphone.LOGGER.info("一家存储模组都没装，「终端」App 不会出现在主屏上");
        } else {
            MCphone.LOGGER.info("「终端」App 接上了：{}",
                    ready.stream().map(TerminalIntegration::displayName).toList());
        }
    }

    /** 装了的那几家。顺序就是 {@link #discover()} 里的顺序，也就是认领终端时的优先级 */
    public static List<TerminalIntegration> active() {
        return active;
    }

    /** 有没有装上任何一家。一家都没有的话这个 App 没有任何内容可给 */
    public static boolean anyPresent() {
        return !active.isEmpty();
    }

    /**
     * 这台终端归谁管。不是终端则 {@link Optional#empty()}。
     *
     * 认领是先到先得：一件物品同时被两家认领是不可能的（各自认的是自己的类型），真出现了
     * 也说明对方的类型判断有问题，那时按 {@link #discover()} 里的顺序取第一家是可预测的行为。
     */
    public static Optional<TerminalIntegration> owner(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        for (TerminalIntegration integration : active) {
            if (integration.claims(stack)) return Optional.of(integration);
        }
        return Optional.empty();
    }

    /**
     * 这台终端能从手机上打开吗 —— 认得出<b>并且</b>开得了。
     *
     * 卡槽只收这一种（见 {@code TerminalSlotMenu} 的 mayPlace）：装得进去却点不开的东西
     * 比装不进去更难解释。
     */
    public static boolean isOpenable(ItemStack stack) {
        return owner(stack).filter(integration -> integration.canOpen(stack)).isPresent();
    }

    /**
     * 认得出、但远程开不了的那一种吗。
     *
     * 只有 Tom's 的基础无线终端会答 true。拿它来给玩家解释"为什么这台不行"，不然他看到的
     * 是点了没反应。
     */
    public static boolean isRemoteIncapable(ItemStack stack) {
        return owner(stack).filter(integration -> !integration.canOpen(stack)).isPresent();
    }

    /** 背包里有没有一台开得了的终端。含快捷栏、副手与盔甲位，与服务端那一级的范围一致 */
    public static boolean anyOpenableIn(Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isOpenable(inventory.getItem(slot))) return true;
        }
        return false;
    }
}
