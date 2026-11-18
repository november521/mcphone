package com.november.mcphone.platform.client.port;
import com.mojang.blaze3d.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
/** Chromium 旧 OpenGL 纹理复制到原版持有的 GPU 纹理后入队，避免立即绘制被新 GUI 覆盖。 */
public final class PhoneBrowserTexture {
    private static final Identifier ID=Identifier.fromNamespaceAndPath("mcphone","browser/frame");
    private static DynamicTexture frame;
    private static int width,height;
    private PhoneBrowserTexture() {}
    public static void draw(PhoneGraphics g,int source,int x,int y,int w,int h) {
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int sourceW,sourceH;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,source);
            sourceW=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);
            sourceH=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
        } finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous); }
        if(sourceW<=0||sourceH<=0) return;
        if(frame==null||width!=sourceW||height!=sourceH) {
            if(frame!=null) Minecraft.getInstance().getTextureManager().release(ID);
            frame=new DynamicTexture("MCphone 浏览器",sourceW,sourceH,false);
            Minecraft.getInstance().getTextureManager().register(ID,frame);
            width=sourceW;height=sourceH;
        }
        if(!(frame.getTexture() instanceof GlTexture target)) return;
        GL43.glCopyImageSubData(source,GL11.GL_TEXTURE_2D,0,0,0,0,target.glId(),GL11.GL_TEXTURE_2D,0,0,0,0,sourceW,sourceH,1);
        // 与旧版相同的上下方向；提取器持有原版纹理视图而非裸 GL id。
        g.nativeGraphics().nextStratum();
        g.nativeGraphics().blit(ID,x,y,x+w,y+h,0,1,0,1);
    }
    public static void dispose() {
        if(frame!=null) Minecraft.getInstance().getTextureManager().release(ID);
        frame=null;width=height=0;
    }
}
