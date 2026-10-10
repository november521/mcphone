package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.feature.chat.ChatImage;
import com.november.mcphone.feature.gallery.client.PhotoLibrary;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** 图片放大、动图绘制和保存；打开时由会话页阻断下层输入。 */
final class ChatImageViewer {
    private static final int HIT_PAD = 2;
    private UUID viewingImage;
    private boolean saveHovered;
    private int saveX, saveY;
    private static int colorEmpty() { return FontPalette.subtle(); }
    private static int colorStamp() { return FontPalette.timestamp(); }
    private static int colorSend() { return ChatUi.accent(); }
    private static int colorSendHover() { return FontPalette.current().darkText() ? 0xFF863A66 : 0xFFFFD5E9; }

    boolean isOpen() { return viewingImage != null; }
    void open(UUID image) { viewingImage = image; saveHovered = false; }
    boolean dismiss() {
        if (viewingImage == null) return false;
        viewingImage = null;
        saveHovered = false;
        return true;
    }
    boolean mouseClicked(int button) {
        if (!isOpen()) return false;
        if (button == 0 && saveHovered) saveViewingImage();
        else dismiss();
        return true;
    }

    void render(GuiGraphics g, Font font,
                int areaX, int areaY, int areaW, int areaH,
                int mouseX, int mouseY) {

        if (viewingImage == null) return;
        g.fill(areaX, areaY, areaX + areaW, areaY + areaH, PhoneTheme.COLOR_OVERLAY);

        // 图还没到时这一层没有任何可点的东西，悬停先归零，免得留着上一帧的
        saveHovered = false;

        var texture = ChatImageCache.get(viewingImage);
        if (texture == null) {
            String loading = Component.translatable("mcphone.chat.image_loading").getString();
            g.drawString(font, loading,
                    areaX + (areaW - font.width(loading)) / 2,
                    areaY + (areaH - font.lineHeight) / 2, colorEmpty(), false);
            return;
        }

        int hintH = font.lineHeight + 2;
        drawImage(g, texture, viewingImage, areaX + 2, areaY + 2 + font.lineHeight,
                areaW - 4, areaH - 6 - hintH - font.lineHeight);

        // 「保存」摆右上角，与相册单张查看里的删除同一个位置：那一角是这一层唯一的动作，
        // 而下面整片都是"点哪儿都关掉"，动作键不能混在里面
        String save = Component.translatable("mcphone.chat.image_save").getString();
        int saveW = font.width(save);
        saveX = areaX + areaW - saveW - 4;
        saveY = areaY + 2;
        saveHovered = GuiUtil.hit(mouseX, mouseY,
                saveX - HIT_PAD, saveY - HIT_PAD, saveW + HIT_PAD * 2, font.lineHeight + HIT_PAD * 2);
        g.drawString(font, save, saveX, saveY,
                saveHovered ? colorSendHover() : colorSend(), false);

        String hint = Component.translatable("mcphone.chat.image_close_hint").getString();
        g.drawString(font, hint, areaX + (areaW - font.width(hint)) / 2,
                areaY + areaH - hintH, colorStamp(), false);
    }

    private void saveViewingImage() {
        UUID image = viewingImage;
        if (image == null) return;

        byte[] png = ChatImageCache.bytes(image);
        if (png == null) {
            tell("mcphone.chat.image_not_loaded");
            return;
        }

        // 动图存下来的是所有帧拼成的雪碧图，原样进相册就是一张莫名其妙的九宫格；存第一帧
        int frames = ChatImageCache.frames(image);
        final boolean animated = frames > 1;

        Object connection = Minecraft.getInstance().getConnection();
        Util.backgroundExecutor().execute(() -> {
            byte[] saving = animated
                    ? ImageCodec.cropCell(png, ChatImage.cols(frames), ChatImage.rows(frames))
                    : png;
            String name = saving == null ? null : PhotoLibrary.save(saving, "mcphone-");
            Minecraft.getInstance().execute(() -> {
                if (connection != Minecraft.getInstance().getConnection()) return;
                if (name == null) tell("mcphone.chat.image_save_failed");
                else if (animated) tell("mcphone.chat.image_saved_frame", name);
                else tell("mcphone.chat.image_saved", name);
            });
        });
    }

    private static void tell(String key, Object... args) {
        var player = Minecraft.getInstance().player;
        if (player != null) com.november.mcphone.platform.PlayerAccess.message(player, Component.translatable(key, args), true);
    }

    static void drawImage(GuiGraphics g, ImageCodec.Texture sheet, UUID id,
                                  int x, int y, int boxW, int boxH) {
        int frames = ChatImageCache.frames(id);
        int cols = ChatImage.cols(frames);
        int fw = sheet.width() / cols;
        int fh = sheet.height() / ChatImage.rows(frames);

        // 帧数与贴图对不上（伪造的消息，或者贴图被缩过）就整张画：糊一点也好过一片空白
        if (frames <= 1 || fw <= 0 || fh <= 0) {
            GuiUtil.drawFitted(g, sheet, x, y, boxW, boxH);
            return;
        }

        int frameMs = ChatImageCache.frameMs(id);
        int index = frameMs <= 0 ? 0 : (int) ((System.currentTimeMillis() / frameMs) % frames);
        GuiUtil.drawFittedRegion(g, sheet, (index % cols) * fw, (index / cols) * fh, fw, fh,
                x, y, boxW, boxH);
    }
}
