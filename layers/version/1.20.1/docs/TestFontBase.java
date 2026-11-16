package com.november.mcphone.test;
import net.minecraft.client.gui.Font;
/** 确定宽度字体替身的构造接缝。测试子类提供宽度和分行，不访问真实字形或 GPU。 */
public abstract class TestFontBase extends Font {
    protected TestFontBase() { super(id -> null,false); }
}
