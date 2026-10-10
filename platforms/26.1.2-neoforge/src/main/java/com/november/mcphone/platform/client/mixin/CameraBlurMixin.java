package com.november.mcphone.platform.client.mixin;

import com.november.mcphone.platform.client.port.PhoneCameraBlur;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在原版实际执行 GUI 模糊时处理相机反馈。
 * Mixin 专用包只放注入类，普通渲染适配留在同级 port 包，避免被 Mixin 限制直接加载。
 */
@Mixin(GameRenderer.class)
abstract class CameraBlurMixin {
    @Inject(method = "processBlurEffect()V", at = @At("HEAD"), cancellable = true)
    private void mcphone$cameraBlur(CallbackInfo callback) {
        if (PhoneCameraBlur.processRequested()) {
            callback.cancel();
        }
    }
}
