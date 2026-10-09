package com.november.mcphone.feature.chat.client;

import com.november.mcphone.feature.chat.client.contextmenu.ChatContextMenuView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 输入框的粘贴菜单；只交出文字，光标、选区、过滤和长度上限仍由 EditBox 处理。 */
final class ChatInputMenu {
    private final ChatContextMenuView view = new ChatContextMenuView();
    private final Supplier<String> clipboard;
    private final Consumer<String> paste;
    private final ChatContextMenuView.PanelPainter painter;
    private boolean pasteEnabled;
    ChatInputMenu(Supplier<String> clipboard,Consumer<String> paste,ChatContextMenuView.PanelPainter painter) {
        this.clipboard=clipboard; this.paste=paste; this.painter=painter;
    }
    boolean isOpen() { return view.isOpen(); }
    void open(double x,double y) { pasteEnabled=!clipboard.get().isEmpty(); view.open(x,y); }
    boolean dismiss() { return view.dismiss(); }
    boolean mouseClicked(double x,double y,int button) {
        if (!isOpen()) return false;
        if (button==1) { dismiss(); return false; }
        boolean chosen=button==0 && view.hitIndex(x,y)==0;
        dismiss();
        if (chosen) choosePaste();
        return true;
    }
    /** 打开时判断可用性，执行时读取当前剪贴板；外部编辑器更换内容后也粘贴最新值。 */
    void choosePaste() {
        if (!pasteEnabled) return;
        String text=clipboard.get();
        if (!text.isEmpty()) paste.accept(text);
    }
    void render(GuiGraphics g,Font font,int x,int y,int width,int height,int mx,int my) {
        if (!isOpen()) return;
        view.render(g,font,x,y,width,height,mx,my,
                List.of(new ChatContextMenuView.Entry("mcphone.chat.message_action.paste",ChatContextMenuView.Icon.PASTE,pasteEnabled)),painter);
    }
}
