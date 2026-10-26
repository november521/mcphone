package com.november.mcphone.core.script.client.render;

import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.core.script.layout.LayoutEngine;
import com.november.mcphone.core.script.layout.LayoutNode;
import com.november.mcphone.core.script.layout.NodeType;
import com.november.mcphone.core.script.layout.StateRules;
import com.november.mcphone.core.script.layout.TextMeasure;
import com.november.mcphone.core.script.layout.UiState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Page-owned vanilla editors. Layout rebuilds retain the caret; hidden or replaced inputs lose focus. */
public final class TextInputs {
    @FunctionalInterface
    interface Factory {
        EditBox create(Font font, int x, int y, int width, int height, Component label);
    }

    @FunctionalInterface
    interface Pointer {
        void click(EditBox box, double x, double y, int button);
    }

    private final Factory factory;
    private final Pointer pointer;
    private Map<String, Entry> fields = new LinkedHashMap<>();
    private Entry focused;

    public TextInputs() { this(EditBox::new, (box, x, y, button) -> box.mouseClicked(x, y, button)); }

    TextInputs(Factory factory, Pointer pointer) { this.factory = factory; this.pointer = pointer; }

    private static final class Entry {
        final EditBox box;
        final Font font;
        final String bind;
        final String site;
        LayoutNode node;
        UiState state;
        boolean enabled;
        boolean syncing;
        int x, y, width, height;

        Entry(EditBox box, Font font, String bind, String site) {
            this.box = box;
            this.font = font;
            this.bind = bind;
            this.site = site;
            box.setBordered(false);
            box.setResponder(value -> {
                if (!syncing && enabled && state != null) {
                    String normalized = singleLine(value, StateRules.MAX_STRING);
                    if (!normalized.equals(value)) {
                        syncing = true;
                        try { box.setValue(normalized); } finally { syncing = false; }
                    }
                    state.set(bind, normalized);
                }
            });
        }
    }

    /** Reconcile before rendering AND input dispatch, so a just-hidden field cannot retain keyboard input. */
    public void sync(LayoutNode root, UiState state, Font font, int x, int y, int width, int height) {
        Map<String, Entry> next = new LinkedHashMap<>();
        visit(root, state, font, new FontMeasure(font), x, y, x, y,
                (long) x + width, (long) y + height, next);
        if (focused != null && (!next.containsValue(focused) || !focused.enabled)) blur();
        fields = next;
    }

    private void visit(LayoutNode node, UiState state, Font font, TextMeasure measure, int ox, int oy,
                       long left, long top, long right, long bottom, Map<String, Entry> next) {
        if (left >= right || top >= bottom) return;
        int x = node.x + ox;
        int y = node.y + oy;
        if (node.node.type() == NodeType.TEXT_INPUT && node.w > 0 && node.h > 0
                && x < right && (long) x + node.w > left && y < bottom && (long) y + node.h > top) {
            update(node, state, font, x, y, next);
        }
        if (Renderer.isScroller(node)) {
            long l = Math.max(left, x), t = Math.max(top, y);
            long r = Math.min(right, (long) x + node.w), b = Math.min(bottom, (long) y + node.h);
            if (l >= r || t >= b) return;
            int sy = Renderer.clampScroll(node);
            int[] range = Renderer.childRange(node, sy);
            for (int i = range[0]; i <= range[1]; i++) {
                LayoutEngine.layoutItem(node, i, measure);
                visit(node.children.get(i), state, font, measure, ox, oy - sy, l, t, r, b, next);
            }
        } else {
            for (LayoutNode child : node.children) visit(child, state, font, measure, ox, oy, left, top, right, bottom, next);
        }
    }

