package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.ServerConfig;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.chat.TextBody;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.Consumer;

/** 原版 EditBox 与附件菜单的输入组件；发送通过回调交给会话页，不持有 peer 或网络包。 */
final class ChatComposer {
    private static final int INPUT_H = ChatLayout.INPUT_HEIGHT;
    private static final int INPUT_TEXT_PAD = 3;
    private static final int ATTACH_BTN = 9;
    private static final int MENU_PAD = 3;
    private static final int MENU_ROW_EXTRA = 3;
    private static final String[] RESERVED_ATTACH_KEYS = {
            "mcphone.chat.layout.voice", "mcphone.chat.layout.file", "mcphone.chat.layout.location"};
    private static final int COLOR_INPUT_BG = PhoneTheme.COLOR_CHAT_INPUT_BG;
    private static int colorSend() { return ChatUi.accent(); }
    private static int colorSendHover() { return FontPalette.current().darkText() ? 0xFF863A66 : 0xFFFFD5E9; }
    private static int colorSendOff() { return FontPalette.muted(); }
    private static int cursorRoom(Font font) { return font.width("_"); }
    private final Consumer<String> onSend;
    private EditBox box;
    private boolean sendHovered, attachBtnHovered, stickerHovered, attachMenuOpen;
    private boolean inputFocused = true;
    private ChatAttachment attachHovered, pendingAttach;
    private int inputOriginX, contentLeft, contentWidth;
    private float inputOriginY;
    private ChatLayout.Rect inputBounds = new ChatLayout.Rect(0, 0, 0, 0);

    ChatComposer(Consumer<String> onSend) { this.onSend = onSend; }

    void reset(boolean focused) {
        inputFocused = focused;
        sendHovered = attachBtnHovered = stickerHovered = attachMenuOpen = false;
        attachHovered = pendingAttach = null;
        inputBounds = new ChatLayout.Rect(0, 0, 0, 0);
        if (box != null) {
            box.setValue("");
            box.setFocused(focused);
        }
    }

    ChatAttachment consumeAttachRequest() {
        var out = pendingAttach;
        pendingAttach = null;
        return out;
    }

    /** 菜单和按钮优先处理；普通点击交回会话页判断图片，再决定输入框焦点。 */
    boolean mouseClicked(int button) {
        if (attachMenuOpen) {
            if (button == 0 && attachHovered != null) pendingAttach = attachHovered;
            attachMenuOpen = false;
            attachHovered = null;
            return true;
        }
        if (button != 0) return false;
        if (attachBtnHovered) { attachMenuOpen = true; return true; }
        if (stickerHovered) { pendingAttach = ChatAttachment.STICKER; return true; }
        if (sendHovered) { send(); return true; }
        return false;
    }

    void focusInput(double mx, double my, int button) {
        if (box == null) return;
        boolean inInput = inputBounds.contains(mx, my);
        setFocused(inInput);
        if (inInput) box.mouseClicked((mx - inputOriginX) / ChatLayout.TEXT_SCALE,
                Mth.clamp((my - inputOriginY) / ChatLayout.TEXT_SCALE, 0, box.getHeight() - 1), button);
    }

