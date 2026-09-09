package com.november.mcphone.compat;

import com.november.mcphone.MCphone;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegisterEvent;

/**
 * Integrated Dynamics 兼容——让注册表回滚时不再炸在它的方块上。
 *
 * 这个 bug 到底是怎么回事
 *
 * 先说结论：它掩盖别人的错误，而不是它自己有错。
 *
 * NeoForge 的 GameData.postRegisterEvents 逐个注册表触发 RegisterEvent，
 * 把途中的异常先吞进一个 aggregate；只要【任何一个】模组注册失败，它就
 * 回滚整个注册表到原版状态，然后把真正的错误抛出来：
 *
 *     if (aggregate.getSuppressed().length > 0) {
 *         LOGGER.error("Failed to register some entries, ...");
 *         LOGGER.error("Rolling back to VANILLA state");
 *         RegistryManager.revertToVanilla();   // ← 崩在这一行
 *         throw aggregate;                     // ← 于是永远走不到这里
 *     }
 *
 * 回滚这一步会做三件事，凑在一起就出事：
 *
 *   1. applySnapshot 对方块注册表做 clear(true)，ID 的方块被清空；
 *   2. 但 NeoForgeRegistryCallbacks.BlockCallbacks.addedBlocks 这个集合
 *      并没有跟着清——它只在 onBake 结尾清一次，此刻还存着注册阶段加进去
 *      的全部模组方块；
 *   3. 回滚末尾重新 freeze()，触发 onBake 遍历 addedBlocks，对每个方块调
 *      getLootTable()。轮到 ID 的墙上火把时，它的 lootFrom 供应者去取刚被
 *      清掉的立式火把，DeferredHolder 未绑定 → NullPointerException。
 *
 * 于是真正的错误（aggregate）被这个空指针顶掉，崩溃报告里只剩下一句
 * "Trying to access unbound value: integrateddynamics:menril_torch_stone"，
 * 看起来像是 ID 的锅，其实 ID 只是最后一个倒下的。
 *
 * addedBlocks 是按对象身份哈希排序的 ReferenceOpenHashSet，遍历顺序每次
 * 启动都不同——所以同一台服务器连崩十几次，报出来的方块名会在
 * menril_torch 和 menril_torch_stone 之间来回跳。这也是这个 bug 最迷惑
 * 人的地方。
 *
 * 我们怎么绕开
 *
 * getLootTable() 是带缓存的：
 *
 *     public final ResourceKey&lt;LootTable&gt; getLootTable() {
 *         if (this.drops == null) this.drops = this.lootTableSupplier.get();
 *         return this.drops;
 *     }
 *
 * 所以只要在注册表还完整的时候【主动调一次】，drops 就被永久缓存，之后
 * 回滚时再调直接返回缓存，供应者根本不会被执行，也就碰不到那个已经失效
 * 的 DeferredHolder。
 *
 * 正常启动路径下 NeoForge 自己也会调这个方法（就在 onBake 里），我们只是
 * 把它提前——结果完全一致，不改变任何掉落行为。
 *
 * 【为什么是 ID 的全部方块，而不只是那四个火把】
 * 没写 lootFrom 的方块用的是默认供应者，里面是
 * BuiltInRegistries.BLOCK.getKey(this.asBlock())——回滚后方块已被清掉，
 * getKey 返回 null，紧接着 withPrefix 一样是空指针。两种写法在回滚面前
 * 一样脆，所以一并处理，成本也只是多几百次哈希查找。
 *
 * 【为什么完全不 import ID 的任何类】
 * 我们只用命名空间字符串加原版注册表 API 就够了。不碰对方的类，就不需要
 * compileOnly 依赖，也不必像 {@link CuriosCompat} 那样为了躲
 * NoClassDefFoundError 把判断和调用拆成两个方法。对方改版、改包名、
 * 改类名，这里都不会受影响。
 *
 * 说清楚这个模块【不】能做什么
 *
 * 它救不回服务器。修掉这个空指针之后，回滚会正常跑完，然后上面那句
 * throw aggregate 把真正的错误抛出来——服务器照样起不来。
 *
 * 它的价值是让崩溃报告指认真凶，而不是甩锅给 ID。原本要靠反编译
 * NeoForge 对行号才能看穿的事，之后看一眼崩溃报告就知道了。
 *
 * 【这一支上它是预防性的，那条 bug 链在 Forge 1.20.1 上构不成】
 *
 * 上面整段分析成立的前提是 NeoForge 有一个【独立于注册表之外】的
 * addedBlocks 集合，回滚清了注册表却没清它。实测 Forge 47.4.23 的字节码，
 * 三处都对不上：
 *
 *   1. GameData$BlockCallbacks 【没有 addedBlocks 这个字段】，
 *      它的字段只有一个 INSTANCE；
 *   2. onBake 遍历的是【注册表本身】（registry.iterator()），
 *      回滚后里面只剩原版方块，getLootTable() 根本轮不到模组方块；
 *   3. 于是 postRegisterEvents 末尾那句 throw aggregate 【真的会执行】，
 *      真正的错误本来就报得出来。
 *
 * 也就是说：这个模块在这一支上不修任何东西。它没有害处——prebind 只是
 * 把 getLootTable() 的结果提前缓存住，掉落行为分毫不变——但别指望它在
 * 这里救过谁的崩溃报告。
 *
 * 留着的理由只有一个：万一 Forge 哪天把 onBake 改成 NeoForge 那种形状，
 * 这道保险已经在了。真要清理，整个文件连同 CompatModules 里那一行一起删
 * 即可，删了不会有任何行为变化。
 */
