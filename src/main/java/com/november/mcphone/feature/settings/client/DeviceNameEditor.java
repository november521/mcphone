package com.november.mcphone.feature.settings.client;

import com.november.mcphone.core.ModDataComponents;
import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.feature.settings.net.SetDeviceNamePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.core.client.PhoneTheme;

/**
 * 设备名称编辑界面 —— 由 PhoneScreen 嵌入渲染。
 *
 * ================================================================
 * 为什么用原版 EditBox 而不是自己撸一个输入框
 * ================================================================
 *
 * 粘贴、光标移动、选中、退格这些 EditBox 全都现成，自己写要重来一遍，
 * 还容易在代理对（表情符号）上出错。
 *
 * 原版按 T 弹出的聊天框用的就是这个类（见 ChatScreen 的 input 字段），
 * 中文走的也是同一条路，没有第二条通道：
 *
 *   系统输入法提交 → GLFW 字符回调 → KeyboardHandler.charTyped
 *     → Screen.charTyped → EditBox.charTyped
 *
 * 中途只有 StringUtil.isAllowedChatCharacter 一道过滤，它整个实现就是
 * "不是 §、不是控制字符、不是 DEL"，汉字一律放行；超出基本平面的字符
 * （表情）由 KeyboardHandler 拆成代理对逐个送来，拼回去仍然正确。
 * 唯一的门槛是 EditBox.canConsumeInput 要求的焦点，下面已经设过。
 *
 * 所以这里能不能打中文，与原版聊天框完全一致——不存在"手机界面不支持
 * 输入法"这回事，先前那条注释是错的。
 *
 * 真正碍事的是另外两件，都不在本类：
 *   看不见候选框 —— LWJGL 带的 GLFW（3.3.3）没有 preedit 定位接口，
 *     Minecraft 从不告诉系统候选窗该画在哪，等于盲打。原版聊天框同病，
 *     只是它贴在屏幕底部，系统候选框默认也弹在那附近，才显得正常。
 *   按键被界面吞掉 —— 打拼音必然按到 e，而背包键默认就是 e。
 *     见 PhoneScreen.keyPressed 里对本界面的整段吞键处理。
 *
 * 但 EditBox 不作为 widget 加进 Screen：PhoneScreen.render 没有调
 * super.render()，加进去根本不会被画出来。这里手动调它的 render
 * 与按键转发，好处是它跟着手机开场动画一起缩放，位置也始终跟着机身走。
 *
 * ================================================================
 * 名字存在哪
 * ================================================================
 *
 * 存在【正打开的那一部】手机的数据组件里，见
 * {@link ModDataComponents#DEVICE_NAME}。哪一部由 {@link PhoneLocation}
 * 指明——玩家身上可能不止一部手机，手上的、背包里的、挂在饰品槽的，
 * 改错了就改到别人头上。
 *
 * 客户端只负责显示与发包，真正写入由服务端完成（会再校验一次），
 * 写完同步回来，物品栏里的名字随之更新。
 */
public final class DeviceNameEditor {

    private static final int PAD = 8;
    private static final int BOX_HEIGHT = 14;

    private static final int COLOR_HINT   = 0xFF888888;
    private static final int COLOR_BTN    = 0xFFCCCCCC;
    private static final int COLOR_BTN_ON = 0xFF88CCFF;

    /** 正在改名的是身上哪一部手机 */
    private PhoneLocation location = new PhoneLocation.InHand(InteractionHand.MAIN_HAND);

    private EditBox box;

    /** 本帧鼠标悬停的按钮 */
    private enum Btn { NONE, SAVE, CANCEL }
    private Btn hovered = Btn.NONE;

    // ============================================================
    //  进入 / 离开
    // ============================================================

    /** 进入编辑界面：把当前设备名填进输入框 */
    public void open(PhoneLocation location) {
        this.location = location;
        this.hovered = Btn.NONE;
        if (box != null) {
            box.setValue(currentName());
            box.moveCursorToEnd(false);
            box.setFocused(true);
        }
        // box 尚未创建时不做事：它在首次 render 里按机身坐标创建，
        // 那时会自动填入当前名字
    }

    public void close() {
        if (box != null) box.setFocused(false);
    }

