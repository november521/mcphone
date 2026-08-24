package com.november.mcphone.feature.notes.client;

import com.november.mcphone.feature.notes.Note;
import com.november.mcphone.feature.notes.NoteService;
import com.november.mcphone.feature.notes.net.DeleteNotePacket;
import com.november.mcphone.feature.notes.net.NotesClientCache;
import com.november.mcphone.feature.notes.net.RequestNotePacket;
import com.november.mcphone.feature.notes.net.SaveNotePacket;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 笔记编辑界面。
 *
 * 多行输入用原版 MultiLineEditBox
 *
 * 它和书与笔的编辑器共用底层的 MultilineTextField：换行、光标上下移动、
 * 拖动选中、滚动全都现成。自己撸一个多行输入框要把这些重写一遍，
 * 还容易在代理对上出错。
 *
 * 中文能不能打与原版聊天框完全一致，理由见 DeviceNameEditor 的类注释。
 * 同样地，本界面下所有按键都必须被 PhoneScreen 吞掉，否则打拼音按到 e
 * 就命中背包键，手机当场关掉。
 *
 * 全文是异步到的
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

    /**
     * 给输入框右侧的滚动条留出的宽度。
     *
     * 不留这块地方，滚动条会画到手机外面去
     *
     * 原版 AbstractScrollWidget.renderScrollBar 是这么画的：
     *
     *     int j = this.getX() + this.width;
     *     guiGraphics.blitSprite(SCROLLER_SPRITE, j, k, 8, i);
     *
     * 起点是控件右边缘，也就是画在控件【外面】，宽度写死 8。
     *
     * 手机屏幕宽 120，左右各留 4 的边距，输入框原来直接占满 112——滚动条于是
     * 落在 116～124，而屏幕右边界是 120，有 4 像素压在机身边框上。
     *
     * 为什么不把它改细
     *
     * 改不了。renderScrollBar 是 private，子类覆盖不到；那个 8 是方法里的字面量，
     * 不走 scrollbarWidth()（那个方法虽然是 public，但渲染压根没调它）。
     *
     * 要改只能把整个 MultiLineEditBox 重写一遍，而我们用它正是图它自带的换行、
     * 光标移动、拖选、滚动——为了一条滚动条的粗细把这些全接过来不划算。
     *
     * 所以只解决越界：输入框让出这 8 像素，滚动条正好落在右边距以内。
     */
    private static final int SCROLL_BAR_W = 8;

    private static int colorSave() { return FontPalette.confirm(); }
    private static int colorDelete() { return FontPalette.danger(); }
    private static int colorHover() { return FontPalette.title(); }
    private static int colorHint() { return FontPalette.subtle(); }

    /** 删除已上膛时的颜色 —— 与相册的确认态一致 */
    private static int colorArmed() { return FontPalette.armed(); }

    private MultiLineEditBox box;

    /** 正在编辑哪一条；{@link NoteService#NEW_NOTE_ID} 表示新建 */
    private int noteId = NoteService.NEW_NOTE_ID;

    /** 正文填进输入框了没。异步回包只认第一次，之后不再覆盖玩家的输入 */
    private boolean filled;

    private boolean saveHovered;
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

    //  生命周期

    /** 编辑已有的一条：先记下 id 再发请求，回包才不会被丢掉 */
    public void open(int id) {
        this.noteId = id;
        this.filled = false;
        this.deleteArmed = false;
        resetBox();

        NotesClientCache.openNote(id);
        PacketDistributor.sendToServer(new RequestNotePacket(id));
    }

    /** 新建一条：没有 id 可等，直接给一张白纸 */
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

    //  渲染

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, float partialTick, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        final int top = phoneTop + statusH + 4;
        final int buttonY = phoneTop + screenH - navH - BUTTON_ROW_H;
        final int boxH = buttonY - top - COUNTER_ROW_H;

        // 输入框比内容区窄一条滚动条：那东西画在控件外面，不让位就伸出屏幕。
        // 按钮行仍然用整个 w，它没有这个问题
        final int boxW = w - SCROLL_BAR_W;

        // 首次渲染才创建：这里才知道机身坐标。之后每帧同步位置，
        // 手机居中的位置会随窗口大小变化
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

    /**
     * 底部只有两个按钮：保存在左，删除在右。
     *
     * 打印【不】在这儿。它曾经摆在中间，而删除上膛之后文案从"删除"变成
     * "再点一次删除"，右对齐往左长出一大截，正好压住打印那两个字——112 像素宽
     * 的屏幕上，两者重叠 7 像素。
     *
     * 挤是表象，根子是打印本来就不属于这一页：它对笔记做的事跟"编辑"无关，
     * 放在列表页每条笔记的尾部更顺手，还顺带消掉了"新建未保存不能打印"那个
     * 置灰态——列表里的每一条本来就都保存过。
     */
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

        // 新建还没保存过的笔记没什么可删，删除键置灰
        boolean deletable = noteId != NoteService.NEW_NOTE_ID;
        // 上膛后用醒目的黄色：这一下点下去就真没了，颜色得先说一声
        int deleteColor = !deletable ? colorHint()
                : deleteArmed ? colorArmed()
                : deleteHovered ? colorHover() : colorDelete();
        g.drawString(font, delete, deleteX, y, deleteColor, false);
    }

    //  交互

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (saveHovered) { save(); return true; }
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