    void blur() { setFocused(false); }
    private void setFocused(boolean focused) {
        inputFocused = focused;
        if (box != null) box.setFocused(focused);
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!inputFocused) return false;
        if (keyCode == 257 || keyCode == 335) { send(); return true; }
        return box != null && box.keyPressed(keyCode, scanCode, modifiers);
    }
    boolean charTyped(char c, int modifiers) { return inputFocused && box != null && box.charTyped(c, modifiers); }
    private void send() {
        if (box == null || !ChatLayout.hasText(box.getValue())) return;
        onSend.accept(box.getValue());
        box.setValue("");
    }

    void render(GuiGraphics g, Font font, int x, int y, int w,
                int mouseX, int mouseY, float partialTick) {

        contentLeft = x;
        contentWidth = w;
        String send = Component.translatable("mcphone.chat.send").getString();
        boolean hasText = box != null && ChatLayout.hasText(box.getValue());
        var layout = ChatLayout.composer(w, ChatUi.width(font, send, ChatLayout.SEND_SCALE), hasText);
        boolean canAttach = ServerConfig.allowChatImages();
        int barX = x + layout.inputX();
        int boxW = layout.inputWidth();
        float iconY = y + (INPUT_H - ATTACH_BTN) / 2f;
        // 语音仅留出固定槽位，现阶段没有录音或切换输入方式的动作。
        ChatUi.sizedIcon(g, ChatUi.Icon.VOICE, x, iconY, ATTACH_BTN, FontPalette.dim());
        stickerHovered = canAttach && !ChatImageSender.isFull() && ChatLayout.hit(mouseX, mouseY,
                x + layout.stickerX() - 1, y, ATTACH_BTN + 2, INPUT_H);
        ChatUi.sizedIcon(g, ChatUi.Icon.SMILE, x + layout.stickerX(), iconY, ATTACH_BTN,
                stickerHovered ? colorSendHover() : (canAttach ? colorSend() : colorSendOff()));
        attachBtnHovered = false;
        attachHovered = null;
        if (hasText || !canAttach || ChatImageSender.isFull()) attachMenuOpen = false;

        PhoneSkin.drawOrFill(g, PhoneSkin.Element.CHAT_INPUT_BAR,
                barX, y, boxW, INPUT_H, COLOR_INPUT_BG);

        // 无边框的 EditBox 不会自己垂直居中，手动摆到栏中间
        float textY = y + (INPUT_H - font.lineHeight * ChatLayout.TEXT_SCALE) / 2f;
        int textW = ChatLayout.unscaledWidth(boxW - INPUT_TEXT_PAD * 2, ChatLayout.TEXT_SCALE)
                - cursorRoom(font);
        inputOriginX = barX + INPUT_TEXT_PAD;
        inputOriginY = textY;
        inputBounds = new ChatLayout.Rect(barX, y, boxW, INPUT_H);

        if (box == null) {
            box = new EditBox(font, 0, 0, textW, font.lineHeight,
                    Component.translatable("mcphone.app.chat"));
            box.setMaxLength(TextBody.MAX_LENGTH);
            box.setBordered(false);
            box.setFocused(inputFocused);
        } else if (box.getWidth() != textW) {
            box.setWidth(textW);
            // 发送按钮出现时输入框会收窄，重新保证光标可见；不移动光标，也不清除选区。
            box.setCursorPosition(box.getCursorPosition());
        }
        int inputColor = PhoneSkin.textColor(PhoneSkin.Element.CHAT_INPUT_BAR, 0xFFE0E0E0);
        box.setTextColor(inputColor);
        box.setTextColorUneditable(inputColor);
        // 控件保持原字体坐标，渲染与鼠标同时反向换算。光标、选择高亮和长文本滚动仍由 EditBox 管。
        GuiUtil.enableScissor(g, barX, y, barX + boxW, y + INPUT_H);
        g.pose().pushPose();
        g.pose().translate(inputOriginX, inputOriginY, 0);
        g.pose().scale(ChatLayout.TEXT_SCALE, ChatLayout.TEXT_SCALE, 1f);
        box.render(g, (int) ((mouseX - inputOriginX) / ChatLayout.TEXT_SCALE),
                (int) ((mouseY - inputOriginY) / ChatLayout.TEXT_SCALE), partialTick);
        g.pose().popPose();
        GuiUtil.disableScissor(g);

        int actionX = x + layout.actionX();
        int sendY = y + (INPUT_H - ChatLayout.SEND_HEIGHT) / 2;
        sendHovered = hasText && ChatLayout.hit(mouseX, mouseY,
                actionX, sendY, layout.actionWidth(), ChatLayout.SEND_HEIGHT);
        if (hasText) {
            ChatUi.roundedButton(g, actionX, sendY, layout.actionWidth(), ChatLayout.SEND_HEIGHT,
                    sendHovered ? 0xFF913966 : 0xFFB65085);
            ChatUi.buttonText(g, font, send, actionX + layout.actionWidth() / 2f,
                    sendY + ChatLayout.SEND_HEIGHT / 2f,
                    ChatLayout.SEND_SCALE, 0xFFFFFFFF);
        } else {
            renderAttachButton(g, font, actionX + (layout.actionWidth() - ATTACH_BTN) / 2,
                    y, mouseX, mouseY, canAttach);
        }
    }

    private void renderAttachButton(GuiGraphics g, Font font, int x, int barY,
                                    int mouseX, int mouseY, boolean canAttach) {

        int by = barY + (INPUT_H - ATTACH_BTN) / 2;
        boolean sending = ChatImageSender.isBusy();
        boolean blocked = !canAttach || ChatImageSender.isFull();
        if (blocked) attachMenuOpen = false;

        attachBtnHovered = !blocked && ChatLayout.hit(mouseX, mouseY,
                x - 1, by - 1, ATTACH_BTN + 2, ATTACH_BTN + 2);

        if (attachBtnHovered || attachMenuOpen) {
            g.fill(x - 1, by - 1, x + ATTACH_BTN + 1, by + ATTACH_BTN + 1,
                    PhoneTheme.COLOR_HOVER_STRONG);
        }

        ChatUi.sizedIcon(g, ChatUi.Icon.PLUS, x, barY + (INPUT_H - ATTACH_BTN) / 2f, ATTACH_BTN,
                sending || blocked ? colorSendOff()
                        : (attachBtnHovered || attachMenuOpen ? colorSendHover() : colorSend()));

        if (attachMenuOpen) renderAttachMenu(g, font, x, by, mouseX, mouseY);
    }

    private void renderAttachMenu(GuiGraphics g, Font font, int btnX, int btnY,
                                  int mouseX, int mouseY) {

        ChatAttachment[] items = ChatAttachment.values();
        float scale = ChatLayout.TEXT_SCALE;
        int rowH = ChatUi.lineHeight(font, scale) + MENU_ROW_EXTRA;

        int width = 0;
        for (ChatAttachment item : items) width = Math.max(width, ChatUi.width(font, item.label(), scale));
        for (String key : RESERVED_ATTACH_KEYS) width = Math.max(width,
                ChatUi.width(font, Component.translatable(key).getString(), scale));
        width += MENU_PAD * 2;
        width = Math.min(width, contentWidth);

        int height = (items.length + RESERVED_ATTACH_KEYS.length) * rowH + MENU_PAD * 2;
        int menuX = Math.max(contentLeft, btnX + ATTACH_BTN - width);
        int menuY = btnY - height - 2;

        g.fill(menuX, menuY, menuX + width, menuY + height, PhoneTheme.COLOR_OVERLAY);
        g.renderOutline(menuX, menuY, width, height, PhoneTheme.COLOR_DIVIDER);

        attachHovered = null;
        int rowY = menuY + MENU_PAD;
        for (ChatAttachment item : items) {
            boolean hovered = GuiUtil.hit(mouseX, mouseY, menuX, rowY, width, rowH - 1);
            if (hovered) {
                attachHovered = item;
                g.fill(menuX + 1, rowY, menuX + width - 1, rowY + rowH - 1,
                        PhoneTheme.COLOR_ROW_HOVER);
            }
            ChatUi.text(g, font, ChatUi.truncate(font, item.label(), width - MENU_PAD * 2, scale), menuX + MENU_PAD,
                    rowY + (rowH - ChatUi.lineHeight(font, scale)) / 2, scale,
                    hovered ? FontPalette.title() : FontPalette.body());
            rowY += rowH;
        }
        // 灰色项只是为后续布局占位，不加入 ChatAttachment 枚举，也不发送请求。
        for (String key : RESERVED_ATTACH_KEYS) {
            ChatUi.text(g, font, ChatUi.truncate(font, Component.translatable(key).getString(),
                    width - MENU_PAD * 2, scale), menuX + MENU_PAD,
                    rowY + (rowH - ChatUi.lineHeight(font, scale)) / 2, scale, FontPalette.dim());
            rowY += rowH;
        }
    }
}
