package com.november.mcphone.core.client.home;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.core.client.DeviceMetrics;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.HomeLayout;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.client.UnreadBadge;
import com.november.mcphone.core.client.home.HomeInput.FolderDrag;
import com.november.mcphone.platform.ResourceIds;
import com.november.mcphone.platform.client.EditBoxes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Folder presentation and its transient editing and selection state. */
public final class HomeFolderView {
    private static final int TEXT = 0xFFF5F6F8;
    private static final int ACCENT = 0xFFC3DEFF;
    private static final int MUTED_TEXT = 0xFFB9C3D6;
    private static final int HOVER = 0x445883BF;
    private static final int SELECTED = 0x895691CE;
    private static final int ADD_ICON_TEXTURE_SIZE = 40;
    private static final ResourceLocation ADD_ICON =
            ResourceIds.create(MCphone.MODID, "textures/phone/home_folder_add.png");
    private static final ResourceLocation ADD_ICON_HOVER =
            ResourceIds.create(MCphone.MODID, "textures/phone/home_folder_add_hover.png");
    private static final String ADD_TILE = "+"; // App IDs always contain a namespace.
    private static final int FOOTER_HEIGHT = 13;
    private static final float TITLE_SCALE = 1.25f;
    private static final int RENAME_HEIGHT = 14;
    private static final int RENAME_GAP = 2;

    private record RenameBar(int left, int top, int width, int textLeft, int textTop, int textWidth) {
        boolean contains(double x, double y) {
            return HomeFolderView.contains(x, y, left, top, width, RENAME_HEIGHT);
        }
    }

    private final HomeInput input = new HomeInput(PhoneTheme.APP_DRAG_THRESHOLD, PhoneTheme.PAGE_SWIPE_THRESHOLD);
    private final Set<String> selection = new LinkedHashSet<>();

    private String folderId;
    private boolean picker;
    private EditBox rename;
    private int page;
    private int folderPage;
    private HomeLayout.FolderGrid grid;
    private Font font;

    public void open(String id) {
        close();
        if (PhoneScreenRegistry.homeFolder(id) != null) folderId = id;
    }

    public boolean isOpen() {
        if (folderId != null && PhoneScreenRegistry.homeFolder(folderId) == null) close();
        return folderId != null;
    }

    public boolean isRenaming() {
        return rename != null;
    }

    public void close() {
        folderId = null;
        grid = null;
        rename = null;
        picker = false;
        selection.clear();
        page = 0;
        input.cancel();
    }

    public void cancelInput() {
        input.cancel();
    }

    /** Back cancels the current action before leaving the folder. */
    public void back() {
        if (rename != null) rename = null;
        else if (picker) closePicker();
        else if (!input.dragging()) close();
        input.cancel();
    }

    public void tick() {
        if (rename != null) EditBoxes.tick(rename);
    }

    public void render(GuiGraphics g, int left, int top, DeviceMetrics metrics, Font font,
                       int mouseX, int mouseY, float partialTick, HomeBackdrop backdrop) {
        HomeArrangement.Folder folder = PhoneScreenRegistry.homeFolder(folderId);
        if (folder == null) return;
        this.font = font;
        List<String> tiles = tiles(folder);
        grid = HomeLayout.folderGrid(metrics, left, top);
        backdrop.drawFrostedPanel(g, grid);
        if (rename != null) {
            drawRenameBar(g, backdrop, mouseX, mouseY, partialTick);
        } else {
            drawTitle(g, folder, mouseX, mouseY);
        }

        page = HomeLayout.clampPage(page, tiles.size(), grid.pageSize());
        int start = page * grid.pageSize();
        int end = Math.min(tiles.size(), start + grid.pageSize());
        String floating = input.dragging() && !ADD_TILE.equals(input.id()) ? input.id() : null;
        int pending = floating == null ? -1 : tiles.indexOf(floating);
        if (pending >= 0 && withinRows(input.x(), input.y())) {
            List<String> preview = new ArrayList<>(folder.members());
            HomeLayout.reorder(preview, pending, memberDropIndex(input.x(), input.y(), preview.size()));
            if (tiles.size() > preview.size()) preview.add(ADD_TILE);
            tiles = preview;
        }

        for (int i = start; i < end; i++) {
            int slot = i - start;
            String id = tiles.get(i);
            int x = grid.x(slot);
            int y = grid.y(slot);
            if (id.equals(floating)) {
                g.fill(x, y, x + grid.iconSize(), y + grid.iconSize(), HOVER);
            } else {
                boolean hovered = rename == null && !input.dragging() && grid.hit(mouseX, mouseY) == slot;
                drawTile(g, id, x, y, hovered, partialTick);
            }
        }

        if (floating != null && pending >= 0) {
            int size = grid.iconSize();
            int x = Math.max(left, Math.min((int) input.x() - size / 2, left + metrics.contentWidth() - size));
            int y = Math.max(top, Math.min((int) input.y() - size / 2, top + metrics.screenH() - size));
            drawTile(g, floating, x, y, false, partialTick);
        }

        if (picker) {
            drawActions(g, "mcphone.home.folder.done", !selection.isEmpty(), mouseX, mouseY);
        }
        if (floating == null) drawPagination(g, tiles.size());
    }