    /** 读取正在改的那一部手机的当前设备名，没起过名则为空串 */
    private String currentName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "";
        ItemStack stack = location.resolve(mc.player);
        String name = stack.get(ModDataComponents.DEVICE_NAME.get());
        return name == null ? "" : name;
    }

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, float partialTick, Font font) {

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;

        // ---- 标题 ----
        g.drawString(font, Component.translatable("mcphone.settings.device_name").getString(),
                x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        y += 6;

        // ---- 输入框 ----
        // 首次渲染时才创建：这里才知道机身坐标。
        // 之后每帧同步位置，手机居中位置会随窗口大小变化
        if (box == null) {
            box = new EditBox(font, x, y, w, BOX_HEIGHT,
                    Component.translatable("mcphone.settings.device_name"));
            box.setMaxLength(SetDeviceNamePacket.MAX_NAME_LENGTH);
            box.setValue(currentName());
            box.moveCursorToEnd(false);
            box.setFocused(true);
        } else {
            box.setX(x);
            box.setY(y);
            box.setWidth(w);
        }
        box.render(g, mouseX, mouseY, partialTick);
        y += BOX_HEIGHT + 4;

        // ---- 剩余字数 ----
        String counter = box.getValue().length() + "/" + SetDeviceNamePacket.MAX_NAME_LENGTH;
        g.drawString(font, counter, x + w - font.width(counter), y, COLOR_HINT, false);
        y += font.lineHeight + 4;

        // ---- 说明 ----
        // 手机屏幕窄，说明文字用 split 自动折行
        for (var line : font.split(Component.translatable("mcphone.settings.device_name_hint"), w)) {
            g.drawString(font, line, x, y, COLOR_HINT, false);
            y += font.lineHeight + 1;
        }

        // ---- 保存 / 取消 ----
        int btnY = phoneTop + screenH - navH - font.lineHeight - 6;
        String save = Component.translatable("mcphone.settings.save").getString();
        String cancel = Component.translatable("mcphone.gui.cancel").getString();

        int saveW = font.width(save);
        int cancelW = font.width(cancel);
        int saveX = x;
        int cancelX = x + w - cancelW;

        boolean onSave = mouseX >= saveX - 2 && mouseX < saveX + saveW + 2
                      && mouseY >= btnY - 2 && mouseY < btnY + font.lineHeight + 2;
        boolean onCancel = mouseX >= cancelX - 2 && mouseX < cancelX + cancelW + 2
                        && mouseY >= btnY - 2 && mouseY < btnY + font.lineHeight + 2;
        hovered = onSave ? Btn.SAVE : onCancel ? Btn.CANCEL : Btn.NONE;

        g.drawString(font, save, saveX, btnY, onSave ? COLOR_BTN_ON : 0xFF66FF88, false);
        g.drawString(font, cancel, cancelX, btnY, onCancel ? COLOR_BTN_ON : COLOR_BTN, false);
    }

    // ============================================================
    //  交互
    // ============================================================

    /**
     * @return true 表示应当返回设置列表
     */
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (hovered == Btn.SAVE)   { save(); return true; }
            if (hovered == Btn.CANCEL) { return true; }
        }
        // 其余点击交给输入框，用来挪光标 / 选中
        if (box != null) box.mouseClicked(mx, my, button);
        return false;
    }

    /**
     * @return true 表示按键已被消费；needsBack() 另行判断是否要退出
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 回车＝保存。ESC 不在这里处理，交给 PhoneScreen 当作取消返回
        if (keyCode == 257 || keyCode == 335) {   // Enter / 小键盘 Enter
            save();
            backRequested = true;
            return true;
        }
        return box != null && box.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char c, int modifiers) {
        return box != null && box.charTyped(c, modifiers);
    }

    /** 回车保存后置位，PhoneScreen 取走后清零 */
    private boolean backRequested = false;

    public boolean consumeBackRequest() {
        if (!backRequested) return false;
        backRequested = false;
        return true;
    }

    // ============================================================
    //  保存
    // ============================================================

    /**
     * 发包让服务端写入。客户端这边先清洗一次是必须的：
     * 超长字符串在编码阶段就会抛 EncoderException，
     * 不能指望服务端兜底。
     */
    private void save() {
        if (box == null) return;
        String name = SetDeviceNamePacket.sanitize(box.getValue());
        PacketDistributor.sendToServer(
                new SetDeviceNamePacket(name, location));
    }
}
