package com.november.mcphone.platform.client.port;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** 新事件在回调期间保留完整字符、修饰键与双击信息。 */
public final class PhoneInputs {
    public static CharacterEvent character;
    public static MouseButtonEvent mouse;
    public static boolean doubleClick;
    private PhoneInputs() {}
    public static boolean down(int key) {
        var client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) return false;
        long handle = client.getWindow().handle();
        return handle != 0 && GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
    }
    public static boolean shift() { return down(GLFW.GLFW_KEY_LEFT_SHIFT)||down(GLFW.GLFW_KEY_RIGHT_SHIFT); }
    public static boolean control() {
        boolean mac=System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        return mac ? down(GLFW.GLFW_KEY_LEFT_SUPER)||down(GLFW.GLFW_KEY_RIGHT_SUPER)
                : down(GLFW.GLFW_KEY_LEFT_CONTROL)||down(GLFW.GLFW_KEY_RIGHT_CONTROL);
    }
    public static boolean isCopy(int key) { return control()&&key==GLFW.GLFW_KEY_C; }
    public static boolean alt() { return down(GLFW.GLFW_KEY_LEFT_ALT)||down(GLFW.GLFW_KEY_RIGHT_ALT); }
    public static int modifiers() { return (shift()?GLFW.GLFW_MOD_SHIFT:0)|(control()?GLFW.GLFW_MOD_CONTROL:0)|(alt()?GLFW.GLFW_MOD_ALT:0); }
    public static MouseButtonEvent mouse(double x,double y,int button) {
        return new MouseButtonEvent(x,y,new MouseButtonInfo(button,mouse==null?modifiers():mouse.modifiers()));
    }
    public static CharacterEvent character(char c) { return character==null?new CharacterEvent(c, modifiers()):character; }
    /**
     * 原生 CharacterEvent 仍在上下文时只转交一次，原版控件会读取其完整码点；
     * 不能把代理对各转一次，否则原生控件会插入同一码点两遍。
     * 没有原生事件时按 UTF-16 兼容传统 char 回调。
     */
    public static boolean dispatchCharacter(int codePoint, java.util.function.IntPredicate typed) {
        if (!Character.isValidCodePoint(codePoint)) return false;
        if (character != null) return typed.test(Character.isSupplementaryCodePoint(codePoint)
                ? Character.highSurrogate(codePoint) : (char) codePoint);
        if (Character.isBmpCodePoint(codePoint)) return typed.test((char) codePoint);
        boolean first = typed.test(Character.highSurrogate(codePoint));
        return typed.test(Character.lowSurrogate(codePoint)) | first;
    }

    /** 旧附属会将补充字符拆成代理对；原生控件在高位那次已收到完整事件，低位不能再提交。 */
    public static boolean submitCharacter(char unit, java.util.function.Predicate<CharacterEvent> receiver) {
        if (character != null && Character.isSupplementaryCodePoint(character.codepoint())
                && unit == Character.lowSurrogate(character.codepoint())) return false;
        return receiver.test(character(unit));
    }

}
