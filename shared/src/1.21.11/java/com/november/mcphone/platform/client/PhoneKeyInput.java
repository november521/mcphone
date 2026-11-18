package com.november.mcphone.platform.client;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
/** 保留扫描码与键码含义，转换新版 KeyEvent 签名。 */
public final class PhoneKeyInput {
    private PhoneKeyInput() {}
    public static InputConstants.Key key(int code,int scan) { return InputConstants.getKey(new net.minecraft.client.input.KeyEvent(code,scan,0)); }
    public static boolean matches(KeyMapping key,int code,int scan) { return key.matches(new net.minecraft.client.input.KeyEvent(code,scan,com.november.mcphone.platform.client.port.PhoneInputs.modifiers())); }
}
