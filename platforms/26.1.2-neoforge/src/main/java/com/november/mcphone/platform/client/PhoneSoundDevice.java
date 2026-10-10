package com.november.mcphone.platform.client;
import com.mojang.blaze3d.audio.Library;
/** OpenAL 初始化签名的版本接缝。 */
public final class PhoneSoundDevice {
    private PhoneSoundDevice() {}
    public static void initialize(Library library) { library.init(null,com.mojang.blaze3d.audio.DeviceList.query(),false); }
}
