package com.november.mcphone.core.script.client.render;

import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.core.script.layout.LayoutEngine;
import com.november.mcphone.core.script.layout.LayoutNode;
import com.november.mcphone.core.script.layout.NodeType;
import com.november.mcphone.core.script.layout.StateRules;
import com.november.mcphone.core.script.layout.TextMeasure;
import com.november.mcphone.core.script.layout.UiState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Page-owned text editors. Layout rebuilds retain the caret; hidden or replaced inputs lose focus. */
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
    private final Supplier<String> clipboard;
    private final Consumer<String> copy;
    private Map<String, Entry> fields = new LinkedHashMap<>();
    private Entry focused;
    private Entry dragged;

    public TextInputs() { this(EditBox::new, (box, x, y, button) -> box.mouseClicked(x, y, button)); }

    TextInputs(Factory factory, Pointer pointer) {
        this(factory, pointer, () -> Minecraft.getInstance().keyboardHandler.getClipboard(),
                text -> Minecraft.getInstance().keyboardHandler.setClipboard(text));
    }

    TextInputs(Factory factory, Pointer pointer, Supplier<String> clipboard, Consumer<String> copy) {
        this.factory = factory; this.pointer = pointer; this.clipboard = clipboard; this.copy = copy;
    }

    private static final class Entry {
        final EditBox box;
        final TextAreaEditor area;
        final Font font;
        final String bind;
        final String site;
        LayoutNode node;
        UiState state;
        boolean enabled;
        boolean syncing;
        int x, y, width, height;
        int areaLimit = -1;

        Entry(EditBox box, Font font, String bind, String site, boolean multiline) {
            this.box = box;
            this.font = font;
            this.bind = bind;
            this.site = site;
            if (multiline) {
                // Keep vanilla's OS text-input focus notification (required since 26.1).
                // This box is a focus delegate only; text, selection and painting belong to area.
                this.area = new TextAreaEditor(new FontMeasure(font), value -> {
                    if (syncing || !enabled || state == null) return false;
                    Map<String, Object> next = new LinkedHashMap<>(state.values());
                    next.put(bind, value);
                    if (state.writeProblems(next) != null) return false;
                    state.set(bind, value);
                    return true;
                });
                return;
            }
            this.area = null;
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

        void focus(boolean value) { box.setFocused(value); }
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
        if (node.node.type().textEditor() && node.w > 0 && node.h > 0
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
        boolean multiline = node.node.type() == NodeType.TEXTAREA;
        Entry entry = fields.get(node.key);
        if (entry == null || entry.font != font || entry.state != state
                || !entry.bind.equals(bind) || !entry.site.equals(site) || (entry.area != null) != multiline) {
            entry = new Entry(factory.create(font, 0, 0, width - 1, font.lineHeight, Component.empty()), font, bind, site, multiline);
        }
        entry.node = node;
        entry.state = state;
        boolean enabled = Renderer.isEnabled(node, state);
        if (!enabled && focused == entry) blur();
        entry.enabled = enabled;
        entry.x = x + node.style.padLeft();
        entry.y = y + node.style.padTop() + (multiline ? 0 : Math.max(0, (height - font.lineHeight) / 2));
        entry.width = width;
        entry.height = height;
        if (multiline) {
            int limit = Math.min(StateRules.MAX_PAGE_STRING, state.stringLimit());
            int max = Math.max(1, Math.min(limit, node.node.num("max-length", limit)));
            String value = state.getString(bind);
            String normalized = entry.areaLimit == max && value.equals(entry.area.value())
                    ? value : TextAreaEditor.normalize(value, max);
            entry.areaLimit = max;
            if (!value.equals(normalized)) state.set(bind, normalized);
            entry.area.sync(normalized, max, Math.max(1, width - 4), height);
            next.put(node.key, entry);
            return;
        }
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
        if (entry.area != null) {
            drawArea(entry, canvas, foreground);
            return;
        }
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

    private void drawArea(Entry entry, PhoneCanvas canvas, int color) {
        TextAreaEditor area = entry.area;
        int lineHeight = area.lineHeight();
        canvas.clipped(entry.x, entry.y, entry.width, entry.height, () -> {
            String text = area.value();
            if (text.isEmpty() && focused != entry) {
                String hint = canvas.font().plainSubstrByWidth(entry.node.node.str("placeholder", ""), Math.max(1, entry.width - 4));
                canvas.graphics().drawString(canvas.font(), hint, entry.x, entry.y, canvas.style().subtleColor(), false);
            }
            int first = area.scroll() / lineHeight;
            int last = Math.min(area.lines().size() - 1, (area.scroll() + entry.height - 1) / lineHeight);
            int from = Math.min(area.cursor(), area.anchor()), to = Math.max(area.cursor(), area.anchor());
            int cursorLine = area.cursorLine();
            for (int row = first; row <= last; row++) {
                TextAreaEditor.Line line = area.lines().get(row);
                int y = entry.y + row * lineHeight - area.scroll();
                if (from != to && from <= line.end() && to > line.start()) {
                    int left = canvas.font().width(text.substring(line.start(), Math.max(line.start(), from)));
                    int right = to > line.end() ? Math.max(1, entry.width - 4)
                            : canvas.font().width(text.substring(line.start(), to));
                    canvas.graphics().fill(entry.x + left, y, entry.x + Math.max(left + 1, right), y + lineHeight, 0x665b9dff);
                }
                canvas.graphics().drawString(canvas.font(), text.substring(line.start(), line.end()), entry.x, y, color, false);
                if (focused == entry && row == cursorLine && area.caretVisible()) {
                    int x = entry.x + canvas.font().width(text.substring(line.start(), area.cursor()));
                    canvas.graphics().fill(x, y, x + 1, y + lineHeight, color);
                }
            }
            if (area.contentHeight() > entry.height) {
                int thumb = Math.min(entry.height, Math.max(4, (int) ((long) entry.height * entry.height / area.contentHeight())));
                int top = entry.y + (int) ((long) area.scroll() * (entry.height - thumb) / (area.contentHeight() - entry.height));
                canvas.graphics().fill(entry.x + entry.width - 2, top, entry.x + entry.width, top + thumb, canvas.style().subtleColor());
            }
        });
    }

    /** hit was chosen using the same clipping and stacking rules as the renderer. */
    public boolean mouseClicked(LayoutNode hit, double x, double y, int button) {
        if (button != 0) return false;
        Entry target = hit == null ? null : fields.get(hit.key);
        if (target == null || target.node != hit || !target.enabled) {
            blur();
            return hit != null && hit.node.type().textEditor();
        }
        if (focused != target) {
            blur();
            focused = target;
            target.focus(true);
        }
        if (target.area != null) {
            target.area.point(x - target.x, y - target.y, false);
            dragged = target;
            return true;
        }
        pointer.click(target.box, Math.max(0, Math.min(target.box.getWidth() - 1, x - target.x)),
                Math.max(0, Math.min(target.box.getHeight() - 1, y - target.y)), button);
        return true;
    }

    public boolean mouseDragged(double x, double y, int button) {
        if (button != 0 || dragged == null || dragged != focused || !focused()) return false;
        dragged.area.drag(x - dragged.x, y - dragged.y);
        return true;
    }

    public boolean mouseReleased(int button) {
        if (button != 0 || dragged == null) return false;
        dragged = null;
        return true;
    }

    public boolean mouseScrolled(LayoutNode hit, double amount) {
        if (hit == null || hit.node.type() != NodeType.TEXTAREA) return false;
        Entry target = fields.get(hit.key);
        if (target != null && target.node == hit && target.enabled) target.area.scroll(amount);
        return true; // the editor owns its wheel, including its boundaries
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!focused()) return false;
        if (key == 258) {
            var candidates = fields.values().stream().filter(e -> e.enabled).toList();
            int index = candidates.indexOf(focused);
            Entry next = candidates.get(Math.floorMod(index + ((modifiers & 1) != 0 ? -1 : 1), candidates.size()));
            blur(); focused = next; next.focus(true);
        } else if (focused.area != null) {
            focused.area.key(key, (modifiers & (2 | 8)) != 0, (modifiers & 1) != 0, clipboard, copy);
        } else focused.box.keyPressed(key, scan, modifiers);
        return true; // keep typing shortcuts away from Minecraft's inventory/navigation bindings
    }

    public boolean charTyped(char c, int modifiers) {
        if (!focused()) return false;
        if (focused.area != null) {
            if (!Character.isSurrogate(c) && c >= ' ') focused.area.insert(String.valueOf(c));
            return true;
        }
        focused.box.charTyped(c, modifiers);
        return true;
    }

    public boolean charTyped(int codePoint, int modifiers) {
        if (!focused() || !Character.isValidCodePoint(codePoint)) return false;
        if (Character.isBmpCodePoint(codePoint)) return charTyped((char) codePoint, modifiers);
        if (focused.area != null) focused.area.insert(new String(Character.toChars(codePoint)));
        else focused.box.insertText(new String(Character.toChars(codePoint)));
        return true;
    }

    public boolean focused() { return focused != null && focused.enabled && focused.box.isFocused(); }

    public void blur() {
        if (focused != null) focused.focus(false);
        focused = null;
        dragged = null;
    }

    public void clear() { blur(); fields.clear(); }

    public void tick() {
        for (Entry entry : fields.values()) com.november.mcphone.platform.client.EditBoxes.tick(entry.box);
    }
}
