package com.november.mcphone.core;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 跟着物品走的手机数据 —— 眼下只有一样：这一部手机叫什么。
 *
 * <h2>为什么设备名要存在物品堆上</h2>
 *
 * 壁纸存在玩家身上（{@link PhonePlayerData}），因为壁纸是"这个玩家的偏好"。
 * 设备名不同，它是"这一只手机的名字"：玩家可以有好几只手机，名字得跟着物品走，
 * 丢在地上、放进箱子、交易给别人都还在。
 *
 * <h2>为什么要这么一层，明明只是转调</h2>
 *
 * 因为<b>存在哪儿</b>这件事两个加载器上不一样，而<b>怎么用</b>是一样的。
 *
 * 这边（1.21.1）用的是数据组件，{@link ModDataComponents#DEVICE_NAME}；1.20.5
 * 起原版才有这东西，1.20.1 那一支只能退回裸 NBT。原先四处调用点各写各的
 * {@code stack.get(ModDataComponents.DEVICE_NAME.get())} —— 四个文件、五处调用点，
 * 在两支之间必然分叉；收进来之后，分叉只剩这个文件。
 *
 * <b>这一层不改存储。</b>这边仍然是数据组件，存档格式一个字节没动 —— 它只是把
 * 调用点拢到一处。
 *
 * <h2>两支之间真正的差别，在读的那一侧</h2>
 *
 * 组件是<b>带类型</b>的：读出来要么是 String 要么是 null，由类型系统兜着。
 * 1.20.1 那边的 NBT 是自由格式的，读出来可能是任何东西 —— 玩家用 {@code /give}
 * 塞进来的、老存档留下的、别的模组写的。所以那边的 {@code getDeviceName} 必须
 * 显式判一次标签类型，不能直接 {@code getString}。
 *
 * 也就是说这三个方法的<b>签名</b>两支相同（调用点因此逐字相同），<b>方法体</b>
 * 注定不同。这正是这一层要挡住的那道缝。
 *
 * <h2>为什么这个是静态工具，而 {@link PhonePlayerData} 是实例视图</h2>
 *
 * 两边形状不一样是有理由的，不要为了"统一"把它们改成一种。
 *
 * 这里的把手<b>就是那个 ItemStack</b> —— 它由调用方拿着、逐次传进来，没有第二样
 * 东西要握住，所以静态方法正合适；那边的 NBT 辅助方法也是静态的，两支对得上。
 *
 * {@link PhonePlayerData} 不同：1.20.1 那边 {@code ModCapabilities.of(player)}
 * 返回的是<b>真的存储对象</b>（capability 实例），调用点长成"取一个东西、再在它上面
 * 读写"。这边要跟那个形状对齐，才做成视图。把这个类也改成视图，反而会和那边对不上。
 */
public final class PhoneItemData {

    private PhoneItemData() {}

    /**
     * 这一部手机的名字，没起过名则为 {@code null}。
     *
     * 返回 null 而不是空串：<b>"没起过名"与"起了个空名"不是一回事</b>，前者要退回
     * 默认物品名。{@link #setDeviceName} 会把空白名字转成"清除"，所以正常路径上
     * 存不进一个空白的名字。
     *
     * ⚠ <b>但读的那一侧仍然要自己判一次 {@code isBlank()}</b>，别信这条：数据组件是
     * 公开的，命令、别的模组、以及这道规范化之前的老存档都能让它里头躺着一个空白串。
     * {@code PhoneItem.getName} 与 {@code PhoneScreen} 都是这么防的。
     */
    public static @Nullable String getDeviceName(ItemStack stack) {
        return stack.get(ModDataComponents.DEVICE_NAME.get());
    }

    /**
     * 给这一部手机起名。<b>空白名字一律当作"清除"</b>，等同 {@link #clearDeviceName}。
     *
     * 存一个空的设备名没有意义：它表达的东西和"没起过名"完全一样，却会干扰物品比较
     * （合并堆叠、配方匹配）。
     *
     * <b>这道判断为什么在这里而不在调用者那儿。</b>它原先写在网络层的 if/else 里，
     * 而那正是这个门面要收编的东西 —— 留在外面的话，1.20.1 那边把方法体换成裸 NBT
     * （{@code putString} 对空串毫无意见）时，这条约束只剩"调用者自觉"。
     *
     * 判的是 {@code isBlank()} 不是 {@code isEmpty()}：全是空格的名字渲染出来什么都
     * 没有，和空串是一回事。网络层送进来的已经 trim 过，但这个方法是公开的。
     */
    public static void setDeviceName(ItemStack stack, String name) {
        if (name.isBlank()) {
            clearDeviceName(stack);
            return;
        }
        stack.set(ModDataComponents.DEVICE_NAME.get(), name);
    }

    /** 抹掉名字，恢复默认物品名。未命名的手机<b>不带这个组件</b>，而不是带一个空值。 */
    public static void clearDeviceName(ItemStack stack) {
        stack.remove(ModDataComponents.DEVICE_NAME.get());
    }

    /**
     * 这一部手机的屏幕正亮着吗 —— 物品模型据此在黑屏与白屏之间切。
     *
     * 与设备名不同，这一条<b>不落盘</b>（见 {@link ModDataComponents#SCREEN_ON}）：它说的是
     * "此刻有人正开着它"，不是手机自身的属性。写它的只有服务端收到
     * {@code PhoneScreenOnPacket} 那一处，读它的只有渲染那一处。
     */
    public static boolean isScreenOn(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.SCREEN_ON.get(), false);
    }

    /** 点亮这一部的屏幕 */
    public static void setScreenOn(ItemStack stack) {
        stack.set(ModDataComponents.SCREEN_ON.get(), true);
    }

    /** 灭掉这一部的屏幕。灭着的手机<b>不带这个组件</b>，而不是带一个 false */
    public static void clearScreenOn(ItemStack stack) {
        stack.remove(ModDataComponents.SCREEN_ON.get());
    }
}
