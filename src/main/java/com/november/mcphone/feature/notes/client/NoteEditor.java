package com.november.mcphone.feature.notes.client;

import com.november.mcphone.feature.notes.Note;
import com.november.mcphone.feature.notes.NoteService;
import com.november.mcphone.feature.notes.net.DeleteNotePacket;
import com.november.mcphone.feature.notes.net.NotesClientCache;
import com.november.mcphone.feature.notes.net.RequestNotePacket;
import com.november.mcphone.feature.notes.net.SaveNotePacket;
import com.november.mcphone.core.client.FontPalette;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 笔记编辑界面，多行输入用原版 MultiLineEditBox。本界面下所有按键都必须被
 * PhoneScreen 吞掉，否则打拼音按到 e 会命中背包键。正文异步到达，只填一次。
 */
public final class NoteEditor {

    private static final int PAD = 4;

    private static final int BUTTON_ROW_H = 12;

    /** MultiLineEditBox 会在 getY()+height+4 处画一行字数统计（关不掉），得给它留地方 */
    private static final int COUNTER_ROW_H = 13;

    /** 原版滚动条画在控件右边缘外侧、宽度写死 8（private 方法，改不了），输入框得让出这块 */
    private static final int SCROLL_BAR_W = 8;

    private static int colorSave() { return FontPalette.confirm(); }
    private static int colorDelete() { return FontPalette.danger(); }
    private static int colorHover() { return FontPalette.title(); }
    private static int colorHint() { return FontPalette.subtle(); }

    private static int colorArmed() { return FontPalette.armed(); }

    private MultiLineEditBox box;

    /** 正在编辑哪一条；{@link NoteService#NEW_NOTE_ID} 表示新建 */
    private int noteId = NoteService.NEW_NOTE_ID;

    /** 异步回包只填第一次，之后不再覆盖玩家的输入 */
    private boolean filled;

    private boolean saveHovered;
    private boolean deleteHovered;

    /** 删除已"上膛"：第一次点上膛，第二次才真删；任何其他操作都会卸掉 */
    private boolean deleteArmed;

    /** 保存或删除后置位，PhoneScreen 取走后退回列表 */
    private boolean backRequested;

    /** 编辑已有的一条：先记下 id 再发请求，回包才不会被丢掉 */
    public void open(int id) {
        this.noteId = id;
        this.filled = false;
        this.deleteArmed = false;
        resetBox();

        NotesClientCache.openNote(id);
        PacketDistributor.sendToServer(new RequestNotePacket(id));
    }

    public void openNew() {
        this.noteId = NoteService.NEW_NOTE_ID;
        this.filled = true;   // 白纸本身就是"填好了"，别再等回包
        this.deleteArmed = false;
        resetBox();

        NotesClientCache.openNewNote();
    }

    public void close() {
        saveHovered = false;
        deleteHovered = false;
        deleteArmed = false;
        backRequested = false;
        if (box != null) box.setFocused(false);
        NotesClientCache.closeNote();
    }

    public boolean consumeBackRequest() {
        if (!backRequested) return false;
        backRequested = false;
        return true;
    }

    private void resetBox() {
        if (box != null) {
            box.setValue("");
            box.setFocused(true);
        }
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, float partialTick, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int top = phoneTop + statusH + 4;
        final int buttonY = phoneTop + screenH - navH - BUTTON_ROW_H;
        final int boxH = buttonY - top - COUNTER_ROW_H;

        final int boxW = w - SCROLL_BAR_W;

        // 首次渲染才创建，之后每帧同步位置：手机居中的位置随窗口大小变化
        if (box == null) {
            box = new MultiLineEditBox(font, x, top, boxW, boxH,
                    Component.translatable("mcphone.notes.placeholder"),
                    Component.translatable("mcphone.app.notes"));
            box.setCharacterLimit(Note.MAX_BODY_LENGTH);
            box.setFocused(true);
        } else {
            box.setX(x);
            box.setY(top);
            box.setWidth(boxW);
            box.setHeight(boxH);
        }

        fillFromCacheOnce();
        box.render(g, mouseX, mouseY, partialTick);

        renderButtons(g, font, x, buttonY, w, mouseX, mouseY);
    }

    /** 只填一次：不判 filled 的话玩家打的每个字都会被缓存里的旧正文覆盖 */
    private void fillFromCacheOnce() {
        if (filled) return;

        Note note = NotesClientCache.getOpenNote();
        if (note == null) return;

        box.setValue(note.body());
        filled = true;
    }

    private void renderButtons(GuiGraphics g, Font font, int x, int y, int w,
                               int mouseX, int mouseY) {
        String save = Component.translatable("mcphone.settings.save").getString();
        String delete = Component.translatable(
                deleteArmed ? "mcphone.notes.delete_confirm" : "mcphone.notes.delete").getString();

        int saveW = font.width(save);
        int deleteW = font.width(delete);
        int deleteX = x + w - deleteW;

        boolean inRow = mouseY >= y - 2 && mouseY < y + font.lineHeight + 2;
        saveHovered = inRow && mouseX >= x - 2 && mouseX < x + saveW + 2;
        deleteHovered = inRow && mouseX >= deleteX - 2 && mouseX < deleteX + deleteW + 2;

        g.drawString(font, save, x, y, saveHovered ? colorHover() : colorSave(), false);

        boolean deletable = noteId != NoteService.NEW_NOTE_ID;
        int deleteColor = !deletable ? colorHint()
                : deleteArmed ? colorArmed()
                : deleteHovered ? colorHover() : colorDelete();
        g.drawString(font, delete, deleteX, y, deleteColor, false);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (saveHovered) { save(); return true; }
            if (deleteHovered && noteId != NoteService.NEW_NOTE_ID) { delete(); return true; }
        }
        deleteArmed = false;
        return box != null && box.mouseClicked(mx, my, button);
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return box != null && box.mouseDragged(mx, my, button, dx, dy);
    }

    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        return box != null && box.mouseScrolled(mx, my, scrollX, scrollY);
    }

    /** 调用方无论返回值如何都该吃掉按键，别让 e 漏到背包键 */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 回车在多行输入里是换行，不拿来当保存
        return box != null && box.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char c, int modifiers) {
        return box != null && box.charTyped(c, modifiers);
    }

    /** 只发包、不改本地缓存：以服务端回发的列表为准（满了会拒绝） */
    private void save() {
        if (box == null) return;
        deleteArmed = false;
        PacketDistributor.sendToServer(new SaveNotePacket(noteId, box.getValue()));
        backRequested = true;
    }

    private void delete() {
        if (!deleteArmed) {
            deleteArmed = true;
            return;
        }
        deleteArmed = false;
        PacketDistributor.sendToServer(new DeleteNotePacket(noteId));
        backRequested = true;
    }
}
