package com.november.mcphone.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;

/**
 * 把 {@link PhoneKeys} 声明的那几个键在 Forge 上建出来并注册。
 *
 * <h2>这个类为什么这么薄</h2>
 *
 * 键<b>有哪些、叫什么、默认绑哪个键位</b>写在 {@link PhoneKeys}，一份，所有目标共用。
 * 这里只做 Forge 自己那部分：{@link KeyConflictContext#IN_GAME}（"只在游戏里生效"
 * 这件事各加载器各有各的类）与往模组总线注册。
 *
 * 照 {@link PhoneKeys#ALL} <b>循环</b>建，不逐个点名 —— 逐个点名正是从前那个坏的来源：
 * 加一个键要在每个加载器层各补一行，漏掉哪一个都不报错，那个目标上按键设置里就少一行、
 * 依赖它的功能静默失灵。循环之后"漏掉某个加载器"这件事不可能发生。
 *
 * <h2>总线</h2>
 *
 * {@code RegisterKeyMappingsEvent} 是<b>模组总线</b>事件，由 MCphoneClient 的构造函数用
 * {@code modEventBus.addListener(MCphoneKeyBindings::register)} 显式挂载。
 *
 * 这里刻意不用注解自动注册：按键注册在模组总线、而相机的按键监听与渲染在游戏总线，
 * 两者混在一个类里靠注解自动路由容易出错，且出错时不报错、事件直接不触发，排查成本很高。
 */
public final class MCphoneKeyBindings {

    private MCphoneKeyBindings() {}

    /** 由 MCphoneClient 构造函数挂到模组总线 */
    public static void register(RegisterKeyMappingsEvent event) {
        for (PhoneKeys.Key key : PhoneKeys.ALL) {
            KeyMapping mapping = new KeyMapping(
                    key.id(),
                    KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM,
                    key.defaultCode(),
                    PhoneKeys.CATEGORY);

            // 建好之后交回声明那一侧：共用代码通过 PhoneKeys.XXX 用它，不认识这个类
            key.bind(mapping);
            event.register(mapping);
        }
    }
}