    private void drawTitle(GuiGraphics g, HomeArrangement.Folder folder, int mouseX, int mouseY) {
        String title = picker ? label("mcphone.home.folder.choose") : folder.name();
        boolean hovered = !picker && onTitle(mouseX, mouseY);
        g.pose().pushPose();
        g.pose().translate(grid.titleLeft(), grid.titleY(), 0);
        g.pose().scale(TITLE_SCALE, TITLE_SCALE, 1);
        g.drawString(font, GuiUtil.truncate(font, title, (int) (grid.titleWidth() / TITLE_SCALE)), 0, 0,
                hovered ? ACCENT : TEXT, !picker);
        g.pose().popPose();
    }

    private RenameBar renameBar() {
        int top = grid.panelTop() - RENAME_HEIGHT - RENAME_GAP;
        int textWidth = (int) (grid.titleWidth() / TITLE_SCALE);
        return new RenameBar(grid.panelLeft(), top, grid.panelWidth(), grid.titleLeft(), grid.titleY(), textWidth);
    }

    private void drawRenameBar(GuiGraphics g, HomeBackdrop backdrop, int mouseX, int mouseY, float partialTick) {
        RenameBar bar = renameBar();
        backdrop.drawRenameSurface(g, bar.left(), bar.top(), bar.width(), RENAME_HEIGHT);
        rename.setWidth(bar.textWidth());
        g.pose().pushPose();
        try {
            g.pose().translate(bar.textLeft(), bar.textTop(), 0);
            g.pose().scale(TITLE_SCALE, TITLE_SCALE, 1);
            g.flush();
            rename.render(g, (int) ((mouseX - bar.textLeft()) / TITLE_SCALE),
                    (int) ((mouseY - bar.textTop()) / TITLE_SCALE), partialTick);
        } finally {
            g.pose().popPose();
        }
    }

    private void drawTile(GuiGraphics g, String id, int x, int y, boolean hovered, float partialTick) {
        int size = grid.iconSize();
        String name;
        if (ADD_TILE.equals(id)) {
            GuiUtil.drawTexture(g, hovered ? ADD_ICON_HOVER : ADD_ICON, x, y, size, size,
                    ADD_ICON_TEXTURE_SIZE, ADD_ICON_TEXTURE_SIZE);
            name = label("mcphone.home.folder.add");
        } else {
            IPhoneApp app = PhoneScreenRegistry.homeApp(id);
            if (app == null) return;
            boolean selected = selection.contains(id);
            if (selected || hovered) {
                g.fill(x - 2, y - 2, x + size + 2, y + size + 2, selected ? SELECTED : HOVER);
            }
            app.renderIcon(g, x, y, size, partialTick);
            int count = UnreadBadge.count(app);
            if (count > 0) {
                String badge = count > 99 ? "99+" : String.valueOf(count);
                UnreadBadge.draw(g, font, badge, x + size - UnreadBadge.width(font, badge) + 2, y - 2);
            }
            name = app.getDisplayName().getString();
        }
        GuiUtil.drawIconLabel(g, font, name, x, y, size, grid.pitch() - 2, grid.labelScale(), TEXT, true);
    }

