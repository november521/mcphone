package com.november.mcphone.mixin;

import com.november.mcphone.core.client.AppHotkeyHandler;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把原版键盘事件转发给 App 快捷键处理器。
 *
 * App 快捷键不是 KeyMapping（名单运行期才定，见 AppHotkeys 类注释），所以
 * 不能在 tick 里轮询——比一个 tick 更短的一下会整个丢掉。Fabric 没有 NeoForge
 * 那个 InputEvent，只能用这个小 mixin 在最前面截一道，把原始键码、扫描码、
 * 动作与修饰位交给 AppHotkeyHandler。只注入不动原逻辑，不冲突。
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void mcphone$onKeyPress(long window, int key, int scancode, int action,
                                    int modifiers, CallbackInfo ci) {
        AppHotkeyHandler.onKeyInput(key, scancode, action);
    }
}
