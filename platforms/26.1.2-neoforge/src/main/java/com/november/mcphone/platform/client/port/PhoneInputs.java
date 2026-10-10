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
    public static boolean down(int key) { return GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(),key)==GLFW.GLFW_PRESS; }
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
    public static CharacterEvent character(char c) { return character==null?new CharacterEvent(c):character; }
}
