package com.november.mcphone.platform.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.gui.screens.Screen;
import org.joml.Matrix4f;

/**
 * 直接走顶点缓冲的那点绘制，以及 {@link Screen} 上换过签名的那几个方法 ——
 * 全仓唯一碰它们的地方。
 *
 * <h2>为什么值得一层</h2>
 *
 * 1.21 把顶点缓冲那套 API 整个改了名：{@code getBuilder() + begin()} 合成一个
 * {@code begin()}，{@code vertex/uv/color/endVertex} 变成
 * {@code addVertex/setUv/setColor}（不再需要收尾那一下），{@code end()} 变成
 * {@code build()}。同一个四边形，两支写出来没有一行是一样的。
 *
 * <b>这是判据看不见的那一类里最典型的</b>：类名没变、方法名换了、调用形状也变了，
 * 扫 import 与扫类型名都发现不了，只有真编一遍才知道。
 *
 * <h2>这个包放什么</h2>
 *
 * {@code platform.client} 下装的是「各目标做同一件事、但写法不同」【且碰客户端类型】
 * 的东西。<b>包名里那个 client 是硬要求</b>：dist 隔离那道闸只准路径里带 /client/ 的类
 * 引用 {@code net.minecraft.client.*}，别处引用了就会在专用服务器上一加载就崩服 ——
 * 这个类第一版放在 platform/ 下，当场被那道闸拦下。
 *
 * {@code platform} 下装的是「各目标做同一件事、但写法不同」的东西。
 * <b>不要往这里放业务逻辑</b> —— 「浏览器把网页画在哪一块」是业务，属于那个界面；
 * 「这个版本怎么提交一个带贴图的四边形」才是这里的事。
 */
public final class Draw {

    private Draw() {}

    /**
     * 画一个铺满给定矩形的带贴图四边形，uv 走满 0..1，颜色纯白不透明。
     *
     * <p><b>v 轴是反的</b>：左下角取 v=1、左上角取 v=0。这不是笔误 ——
     * 送进来的画面（浏览器那一路是 Chromium 的输出）原点在左上，而 OpenGL 的
     * 纹理坐标原点在左下，不翻过来画出来是上下颠倒的。
     *
     * <p>调用方负责先绑好贴图与着色器 —— 这一层只管「怎么把四个顶点交出去」。
     */
    public static void texturedQuad(Matrix4f matrix, float x, float y, float w, float h) {
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.vertex(matrix, x, y + h, 0).uv(0f, 1f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, x + w, y + h, 0).uv(1f, 1f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, x + w, y, 0).uv(1f, 0f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, x, y, 0).uv(0f, 0f).color(255, 255, 255, 255).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }

    /**
     * 画屏幕背景。
     *
     * <p>1.21 的 {@code renderBackground} 多收鼠标位置与 partialTick（它要画模糊），
     * 1.20.1 只收 {@link GuiGraphics}。调用方一律把四个都传进来，用不上的这一支忽略。
     */
    public static void screenBackground(Screen screen, GuiGraphics g,
                                        int mouseX, int mouseY, float partialTick) {
        // 1.20.1 的 renderBackground 只收 GuiGraphics，后三个这一支用不上
        screen.renderBackground(g);
    }

    /**
     * 裁剪框有没有被人漏下来 —— <b>这一支问的是 GL，不是 {@link GuiGraphics}</b>。
     *
     * <p>1.21 那边用 {@code g.containsPointInScissor(0, 0)}，1.20.1 <b>没有这个方法</b>
     * （它是 1.20.2 才加的），{@code scissorStack} 又是私有的，问不到。所以这里直接问 GL：
     * 裁剪测试还开着，就说明栈里还压着别人的框 —— 手机这一层自己的裁剪全走 {@code GuiUtil}
     * 与 {@code PhoneCanvas.clipped}，两边都是成对的，画完这一页时不该还开着。
     *
     * <p>这么问反而<b>比那边更准</b>：那边是靠「(0,0) 在不在框里」推断的，万一漏下来的框
     * 恰好包含窗口左上角就发现不了，GL 的这一位不会漏判。
     *
     * <p>栈弹空之后原版 {@code disableScissor} 会走 {@code applyScissor(null)} →
     * {@code RenderSystem.disableScissor()}，GL 这一位跟着关掉，所以拿它当循环条件弹不穿，
     * 不会去撞 "Scissor stack underflow"。
     *
     * <p>参数 {@code g} 这一支用不上，留着是为了两支签名一致。
     */
    public static boolean scissorLeaked(GuiGraphics g) {
        return GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    }
}
