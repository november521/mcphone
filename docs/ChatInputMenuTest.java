package com.november.mcphone.feature.chat.client;

import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 真实 EditBox 与实际输入组件回调的粘贴回归；不创建窗口或修改系统剪贴板。 */
public final class ChatInputMenuTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static void field(Object owner,String name,Object value) throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static final class TestFont extends Font {
        private final StringSplitter splitter=new StringSplitter((cp,style)->6);
        TestFont() { super(id->null,false); }
        @Override public StringSplitter getSplitter() { return splitter; }
        @Override public int width(String text) { return (int)Math.ceil(splitter.stringWidth(text)); }
        @Override public int width(FormattedText text) { return (int)Math.ceil(splitter.stringWidth(text)); }
        @Override public String plainSubstrByWidth(String text,int width) { return splitter.plainHeadByWidth(text,width,Style.EMPTY); }
        @Override public String plainSubstrByWidth(String text,int width,boolean tail) { return tail?splitter.plainTailByWidth(text,width,Style.EMPTY):plainSubstrByWidth(text,width); }
        @Override public int width(FormattedCharSequence text) { return (int)Math.ceil(splitter.stringWidth(text)); }
    }
    public static void main(String[] args) throws Exception {
        var clipboard=new AtomicReference<>("XY");var sent=new AtomicInteger();
        var composer=new ChatComposer(text->sent.incrementAndGet(),clipboard::get,(g,x,y,w,h)->{});
        var box=new EditBox(new TestFont(),0,0,100,9,Component.empty());box.setMaxLength(8);box.setValue("abcd");
        // 给无窗口测试绑定实际控件与几何；生产路径仍在 render 中创建它们。
        field(composer,"box",box);field(composer,"inputBounds",new ChatLayout.Rect(10,20,50,12));
        var menu=(ChatInputMenu)field(composer,"inputMenu");
        box.setCursorPosition(2);box.setHighlightPos(4);
        check(!composer.openContextMenu(9,25),"输入框外右键不打开粘贴菜单");
        check(composer.openContextMenu(15,25)&&composer.hasContextMenu()&&box.isFocused(),"输入框内右键打开菜单并获得输入焦点");
        check(box.getCursorPosition()==2&&box.getHighlighted().equals("cd"),"右键保留已有光标和选区");
        menu.choosePaste();check(box.getValue().equals("abXY"),"实际输入组件的粘贴替换选区");
        check(box.getCursorPosition()==4&&box.getHighlighted().isEmpty(),"粘贴后 EditBox 收拢选区和更新光标");
        check(composer.dismissContextMenu()&&box.getValue().equals("abXY"),"关闭菜单不丢草稿");
        composer.openContextMenu(15,25);clipboard.set("Z");menu.choosePaste();
        check(box.getValue().equals("abXYZ"),"执行时读取当前剪贴板而不是打开时的旧内容");
        composer.dismissContextMenu();clipboard.set("0123456789");composer.openContextMenu(15,25);menu.choosePaste();
        check(box.getValue().equals("abXYZ012"),"实际 EditBox 保留既有草稿并限制总长度");
        composer.dismissContextMenu();box.setValue("");clipboard.set("a\nb\u0000c");composer.openContextMenu(15,25);menu.choosePaste();
        check(box.getValue().equals("abc"),"沿用原版过滤换行和不可输入字符");
        composer.dismissContextMenu();clipboard.set("");composer.openContextMenu(15,25);menu.choosePaste();
        check(box.getValue().equals("abc"),"空剪贴板不改草稿");
        check(composer.contextMouseClicked(-100,-100,0)&&!composer.hasContextMenu(),"点击菜单外仅关闭，消费事件");
        clipboard.set("Q");composer.openContextMenu(15,25);
        check(!composer.contextMouseClicked(15,25,1)&&!composer.hasContextMenu(),"再次右键交回页面定位新目标");
        composer.openContextMenu(15,25);composer.reset(false);
        check(!composer.hasContextMenu()&&box.getValue().isEmpty(),"切换会话同时清理菜单与输入状态");
        check(sent.get()==0,"右键和所有粘贴操作不隐式发送消息");
        System.out.println("全部通过："+checks+" 条断言");
    }
}
