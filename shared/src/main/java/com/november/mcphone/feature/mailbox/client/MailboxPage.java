package com.november.mcphone.feature.mailbox.client;

import com.google.gson.*;
import com.november.mcphone.api.client.ui.*;
import com.november.mcphone.core.script.client.ScriptCall;
import com.november.mcphone.core.script.net.*;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;

/** 每页五件；关闭页面后回调不再改 UI，领取只由用户点击触发。 */
public final class MailboxPage implements IPhonePage {
    private JsonArray items = new JsonArray();
    private int offset, total, x, y, width, height;
    private boolean closed, busy;
    private long generation;
    private String message = "";
    @Override public void onOpen() { closed = false; generation++; refresh(); }
    @Override public void onClose() { closed = true; generation++; }
    private void refresh() {
        busy = true;
        long expected = generation;
        ScriptCall.call(ScriptProtocol.HOST_APP_ID, "mailbox.list", ("{\"offset\":" + offset + "}").getBytes(StandardCharsets.UTF_8), null, result -> {
            if (closed || expected != generation) return; busy = false;
            if (result.code() != ScriptErrorCode.OK) { message = Component.translatable(result.code().defaultMessageKey()).getString(); return; }
            JsonObject data = JsonParser.parseString(new String(result.data(), StandardCharsets.UTF_8)).getAsJsonObject();
            items = data.getAsJsonArray("items"); total = data.get("total").getAsInt();
        });
    }
    @Override public void render(PhoneCanvas c) {
        x = c.x(); y = c.y(); width = c.width(); height = c.height();
        c.graphics().drawString(c.font(), Component.translatable("mcphone.mailbox.count", total), x + 3, y + 3, c.style().bodyColor(), false);
        for (int i = 0; i < items.size(); i++) {
            var row = items.get(i).getAsJsonObject(); int py = y + 18 + i * 24;
            c.graphics().fill(x + 2, py, x + width - 2, py + 22, c.style().buttonColor());
            String label = row.get("name").getAsString() + " ×" + row.get("count").getAsInt();
            c.graphics().drawString(c.font(), c.font().plainSubstrByWidth(label, width - 8), x + 4, py + 2, c.style().bodyColor(), false);
            String state = row.get("state").getAsString().equals("AVAILABLE") ? "mcphone.mailbox.claim" : "mcphone.mailbox.review";
            c.graphics().drawString(c.font(), Component.translatable(state), x + 4, py + 12, c.style().subtleColor(), false);
        }
        c.graphics().drawString(c.font(), "◀  " + (offset / 5 + 1) + "  ▶", x + 3, y + height - 25, c.style().bodyColor(), false);
        String shown = busy ? Component.translatable("mcphone.mailbox.loading").getString() : message;
        c.graphics().drawString(c.font(), c.font().plainSubstrByWidth(shown, Math.max(0, width - 6)), x + 3, y + height - 12, c.style().bodyColor(), false);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (mx < x || mx >= x + width || my < y || my >= y + height) return false;
        if (busy || button != 0) return true;
        if (my >= y + height - 29) {
            if (mx < x + width / 2 && offset >= 5) offset -= 5;
            else if (mx >= x + width / 2 && offset + 5 < total) offset += 5;
            refresh(); return true;
        }
        int index = (int) (my - y - 18) / 24;
        if (my >= y + 18 && index >= 0 && index < items.size()) {
            var row = items.get(index).getAsJsonObject();
            if (!row.get("state").getAsString().equals("AVAILABLE")) return true;
            busy = true;
            long expected = generation;
            ScriptCall.call(ScriptProtocol.HOST_APP_ID, "mailbox.claim", ("{\"id\":\"" + row.get("id").getAsString() + "\"}").getBytes(StandardCharsets.UTF_8), null, result -> {
                if (closed || generation != expected) return; message = Component.translatable(result.code().defaultMessageKey()).getString(); refresh();
            });
        }
        return true;
    }
}
