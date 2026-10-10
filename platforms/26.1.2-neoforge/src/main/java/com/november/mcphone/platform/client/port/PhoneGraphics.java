package com.november.mcphone.platform.client.port;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import java.util.function.Consumer;

/** 26.1 的 GUI 提取接口；共用页面保留原有布局坐标和调用顺序。 */
public final class PhoneGraphics {
    private final GuiGraphicsExtractor graphics;
    private final PoseStack pose = new PoseStack();
    public PhoneGraphics(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
        var m = graphics.pose();
        pose.last().pose().set(new Matrix4f().m00(m.m00()).m01(m.m01())
                .m10(m.m10()).m11(m.m11()).m30(m.m20()).m31(m.m21()));
    }
    /**
     * 一次覆盖层绘制共用同一个旧式矩阵栈，结束时恢复原版矩阵。
     * 旧式 popPose 只恢复适配器本地状态；若中途新建适配器，会继承上一次文字的平移与缩放。
     */
    public static void withIsolatedPose(GuiGraphicsExtractor graphics, Consumer<PhoneGraphics> render) {
        graphics.pose().pushMatrix();
        try {
            render.accept(new PhoneGraphics(graphics));
        } finally {
            graphics.pose().popMatrix();
        }
    }
    public PoseStack pose() { return pose; }
    public GuiGraphicsExtractor nativeGraphics() {
        var m = pose.last().pose();
        graphics.pose().set(new Matrix3x2f(m.m00(), m.m01(), m.m10(), m.m11(), m.m30(), m.m31()));
        return graphics;
    }
    private GuiGraphicsExtractor draw() {
        var result = nativeGraphics();
        // 原版按管线重新组织同层指令；显式分层保持页面原先的先画底、后画字与覆盖层顺序。
        result.nextStratum();
        return result;
    }
    private static int textColor(int color) { return (color & 0xff000000) == 0 ? color | 0xff000000 : color; }
    public void fill(int x0,int y0,int x1,int y1,int color) { draw().fill(x0,y0,x1,y1,color); }
    public void fillGradient(int x0,int y0,int x1,int y1,int c0,int c1) { draw().fillGradient(x0,y0,x1,y1,c0,c1); }
    public void renderOutline(int x,int y,int w,int h,int c) { draw().outline(x,y,w,h,c); }
    public void hLine(int x0,int x1,int y,int c) { draw().horizontalLine(x0,x1,y,c); }
    public void vLine(int x,int y0,int y1,int c) { draw().verticalLine(x,y0,y1,c); }
    public int drawString(Font f,String s,int x,int y,int c) { return drawString(f,s,x,y,c,true); }
    public int drawString(Font f,String s,int x,int y,int c,boolean shadow) { draw().text(f,s,x,y,textColor(c),shadow); return x+(s==null?0:f.width(s)); }
    public int drawString(Font f,Component s,int x,int y,int c) { return drawString(f,s,x,y,c,true); }
    public int drawString(Font f,Component s,int x,int y,int c,boolean shadow) { draw().text(f,s,x,y,textColor(c),shadow); return x+f.width(s); }
    public int drawString(Font f,FormattedCharSequence s,int x,int y,int c) { return drawString(f,s,x,y,c,true); }
    public int drawString(Font f,FormattedCharSequence s,int x,int y,int c,boolean shadow) { draw().text(f,s,x,y,textColor(c),shadow); return x+f.width(s); }
    public void drawCenteredString(Font f,String s,int x,int y,int c) { draw().centeredText(f,s,x,y,textColor(c)); }
    public void drawCenteredString(Font f,Component s,int x,int y,int c) { draw().centeredText(f,s,x,y,textColor(c)); }
    public void drawCenteredString(Font f,FormattedCharSequence s,int x,int y,int c) { draw().centeredText(f,s,x,y,textColor(c)); }
    public void blit(Identifier id,int x,int y,int w,int h,float u,float v,int sw,int sh,int tw,int th) {
        draw().blit(RenderPipelines.GUI_TEXTURED,id,x,y,u,v,w,h,sw,sh,tw,th,PhoneTextureState.color());
    }
    public void renderItem(ItemStack stack,int x,int y) { draw().item(stack,x,y); }
    public void enableScissor(int x0,int y0,int x1,int y1) {
        var g=nativeGraphics();
        // 共用 GuiUtil 已将裁剪框换算成窗口坐标，新版不能再变换一次。
        g.pose().pushMatrix();
        try { g.pose().identity(); g.enableScissor(x0,y0,x1,y1); }
        finally { g.pose().popMatrix(); }
    }
    public void disableScissor() { graphics.disableScissor(); }
    public boolean containsPointInScissor(int x,int y) { return graphics.containsPointInScissor(x,y); }
    public void setColor(float r,float g,float b,float a) { PhoneTextureState.setShaderColor(r,g,b,a); }
    public void flush() { nativeGraphics().nextStratum(); }
}