    private void drawActions(GuiGraphics g, String confirm, boolean enabled, int x, int y) {
        String done = label(confirm);
        String cancel = label("mcphone.gui.cancel");
        g.drawString(font, done, grid.controlsLeft(), grid.footerY() + 2,
                !enabled ? MUTED_TEXT : onFooter(x, y) && onLeft(x) ? ACCENT : TEXT, true);
        g.drawString(font, cancel, grid.controlsLeft() + grid.controlsWidth() - font.width(cancel), grid.footerY() + 2,
                onFooter(x, y) && !onLeft(x) ? ACCENT : TEXT, true);
    }

    private void drawPagination(GuiGraphics g, int count) {
        int pages = HomeLayout.pageCount(count, grid.pageSize());
        if (pages <= 1) return;
        String marker = (page + 1) + "/" + pages;
        int y = pageControlY();
        int x = grid.left() + grid.width() / 2;
        drawCenteredLabel(g, marker, x, y, TEXT);
        drawCenteredLabel(g, "<", x - 26, y, page > 0 ? TEXT : MUTED_TEXT);
        drawCenteredLabel(g, ">", x + 26, y, page + 1 < pages ? TEXT : MUTED_TEXT);
    }

    private void drawCenteredLabel(GuiGraphics g, String label, int x, int y, int color) {
        g.drawString(font, label, x - font.width(label) / 2, y, color, true);
    }

    public void click(double x, double y, int button) {
        if (!isOpen() || grid == null) return;
        if (button != 0 && button != 1) return;
        if (rename != null) {
            if (button != 0) return;
            RenameBar bar = renameBar();
            if (!bar.contains(x, y)) {
                saveRename();
                rename = null;
            } else {
                double textX = Math.max(0, Math.min(bar.textWidth() - 1, (x - bar.textLeft()) / TITLE_SCALE));
                rename.mouseClicked(textX, font.lineHeight / 2.0, button);
            }
            return;
        }

        if (button == 1) {
            input.cancel();
            return;
        }
        HomeArrangement.Folder folder = PhoneScreenRegistry.homeFolder(folderId);
        List<String> tiles = tiles(folder);
        if (clickPageControl(x, y, tiles.size())) return;
        if (picker && onFooter(x, y)) {
            if (onLeft(x)) savePicker();
            else closePicker();
            return;
        }

        if (!picker && onTitle(x, y)) {
            beginRename(folder.name());
            return;
        }
        if (!picker && !grid.containsPanel(x, y)) {
            close();
            return;
        }
        String id = hitTile(tiles, x, y);
        if (!picker) input.press(id, x, y);
        else if (id != null && !selection.add(id)) selection.remove(id);
    }

    /** Returns the source of the drag when the desktop should take over. */
    public FolderDrag drag(double x, double y, int button) {
        if (button != 0 || !input.pressed() || grid == null) return null;
        int step = input.drag(x, y);
        if (step != 0) stepPage(step);
        if (!input.dragging() || ADD_TILE.equals(input.id()) || grid.containsPanel(x, y)) return null;
        return leaveWithApp(input.id());
    }

    public FolderDrag release(double x, double y, int button, Consumer<IPhoneApp> openApp) {
        if (button != 0 || !isOpen() || grid == null || !input.pressed()) return null;
        HomeArrangement.Folder folder = PhoneScreenRegistry.homeFolder(folderId);
        String hit = hitTile(tiles(folder), x, y);
        HomeInput.Release event = input.release(hit, x, y);
        if (event.clicked() && ADD_TILE.equals(event.id())) {
            folderPage = page;
            page = 0;
            picker = true;
        } else if (event.clicked()) {
            IPhoneApp app = PhoneScreenRegistry.homeApp(event.id());
            if (app != null) openApp.accept(app);
        } else if (event.dragged() && !ADD_TILE.equals(event.id())) {
            if (!grid.containsPanel(x, y)) {
                return leaveWithApp(event.id());
            } else if (withinRows(x, y)) {
                PhoneScreenRegistry.moveFolderApp(folderId, event.id(), memberDropIndex(x, y, folder.members().size()));
            }
        }
        return null;
    }

    private FolderDrag leaveWithApp(String id) {
        FolderDrag drag = new FolderDrag(folderId, id);
        close();
        return drag;
    }

