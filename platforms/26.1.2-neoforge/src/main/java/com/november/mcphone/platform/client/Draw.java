package com.november.mcphone.platform.client;
import com.november.mcphone.platform.client.port.PhoneGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
/** 26.1 使用原版 GUI 提取管线，不再调用已移除的即时 BufferUploader。 */
public final class Draw {
    private Draw() {}
    /** 背景已由 Screen.extractBackground 在内容之前提取，不能在内容阶段再次模糊。 */
    public static void screenBackground(Screen s,PhoneGraphics g,int x,int y,float a) {}
    public static boolean scissorLeaked(PhoneGraphics g) { return !g.containsPointInScissor(0,0); }
    public static void browserFrame(PhoneGraphics g,int texture,int x,int y,int w,int h) {
        com.november.mcphone.platform.client.port.PhoneBrowserTexture.draw(g,texture,x,y,w,h);
    }
    public static boolean canDrawItemIcon(ItemStack stack) {
        var mc=Minecraft.getInstance();
        var state=new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state,stack,ItemDisplayContext.GUI,mc.level,mc.player,0);
        // 新版特殊物品也由原版 GUI 提取器处理；先解析成功才提交，保留缺失模型的回退。
        return !state.isEmpty();
    }
}
