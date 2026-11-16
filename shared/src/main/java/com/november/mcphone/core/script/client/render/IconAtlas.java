package com.november.mcphone.core.script.client.render;

import com.november.mcphone.platform.ResourceIds;

import com.mojang.blaze3d.systems.RenderSystem;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * icon 组件的 12 个系统图标（施工方案 §5.2）。一张白色横条图集，画的时候按颜色染色；资源包覆盖同路径即可换肤。
 * 图集由 docs/make_script_icons.py 按 {@link #NAMES} 的顺序生成。
 */
public final class IconAtlas {

    /** 图集里从左到右的顺序。改了要重跑 docs/make_script_icons.py，否则名字和图对不上。 */
    public static final List<String> NAMES = List.of(
            "back", "forward", "up", "down", "check", "cross",
            "plus", "minus", "gear", "search", "info", "warn");

    private static final int CELL = 16;

    /** 第一次画时才建：docs/ 的断言测试要读 NAMES，那里没有 Minecraft 的运行环境。 */
    private static ResourceLocation texture;

    private IconAtlas() {
    }

    /** 在 (x, y) 画一个 size×size 的图标，颜色是 ARGB。不认识的名字画 info。 */
    public static void draw(GuiGraphics g, String name, int x, int y, int size, int argb) {
        if (size <= 0) return;
        if (texture == null) {
            texture = ResourceIds.create(MCphone.MODID, "textures/script/icons.png");
        }
        int index = NAMES.indexOf(name);
        if (index < 0) index = NAMES.indexOf("info");
        RenderSystem.setShaderColor((argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f,
                (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
        // 着色器颜色是全局状态：不复位的话此后画的一切都被染成这个颜色，关掉这个 App 也还在。别拆掉 finally
        try {
            GuiUtil.drawTexture(g, texture, x, y, size, size, index * CELL, 0, CELL, CELL, CELL * NAMES.size(), CELL);
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
    }
}
