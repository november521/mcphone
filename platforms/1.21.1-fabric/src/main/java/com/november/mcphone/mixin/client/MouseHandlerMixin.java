package com.november.mcphone.mixin.client;

import com.november.mcphone.core.client.AppHotkeyHandler;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把鼠标按键事件转给 {@link AppHotkeyHandler#onMouseButton(int, int)}。
 *
 * NeoForge 那条可取消的 {@code InputEvent.MouseButton.Pre} 在 Fabric 上没有
 * 对应物，取消语义由这条 mixin 补：挂在 {@code onPress} 的 HEAD、cancellable，
 * 处理函数吃下了这一下就 cancel，原版（屏幕分发、挥动手、放置方块）全部跳过。
 * 位置比屏幕分发还早，正合上游"绑键界面/手机界面优先收下侧键"的语义。
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void mcphone$onPress(long windowPointer, int button, int action, int modifiers, CallbackInfo ci) {
        if (AppHotkeyHandler.onMouseButton(button, action)) {
            ci.cancel();
        }
    }
}
