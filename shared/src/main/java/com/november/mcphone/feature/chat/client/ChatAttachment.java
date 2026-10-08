package com.november.mcphone.feature.chat.client;

import net.minecraft.network.chat.Component;

/** 输入栏附件选择的语义；由 PhoneScreen 消费，组件不直接导航到其他页面。 */
public enum ChatAttachment {
    IMAGE("mcphone.chat.attach_image"),
    STICKER("mcphone.chat.attach_sticker");

    private final String labelKey;
    ChatAttachment(String labelKey) { this.labelKey = labelKey; }
    public String label() { return Component.translatable(labelKey).getString(); }
}