public final class IntegratedDynamicsCompat implements CompatModule {

    private static final String MODID = "integrateddynamics";

    @Override
    public String targetModId() {
        return MODID;
    }

    @Override
    public void apply(IEventBus modEventBus) {
        // LOWEST：要跑在所有模组把方块注册完之后。ID 走的是默认优先级，
        // 排在最后就一定看得到它的全部方块。
        //
        // 另一个可行时机是"BLOCK 之后的下一个注册表"，但那要赌注册表之间的
        // 先后顺序，而且更晚——万一在那之前就有模组抛异常，这一轮事件会被
        // 整个中断，我们根本轮不上。挂在 BLOCK 这一轮的末尾是能拿到的最早
        // 时机，也不依赖任何顺序假设。
        // Forge 的 IEventBus 没有 (优先级, 类, 消费者) 这个三参重载，
        // 只有四参那个，中间多一个 receiveCancelled。传 false：这一轮事件
        // 本来也不可取消，与那边的语义一致
        modEventBus.addListener(EventPriority.LOWEST, false, RegisterEvent.class, this::onRegister);
    }

    private void onRegister(RegisterEvent event) {
        // 这一层 try 不能省。CompatModules 兜的是 apply() 那一步，而这里是
        // 事件总线之后独立发起的回调，栈上没有它的 try。这个方法真抛出去，
        // 就正好变成上面类注释里说的"某个模组注册失败"——我们要修的那场崩溃，
        // 会由我们自己制造出来。
        try {
            if (!Registries.BLOCK.equals(event.getRegistryKey())) return;
            prebindLootTables();
        } catch (Throwable t) {
            MCphone.LOGGER.error("Integrated Dynamics 兼容处理失败，已跳过（不影响本模组其余功能）", t);
        }
    }

    /** 把 ID 所有方块的掉落表提前解析并缓存住 */
    private void prebindLootTables() {
        int bound = 0;
        int failed = 0;

        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            if (!MODID.equals(entry.getKey().location().getNamespace())) continue;
            try {
                entry.getValue().getLootTable();
                bound++;
            } catch (Throwable t) {
                // 单个方块解析不了不是中断的理由：其余方块照样值得定死。
                // 失败的那个 drops 仍是 null，等于什么都没发生，不会更糟。
                failed++;
            }
        }

        if (failed > 0) {
            MCphone.LOGGER.warn("Integrated Dynamics 兼容：已固定 {} 个方块的掉落表，{} 个失败", bound, failed);
        } else {
            MCphone.LOGGER.info("Integrated Dynamics 兼容：已固定 {} 个方块的掉落表", bound);
        }
    }
}
