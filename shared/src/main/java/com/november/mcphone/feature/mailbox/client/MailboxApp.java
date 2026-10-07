package com.november.mcphone.feature.mailbox.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class MailboxApp implements IPhoneApp {
    @Override public ResourceLocation getId() { return ResourceLocation.fromNamespaceAndPath("mcphone", "mailbox"); }
    @Override public Component getDisplayName() { return Component.translatable("mcphone.mailbox.title"); }
    @Override public ResourceLocation getIconTexture() { return null; }
    @Override public void renderIcon(net.minecraft.client.gui.GuiGraphics g, int x, int y, int size, float tick) {
        g.fill(x, y + size / 5, x + size, y + size * 4 / 5, 0xFF4488BB);
        g.fill(x + 2, y + size / 2, x + size - 2, y + size / 2 + 1, 0xFFFFFFFF);
    }
    @Override public void onPress() {}
    @Override public int getBadgeCount(){return com.november.mcphone.core.script.client.ClientNotifications.badge("mcphone:mailbox");}
    @Override public IPhonePage openPage() { return new MailboxPage(); }
}
