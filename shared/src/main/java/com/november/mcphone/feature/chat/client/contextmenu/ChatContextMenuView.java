package com.november.mcphone.feature.chat.client.contextmenu;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.List;

/** 消息和输入框共用的菜单视图；只负责显示与命中，不持有消息、EditBox、剪贴板或网络。 */
public final class ChatContextMenuView {
    public enum Icon { COPY, DELETE, PASTE }
    public record Entry(String labelKey, Icon icon, boolean enabled) {}
    @FunctionalInterface public interface PanelPainter { void paint(GuiGraphics g, int x, int y, int width, int height); }
    private static final float SCALE = .75f;
    private boolean open;
    private double anchorX, anchorY;
    private ChatContextMenuLayout.Layout layout;
    private List<Entry> entries = List.of();

    public boolean isOpen() { return open; }
    public void open(double x, double y) { open = true; anchorX = x; anchorY = y; layout = null; entries = List.of(); }
    public boolean dismiss() { boolean wasOpen = open; open = false; layout = null; entries = List.of(); return wasOpen; }
    public int hitIndex(double x, double y) {
        int row = !open || layout == null ? -1 : layout.rowAt(x, y);
        return row < 0 || !entries.get(row).enabled() ? -1 : row;
    }
    void arrange(ChatContextMenuLayout.Bounds viewport, int labelWidth, int textHeight, List<Entry> entries) {
        this.entries = List.copyOf(entries);
        layout = ChatContextMenuLayout.place(anchorX, anchorY, viewport, labelWidth, textHeight, entries.size());
    }
    public void render(GuiGraphics g, Font font, int x, int y, int width, int height, int mx, int my,
                       List<Entry> entries, PanelPainter painter) {
        if (!open || entries.isEmpty()) return;
        int labelWidth = 0;
        for (var entry : entries) labelWidth = Math.max(labelWidth,
                (int) Math.ceil(font.width(Component.translatable(entry.labelKey())) * SCALE));
        arrange(new ChatContextMenuLayout.Bounds(x,y,width,height), labelWidth,
                (int) Math.ceil(font.lineHeight * SCALE), entries);
        var bounds = layout.bounds();
        painter.paint(g, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        float inkHeight = Math.max(1, font.lineHeight - 2) * SCALE;
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            int rowY = bounds.y() + ChatContextMenuLayout.PAD + i * layout.rowHeight();
            if (hitIndex(mx,my) == i) g.fill(bounds.x()+2,rowY,bounds.x()+bounds.width()-2,rowY+layout.rowHeight(),0x337C3559);
            int color = !entry.enabled() ? FontPalette.subtle()
                    : entry.icon() == Icon.DELETE ? FontPalette.danger() : FontPalette.body();
            float centerY = rowY + layout.rowHeight() / 2f;
            ChatContextMenuIcon.draw(g,entry.icon(),layout.iconX(),centerY,color);
            int available = bounds.x()+bounds.width()-ChatContextMenuLayout.PAD-layout.textX();
            String label = GuiUtil.truncate(font,Component.translatable(entry.labelKey()).getString(),
                    Math.max(0,(int)(available/SCALE)));
            g.pose().pushPose();
            try {
                // 与图标共用行中心；字体底部两像素空白不参与视觉居中。
                g.pose().translate(layout.textX(),centerY-inkHeight/2f,0);
                g.pose().scale(SCALE,SCALE,1);
                g.drawString(font,label,0,0,color,false);
            } finally { g.pose().popPose(); }
        }
    }
}
