package com.november.mcphone.feature.chat.client.messageaction;

import com.november.mcphone.core.client.FontPalette;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.List;

/** 菜单展示与命中；只返回操作意图，不碰剪贴板、网络或存档。 */
final class ChatContextMenu {
    static final float SCALE = .75f;
    private ChatMessageTarget target;
    private List<ChatMessageAction> items = List.of();
    private double anchorX, anchorY;
    private ChatContextMenuLayout.Layout layout;
    boolean isOpen() { return target != null; }
    ChatMessageTarget target() { return target; }
    void open(ChatMessageTarget target, double x, double y) {
        this.target = target;
        items = target.copyable() ? List.of(ChatMessageAction.COPY, ChatMessageAction.DELETE) : List.of(ChatMessageAction.DELETE);
        anchorX = x; anchorY = y; layout = null;
    }
    boolean dismiss() { boolean wasOpen = isOpen(); target = null; items = List.of(); layout = null; return wasOpen; }
    ChatMessageAction actionAt(double x, double y) {
        int row = layout == null ? -1 : layout.rowAt(x, y);
        return row < 0 ? null : items.get(row);
    }
    void arrange(ChatContextMenuLayout.Bounds viewport, int labelWidth, int textHeight) {
        layout = ChatContextMenuLayout.place(anchorX, anchorY, viewport, labelWidth, textHeight, items.size());
    }
    private String label(ChatMessageAction action, boolean pending) {
        String key = switch (action) {
            case COPY -> target.selectedText().isEmpty() ? "copy_message" : "copy_selection";
            case DELETE -> pending ? "deleting" : "delete_message";
        };
        return Component.translatable("mcphone.chat.message_action." + key).getString();
    }
    void render(GuiGraphics g, Font font, ChatContextMenuLayout.Bounds viewport, int mx, int my,
                boolean pending, ChatMessageActions.PanelPainter painter) {
        if (!isOpen()) return;
        int labelWidth = 0;
        for (var item : items) labelWidth = Math.max(labelWidth, (int) Math.ceil(font.width(label(item, pending)) * SCALE));
        int textHeight = (int) Math.ceil(font.lineHeight * SCALE);
        arrange(viewport, labelWidth, textHeight);
        var bounds = layout.bounds();
        painter.paint(g, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            int y = bounds.y() + ChatContextMenuLayout.PAD + i * layout.rowHeight();
            boolean disabled = pending && item == ChatMessageAction.DELETE;
            boolean hovered = !disabled && layout.rowAt(mx, my) == i;
            if (hovered) g.fill(bounds.x() + 2, y, bounds.x() + bounds.width() - 2, y + layout.rowHeight(), 0x337C3559);
            int color = disabled ? FontPalette.muted() : item == ChatMessageAction.DELETE ? FontPalette.danger() : FontPalette.body();
            String text = com.november.mcphone.core.client.GuiUtil.truncate(font, label(item, pending),
                    Math.max(0, (int) ((bounds.width() - ChatContextMenuLayout.PAD * 2 - 4) / SCALE)));
            g.pose().pushPose();
            try {
                g.pose().translate(bounds.x() + ChatContextMenuLayout.PAD + 2, y + (layout.rowHeight() - font.lineHeight * SCALE) / 2f, 0);
                g.pose().scale(SCALE, SCALE, 1);
                g.drawString(font, text, 0, 0, color, false);
            } finally { g.pose().popPose(); }
        }
    }
}