    private void update(LayoutNode node, UiState state, Font font, int x, int y, Map<String, Entry> next) {
        String bind = node.node.str("bind", "");
        if (!(state.get(bind) instanceof String)) return;
        int width = node.w - node.style.padLeft() - node.style.padRight();
        int height = node.h - node.style.padTop() - node.style.padBottom();
        if (width < 2 || height < 1) return;
        String site = node.node.str("input-site", "");
        Entry entry = fields.get(node.key);
        if (entry == null || entry.font != font || entry.state != state
                || !entry.bind.equals(bind) || !entry.site.equals(site)) {
            entry = new Entry(factory.create(font, 0, 0, width - 1, font.lineHeight, Component.empty()), font, bind, site);
        }
        entry.node = node;
        entry.state = state;
        boolean enabled = Renderer.isEnabled(node, state);
        if (!enabled && focused == entry) blur();
        entry.enabled = enabled;
        entry.x = x + node.style.padLeft();
        entry.y = y + node.style.padTop() + Math.max(0, (height - font.lineHeight) / 2);
        entry.width = width;
        entry.height = height;
        int max = Math.max(1, Math.min(StateRules.MAX_STRING, node.node.num("max-length", StateRules.MAX_STRING)));
        entry.syncing = true;
        try {
            if (entry.box.getWidth() != width - 1) {
                entry.box.setWidth(width - 1); // reserve one pixel for the caret
                entry.box.setCursorPosition(entry.box.getCursorPosition());
            }
            entry.box.setMaxLength(max);
            String value = state.getString(bind);
            // A bound value changed by a button is authoritative too. Match vanilla max-length/filter rules.
            String normalized = singleLine(value, max);
            if (!entry.box.getValue().equals(normalized)) entry.box.setValue(normalized);
            if (!value.equals(entry.box.getValue())) state.set(bind, entry.box.getValue());
            if (entry.box.active != entry.enabled) {
                entry.box.active = entry.enabled;
                entry.box.setEditable(entry.enabled);
            }
        } finally {
            entry.syncing = false;
        }
        next.put(node.key, entry);
    }

    private static String singleLine(String value, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)) && out.length() + 2 <= max) {
                    out.append(c).append(value.charAt(++i));
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            if (c >= ' ' && c != 127 && c != '\u00a7') out.append(c);
        }
        return out.toString();
    }

    /** Called within Renderer's existing paint/clip order, never as an overlay above later siblings. */
    void draw(LayoutNode node, PhoneCanvas canvas, int foreground) {
        Entry entry = fields.get(node.key);
        if (entry == null || entry.node != node) return;
        EditBox box = entry.box;
        box.setTextColor(foreground);
        box.setTextColorUneditable(foreground);
        canvas.clipped(entry.x, entry.y, entry.width, entry.height, () -> canvas.transformed(() -> {
            canvas.graphics().pose().translate(entry.x, entry.y, 0);
            box.render(canvas.graphics(), canvas.mouseX() - entry.x, canvas.mouseY() - entry.y, canvas.partialTick());
            if (box.getValue().isEmpty() && !box.isFocused()) {
                String hint = canvas.font().plainSubstrByWidth(node.node.str("placeholder", ""), box.getWidth());
                canvas.graphics().drawString(canvas.font(), hint, 0, 0, canvas.style().subtleColor(), false);
            }
        }));
    }

    /** hit was chosen using the same clipping and stacking rules as the renderer. */
    public boolean mouseClicked(LayoutNode hit, double x, double y, int button) {
        if (button != 0) return false;
        Entry target = hit == null ? null : fields.get(hit.key);
        if (target == null || target.node != hit || !target.enabled) {
            blur();
            return hit != null && hit.node.type() == NodeType.TEXT_INPUT;
        }
        if (focused != target) {
            blur();
            focused = target;
            target.box.setFocused(true);
        }
        pointer.click(target.box, Math.max(0, Math.min(target.box.getWidth() - 1, x - target.x)),
                Math.max(0, Math.min(target.box.getHeight() - 1, y - target.y)), button);
        return true;
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!focused()) return false;
        if (key == 258) {
            var candidates = fields.values().stream().filter(e -> e.enabled).toList();
            int index = candidates.indexOf(focused);
            Entry next = candidates.get(Math.floorMod(index + ((modifiers & 1) != 0 ? -1 : 1), candidates.size()));
            blur(); focused = next; next.box.setFocused(true);
        } else focused.box.keyPressed(key, scan, modifiers);
        return true; // keep typing shortcuts away from Minecraft's inventory/navigation bindings
    }

    public boolean charTyped(char c, int modifiers) {
        if (!focused()) return false;
        focused.box.charTyped(c, modifiers);
        return true;
    }

    public boolean charTyped(int codePoint, int modifiers) {
        if (!focused() || !Character.isValidCodePoint(codePoint)) return false;
        if (Character.isBmpCodePoint(codePoint)) return charTyped((char) codePoint, modifiers);
        focused.box.insertText(new String(Character.toChars(codePoint)));
        return true;
    }

    public boolean focused() { return focused != null && focused.enabled && focused.box.isFocused(); }

    public void blur() {
        if (focused != null) focused.box.setFocused(false);
        focused = null;
    }

    public void clear() { blur(); fields.clear(); }

    public void tick() {
        for (Entry entry : fields.values()) com.november.mcphone.platform.client.EditBoxes.tick(entry.box);
    }
}
