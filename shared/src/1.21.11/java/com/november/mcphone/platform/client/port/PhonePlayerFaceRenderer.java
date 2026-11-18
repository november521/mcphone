package com.november.mcphone.platform.client.port;
import net.minecraft.resources.Identifier;
/** 原版头像助手已移除，仍按皮肤 8×8 方形头部和帽子层绘制。 */
public final class PhonePlayerFaceRenderer {
    private PhonePlayerFaceRenderer() {}
    public static void draw(PhoneGraphics g,Identifier skin,int x,int y,int size) {
        g.blit(skin,x,y,size,size,8,8,8,8,64,64);
        g.blit(skin,x,y,size,size,40,8,8,8,64,64);
    }
}
