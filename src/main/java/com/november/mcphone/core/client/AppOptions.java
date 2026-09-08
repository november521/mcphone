package com.november.mcphone.core.client;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 每个 App 自己的开关 —— 画在「设置 → App 管理器 → 某个 App」那一页的操作区里。
 *
 * App 管理页从立起来的那天就是按"操作区一行一个、加一个开关就是加一行"设计的
 * （见 {@link com.november.mcphone.feature.settings.client.AppManagerDetail} 的类注释）。
 * 但那一页在 feature/settings 下，而开关属于各自的功能（相机的闪光归相机管），
 * 让那一页去 import 每一个 feature 是最糟的一种耦合：以后每加一个开关都要改它。
 * 所以中间放这张表——功能自己登记，管理页只管照着画。
 *
 * 为什么不做进 IPhoneApp
 *
 * 那个接口是【公开契约】（wiki 的附属接口文档），加进去就等于承诺"开关长这样"，
 * 而现在只有一个开关，形状还没被第二个、第三个用例检验过。先在内建 App 上跑几版，
 * 等真的定下来再考虑开放给附属——反过来（先公开再改）就只能靠加版本号收场。
 *
 * 登记的时机：App 自己的构造函数里，与它的 id 同时存在，不必在别处再写一遍 id。
 */
public final class AppOptions {

    private AppOptions() {}

    /**
     * 一个开关。
     *
     * 三个翻译键分别是行首那句话、开着时右边那两个字、关着时右边那两个字。
     * 不写死"开/关"是因为多数开关真正的意思不是开关：相机那个开着是"模糊"、
     * 关着是"白闪"，写成开/关的话玩家得先猜"开的是什么"。
     *
     * 值不存在这里：getter/setter 指向功能自己那份状态（多半又是配置里的一项），
     * 这张表只是个入口。存两份迟早对不上。
     */
    public record Toggle(String labelKey, String onKey, String offKey,
                         BooleanSupplier getter, Consumer<Boolean> setter) {

        public boolean value() {
            return getter.getAsBoolean();
        }

        public void flip() {
            setter.accept(!getter.getAsBoolean());
        }

        /** 右边那两个字的翻译键 */
        public String valueKey() {
            return value() ? onKey : offKey;
        }
    }

    private static final Map<ResourceLocation, List<Toggle>> BY_APP = new LinkedHashMap<>();

    /**
     * 登记一个开关。同一个 App 同一个 labelKey 只留最后一次——App 目录万一被重建，
     * 构造函数会再跑一遍，不去重的话那一页上就会出现两行一模一样的开关。
     */
    public static void register(ResourceLocation appId, Toggle toggle) {
        List<Toggle> list = BY_APP.computeIfAbsent(appId, k -> new ArrayList<>(1));
        list.removeIf(t -> t.labelKey().equals(toggle.labelKey()));
        list.add(toggle);
    }

    /** 这个 App 有哪些开关，没有就是空表 */
    public static List<Toggle> of(ResourceLocation appId) {
        return BY_APP.getOrDefault(appId, List.of());
    }
}
