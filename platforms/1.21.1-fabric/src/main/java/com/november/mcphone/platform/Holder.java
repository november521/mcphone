package com.november.mcphone.platform;

import java.util.function.Supplier;

/**
 * 注册表条目的持有者 —— 让 Fabric 的注册结果与另外两支持的持有者同形。
 *
 * <h2>为什么需要它</h2>
 *
 * shared/ 与 layers/ 是三个目标共编一份代码。它们引用本模组的注册条目时写的
 * 是 {@code ModItems.PHONE.get()} 这种形式 —— 因为 NeoForge 给的是
 * {@code DeferredHolder}、Forge 给的是 {@code RegistryObject}，两者都有
 * {@code get()}，共用侧因此能在三支上写同一句话。
 *
 * Fabric 没有延迟注册：{@code Registry.register} 直接返回注册表里的那个实例，
 * 没有"持有者"这一层，也就没有 {@code get()}。若让共用侧迁就 Fabric 写成裸字段，
 * 另外两支就编不过 —— 而共用侧不该为某一个加载器的形态让步。
 *
 * 所以这一支补一个最小持有者：注册完把结果包进来，共用侧照旧 {@code .get()}。
 *
 * <h2>只做一件事</h2>
 *
 * 这不实现任何注册表 API（{@code Holder}、{@code Supplier} 的登记语义都不需要）——
 * 它只是一个"已经在那儿了"的值加上 {@link #get()}。别往这儿加东西。
 */
public final class Holder<T> implements Supplier<T> {

    private final T value;

    private Holder(T value) {
        this.value = value;
    }

    /** 包住一个已经注册好的实例。 */
    public static <T> Holder<T> of(T value) {
        return new Holder<>(value);
    }

    /** 那个实例。Fabric 上没有"还没注册好"的中间态，恒非 null。 */
    @Override
    public T get() {
        return value;
    }
}
