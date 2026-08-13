package com.november.mcphone.gui;

import com.november.mcphone.network.notes.DeleteNotePacket;
import com.november.mcphone.network.notes.NotesClientCache;
import com.november.mcphone.network.notes.PrintNotePacket;
import com.november.mcphone.network.notes.RequestNotePacket;
import com.november.mcphone.network.notes.SaveNotePacket;
import com.november.mcphone.notes.Note;
import com.november.mcphone.notes.NoteService;
import com.november.mcphone.notes.NotePrinter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 笔记编辑界面。
 *
 * ============================================================
 * 多行输入用原版 MultiLineEditBox
 * ============================================================
 *
 * 它和书与笔的编辑器共用底层的 MultilineTextField：换行、光标上下移动、
 * 拖动选中、滚动全都现成。自己撸一个多行输入框要把这些重写一遍，
 * 还容易在代理对上出错。
 *
 * 中文能不能打与原版聊天框完全一致，理由见 DeviceNameEditor 的类注释。
 * 同样地，本界面下所有按键都必须被 PhoneScreen 吞掉，否则打拼音按到 e
 * 就命中背包键，手机当场关掉。
 *
 * ============================================================
 * 全文是异步到的
 * ============================================================
 *
 * 点进一条笔记时只知道 id，正文要等服务端回包。所以每帧都看一眼缓存里
 * 到了没有，到了才填进输入框——填过一次就不再填，否则玩家打的字会被
 * 迟到的回包一遍遍冲掉。
 */
public final class NoteEditor {

    private static final int PAD = 4;

    /** 底部按钮行的高度 */
    private static final int BUTTON_ROW_H = 12;

    /**
     * 输入框下方要留出的空当。
     *
     * MultiLineEditBox 自己会在 getY()+height+4 处右对齐画一行"已用/上限"
     * 的字数，那是原版 renderDecorations 干的、关不掉。不留这块地方的话，
     * 它会正好压在删除按钮上。4 是它的偏移，9 是一行字高。
     */
    private static final int COUNTER_ROW_H = 13;

    private static final int COLOR_SAVE = 0xFF66FF88;
    private static final int COLOR_PRINT = 0xFF88CCFF;
    private static final int COLOR_DELETE = 0xFFFF8888;
    private static final int COLOR_HOVER = 0xFFFFFFFF;
    private static final int COLOR_HINT = 0xFF888888;

    /** 删除已上膛时的颜色 —— 与相册的确认态一致 */
    private static final int COLOR_ARMED = 0xFFFFDD44;

    /** 临时提示的颜色 */
    private static final int COLOR_TOAST = 0xFFFFDD44;

    private MultiLineEditBox box;

    /** 正在编辑哪一条；{@link NoteService#NEW_NOTE_ID} 表示新建 */
    private int noteId = NoteService.NEW_NOTE_ID;

    /** 正文填进输入框了没。异步回包只认第一次，之后不再覆盖玩家的输入 */
    private boolean filled;

    private boolean saveHovered;
    private boolean printHovered;
    private boolean deleteHovered;

    /**
     * 删除是否已"上膛"。
     *
     * 第一次点把按钮变成"再点一次"，第二次才真删。笔记不像照片那样还能
     * 去文件夹里找回来——删了就是没了，一点就消失太容易误触。
     * 相册的删除是同一套做法。
     *
     * 任何其他操作都会卸掉：点了保存、点进输入框、离开界面。
     */
    private boolean deleteArmed;

    /** 保存或删除后置位，PhoneScreen 取走后退回列表 */
    private boolean backRequested;

    /**
     * 手机屏幕里的一行临时提示。
     *
     * 打印的结果本来是服务端用动作栏说的，但动作栏在游戏 HUD 上、手机
     * 机身之外——玩家正盯着手机屏幕，得先关掉手机才看得见那句话，等于
     * 白说。所以结果也在这块小屏幕里说一遍。
     */
    private String toast = "";
    private long toastUntilMs;

    /** 提示停留时长。够读完一行短句，又不至于赖着不走 */
    private static final long TOAST_MS = 2500L;

    // ============================================================
    //  生命周期
    // ============================================================

    /** 编辑已有的一条：先记下 id 再发请求，回包才不会被丢掉 */
    public void open(int id) {
        this.noteId = id;
        this.filled = false;
        this.deleteArmed = false;
        this.toast = "";
        resetBox();

        NotesClientCache.openNote(id);
        PacketDistributor.sendToServer(new RequestNotePacket(id));
    }

    /** 新建一条：没有 id 可等，直接给一张白纸 */
    public void openNew() {
        this.noteId = NoteService.NEW_NOTE_ID;
        this.filled = true;   // 白纸本身就是"填好了"，别再等回包
        this.deleteArmed = false;
        this.toast = "";
        resetBox();

        NotesClientCache.openNewNote();
    }

    public void close() {
        saveHovered = false;
        printHovered = false;
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

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, float partialTick, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int top = phoneTop + statusH + 4;
        final int buttonY = phoneTop + screenH - navH - BUTTON_ROW_H;
        final int boxH = buttonY - top - COUNTER_ROW_H;

        // 首次渲染才创建：这里才知道机身坐标。之后每帧同步位置，
        // 手机居中的位置会随窗口大小变化
        if (box == null) {
            box = new MultiLineEditBox(font, x, top, w, boxH,
                    Component.translatable("mcphone.notes.placeholder"),
                    Component.translatable("mcphone.app.notes"));
            box.setCharacterLimit(Note.MAX_BODY_LENGTH);
            box.setFocused(true);
        } else {
            box.setX(x);
            box.setY(top);
            box.setWidth(w);
            box.setHeight(boxH);
        }

        fillFromCacheOnce();
        box.render(g, mouseX, mouseY, partialTick);

        renderButtons(g, font, x, buttonY, w, mouseX, mouseY);

        // 画在字数那一行的位置：那儿本来就是空的，且紧挨按钮，视线不用挪
        renderToast(g, font, x, buttonY - font.lineHeight - 1, w);
    }