    public void scroll(double horizontal, double vertical) {
        double delta = vertical != 0 ? vertical : horizontal;
        if (rename == null && delta != 0) stepPage(delta < 0 ? 1 : -1);
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) { back(); return true; }
        if (rename != null) {
            if (key == 257 || key == 335) saveRename();
            else rename.keyPressed(key, scan, modifiers);
            return true;
        }
        if (key == 263 || key == 262) {
            stepPage(key == 263 ? -1 : 1);
            return true;
        }
        return false;
    }

    public void charTyped(int codePoint, int modifiers) {
        if (rename == null || !Character.isValidCodePoint(codePoint)) return;
        if (Character.isBmpCodePoint(codePoint)) rename.charTyped((char) codePoint, modifiers);
        else rename.insertText(new String(Character.toChars(codePoint)));
    }

    private void beginRename(String name) {
        input.cancel();
        rename = new EditBox(font, 0, 0, renameBar().textWidth(), font.lineHeight + 1,
                Component.translatable("mcphone.home.folder.rename"));
        rename.setBordered(false);
        rename.setTextColor(TEXT);
        rename.setMaxLength(HomeArrangement.MAX_NAME_LENGTH);
        rename.setValue(name);
        EditBoxes.moveCursorToEnd(rename);
        rename.setFocused(true);
    }

    private void saveRename() {
        String name = rename.getValue().strip();
        if (name.isEmpty()) return;
        PhoneScreenRegistry.renameFolder(folderId, name);
        rename = null;
    }

    private void savePicker() {
        if (selection.isEmpty()) return;
        PhoneScreenRegistry.addFolderApps(folderId, List.copyOf(selection));
        closePicker();
    }

    private void closePicker() {
        picker = false;
        selection.clear();
        page = folderPage;
    }

    private List<String> tiles(HomeArrangement.Folder folder) {
        List<HomeArrangement.Entry> entries = PhoneScreenRegistry.homeEntries();
        if (picker) {
            return entries.stream().filter(HomeArrangement.App.class::isInstance)
                    .map(HomeArrangement.Entry::id).toList();
        }
        if (entries.stream().noneMatch(HomeArrangement.App.class::isInstance)) return folder.members();
        List<String> tiles = new ArrayList<>(folder.members());
        tiles.add(ADD_TILE);
        return tiles;
    }

    private void stepPage(int step) {
        if (grid == null || !isOpen()) return;
        int count = tiles(PhoneScreenRegistry.homeFolder(folderId)).size();
        page = HomeLayout.clampPage(page + step, count, grid.pageSize());
    }

    private int memberDropIndex(double x, double y, int count) {
        int slot = HomeLayout.slotAt(x, y, grid.left(), grid.top(), grid.pitch(), grid.rowHeight(),
                grid.columns(), grid.rows());
        return HomeLayout.dropIndex(page, slot, grid.pageSize(), count);
    }

    private String hitTile(List<String> tiles, double x, double y) {
        int slot = grid.hit(x, y);
        int index = page * grid.pageSize() + slot;
        return slot >= 0 && index < tiles.size() ? tiles.get(index) : null;
    }

    private boolean clickPageControl(double x, double y, int count) {
        if (HomeLayout.pageCount(count, grid.pageSize()) <= 1) return false;
        int center = grid.left() + grid.width() / 2;
        if (!contains(x, y, center - 34, pageControlY() - 1, 68, font.lineHeight + 2)) return false;
        if (x < center - 12) stepPage(-1);
        if (x > center + 12) stepPage(1);
        return true;
    }

    private int pageControlY() {
        return picker ? grid.footerY() - 10 : grid.footerY() + 2;
    }

    private boolean withinRows(double x, double y) {
        return grid.containsPanel(x, y)
                && contains(x, y, grid.left() - 6, grid.top(), grid.width() + 12, grid.rows() * grid.rowHeight());
    }

    private boolean onFooter(double x, double y) {
        return contains(x, y, grid.controlsLeft(), grid.footerY(), grid.controlsWidth(), FOOTER_HEIGHT);
    }

    private boolean onTitle(double x, double y) {
        return contains(x, y, grid.titleLeft(), grid.titleY() - 3, grid.titleWidth(), 14);
    }

    private boolean onLeft(double x) {
        return x < grid.left() + grid.width() / 2.0;
    }

    private static boolean contains(double x, double y, int left, int top, int w, int h) {
        return x >= left && x < left + w && y >= top && y < top + h;
    }

    private static String label(String key) {
        return Component.translatable(key).getString();
    }
}