    /**
     * 全文到了就填进去，只填一次。
     *
     * 不判 filled 的话，缓存每帧都在那儿，玩家每打一个字都会被这份旧正文
     * 覆盖掉——表现为"输入框根本打不进字"，而且极难看出原因。
     */
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

        String print = Component.translatable("mcphone.notes.print").getString();

        int saveW = font.width(save);
        int printW = font.width(print);
        int deleteW = font.width(delete);
        int deleteX = x + w - deleteW;
        int printX = x + (w - printW) / 2;   // 摆中间，与左右两个按钮都拉开距离

        boolean inRow = mouseY >= y - 2 && mouseY < y + font.lineHeight + 2;
        saveHovered = inRow && mouseX >= x - 2 && mouseX < x + saveW + 2;
        printHovered = inRow && mouseX >= printX - 2 && mouseX < printX + printW + 2;
        deleteHovered = inRow && mouseX >= deleteX - 2 && mouseX < deleteX + deleteW + 2;

        g.drawString(font, save, x, y, saveHovered ? COLOR_HOVER : COLOR_SAVE, false);

        // 没保存过的新笔记印不出东西来，与删除一样置灰
        boolean printable = noteId != NoteService.NEW_NOTE_ID;
        g.drawString(font, print, printX, y,
                !printable ? COLOR_HINT : (printHovered ? COLOR_HOVER : COLOR_PRINT), false);

        // 新建还没保存过的笔记没什么可删，删除键置灰
        boolean deletable = noteId != NoteService.NEW_NOTE_ID;
        // 上膛后用醒目的黄色：这一下点下去就真没了，颜色得先说一声
        int deleteColor = !deletable ? COLOR_HINT
                : deleteArmed ? COLOR_ARMED
                : deleteHovered ? COLOR_HOVER : COLOR_DELETE;
        g.drawString(font, delete, deleteX, y, deleteColor, false);
    }

    // ============================================================
    //  交互
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (saveHovered) { save(); return true; }
            if (printHovered && noteId != NoteService.NEW_NOTE_ID) { print(); return true; }
            if (deleteHovered && noteId != NoteService.NEW_NOTE_ID) { delete(); return true; }
        }
        // 点到别处就卸膛：玩家已经去干别的事了，那一下删除多半是误触
        deleteArmed = false;
        return box != null && box.mouseClicked(mx, my, button);
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return box != null && box.mouseDragged(mx, my, button, dx, dy);
    }

    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        return box != null && box.mouseScrolled(mx, my, scrollX, scrollY);
    }

    /**
     * @return true 表示按键已被消费。
     *         调用方无论如何都该吃掉按键，别让 e 漏到背包键那边去
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 回车在多行输入里是换行，不能拿来当保存——保存走底部那个按钮
        return box != null && box.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char c, int modifiers) {
        return box != null && box.charTyped(c, modifiers);
    }

    /**
     * 保存并退回列表。
     *
     * 只发包、不改本地缓存：服务端保存后会回发新的列表，界面以那份为准。
     * 本地抢先改的话，一旦服务端因为条数满了而拒绝，界面上就会留着一条
     * 并不存在的笔记。
     *
     * 正文清空后保存等于删除，那条规则在服务端，见 NoteService.saveNote。
     */
    private void save() {
        if (box == null) return;
        deleteArmed = false;
        PacketDistributor.sendToServer(new SaveNotePacket(noteId, box.getValue()));
        backRequested = true;
    }

    /**
     * 印成一本书。
     *
     * 只发 id，正文以服务端存的那份为准。留在编辑界面不退回列表：打印
     * 不改变笔记本身，把人踢回列表反而像是出了什么事。
     *
     * 成没成由服务端用动作栏回话——它才知道玩家有没有那本空白的书。
     */
    private void print() {
        deleteArmed = false;

        // 背包在客户端是齐全的，够不够这里就能算准——不必先发一趟包，
        // 等服务端拒绝了再回话。缺书是最常见的失败，就地说清楚
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !NotePrinter.COST.canAfford(mc.player)) {
            showToast("mcphone.notes.print_failed");
            return;
        }

        PacketDistributor.sendToServer(new PrintNotePacket(noteId));
        showToast("mcphone.notes.print_done");
    }

    private void showToast(String translationKey) {
        toast = Component.translatable(translationKey).getString();
        toastUntilMs = System.currentTimeMillis() + TOAST_MS;
    }

    /** 提示画在按钮行正上方，到点自动消失 */
    private void renderToast(GuiGraphics g, Font font, int x, int y, int w) {
        if (toast.isEmpty() || System.currentTimeMillis() > toastUntilMs) return;

        int tw = font.width(toast);
        g.drawString(font, toast, x + (w - tw) / 2, y, COLOR_TOAST, false);
    }

    /** 第一次点上膛，第二次才真删 —— 理由见 deleteArmed 的注释 */
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
