package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.feature.reader.BookRef;
import com.november.mcphone.platform.ModPresence;
import com.november.mcphone.platform.ResourceIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Discovers books from Modonomicon's synchronized catalog and uses its own reader. */
final class ModonomiconSource implements BookSource {
    private static final String MOD_ID = "modonomicon";
    private static final String PACKAGE = "com.klikli_dev.modonomicon.";
    private static final ResourceLocation SCRIPTURA_VITAE = ResourceIds.create("neovitae", "guide");

    private List<BookRef> books = List.of();
    private final Map<ResourceLocation, ItemStack> icons = new HashMap<>();
    private boolean resolved;
    private Api api;
    private int loggedCount = -1;
    private Screen returnScreen;
    private LocalPlayer readingPlayer;

    @Override
    public String id() {
        return MOD_ID;
    }

    @Override
    public boolean isAvailable() {
        return ModPresence.isLoaded(MOD_ID);
    }

    @Override
    public List<BookRef> list() {
        return books;
    }

    @Override
    public List<String> searchAliases(BookRef book) {
        return SCRIPTURA_VITAE.equals(book.bookId()) ? List.of("命血宝典", "命血手札", "Scriptura Vitae") : List.of();
    }

    @Override
    public void refresh() {
        books = List.of();
        icons.clear();
        Api access = resolve();
        if (access == null) return;

        var entries = new ArrayList<BookRef>();
        try {
            for (var entry : access.books().entrySet()) {
                if (!(entry.getKey() instanceof ResourceLocation id) || entry.getValue() == null) continue;
                try {
                    String name = (String) access.name().invoke(entry.getValue());
                    Component title = name == null || name.isBlank()
                            ? Component.literal(id.getPath()) : Component.translatable(name);
                    entries.add(new BookRef(MOD_ID, id, title, null, BookSource.modName(id.getNamespace())));
                    identifyIcon(access, id, entry.getValue());
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    MCphone.LOGGER.warn("[MCphone] 读取 Modonomicon 手册 {} 失败", id, failure);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            MCphone.LOGGER.warn("[MCphone] 读取 Modonomicon 书目失败", failure);
        }

        entries.sort(Comparator.comparing(BookRef::owner)
                .thenComparing(book -> book.title().getString())
                .thenComparing(book -> book.bookId().toString()));
        books = List.copyOf(entries);
        if (books.size() != loggedCount) {
            loggedCount = books.size();
            MCphone.LOGGER.info("[MCphone] 书城扫到 {} 本 Modonomicon 手册", loggedCount);
        }
    }

    @Override
    public void open(BookRef book) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        Api access = resolve();
        if (access == null) return;

        Screen previous = client.screen;
        try {
            if (!access.books().containsKey(book.bookId())) {
                MCphone.LOGGER.warn("[MCphone] Modonomicon 书目中已没有手册 {}", book.bookId());
                return;
            }
            Object address = access.address() == null
                    ? book.bookId() : access.address().invoke(null, book.bookId());
            access.open().invoke(access.gui().invoke(null), address);
            if (previous != null && client.screen != previous && isBookScreen(client.screen)) {
                returnScreen = previous;
                readingPlayer = client.player;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            MCphone.LOGGER.warn("[MCphone] 打开 Modonomicon 手册 {} 失败", book.bookId(), failure);
        }
    }

    @Override
    public boolean renderIcon(GuiGraphics graphics, BookRef book, int x, int y, int size) {
        ItemStack icon = icons.get(book.bookId());
        return icon != null && GuiUtil.drawItemIcon(graphics, icon, x, y, size);
    }

    void tickReturn() {
        if (returnScreen == null) return;
        Minecraft client = Minecraft.getInstance();
        boolean samePlayer = client.player == readingPlayer && readingPlayer.isAlive();
        if (samePlayer && isBookScreen(client.screen)) return;

        Screen previous = returnScreen;
        returnScreen = null;
        readingPlayer = null;
        if (samePlayer && client.screen == null) client.setScreen(previous);
    }

    private static boolean isBookScreen(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith(PACKAGE + "client.gui.");
    }

    private void identifyIcon(Api access, ResourceLocation id, Object book) {
        try {
            if (access.icon() == null) return;
            Object result = access.icon().invoke(book);
            if (result instanceof ItemStack stack && !stack.isEmpty()) {
                icons.put(id, stack);
            } else if (result instanceof ResourceLocation itemId) {
                var item = BuiltInRegistries.ITEM.get(itemId);
                if (item != Items.AIR) icons.put(id, new ItemStack(item));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A missing cover uses the bookshelf's default icon.
        }
    }

    private Api resolve() {
        if (resolved) return api;
        resolved = true;
        try {
            Class<?> data = Class.forName(PACKAGE + "data.BookDataManager");
            Class<?> book = Class.forName(PACKAGE + "book.Book");
            Class<?> gui = Class.forName(PACKAGE + "client.gui.BookGuiManager");
            Method getData = data.getMethod("get");
            Method getBooks = data.getMethod("getBooks");
            Method getName = book.getMethod("getName");
            Method getGui = gui.getMethod("get");

            Method address;
            Method open;
            try {
                Class<?> bookAddress = Class.forName(PACKAGE + "client.gui.book.BookAddress");
                address = bookAddress.getMethod("defaultFor", ResourceLocation.class);
                open = gui.getMethod("openBook", bookAddress);
            } catch (ClassNotFoundException | NoSuchMethodException legacyApi) {
                address = null;
                open = gui.getMethod("openBook", ResourceLocation.class);
            }
            Method icon = findIconMethod(book);
            api = new Api(getData, getBooks, getName, getGui, open, address, icon);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            MCphone.LOGGER.warn("[MCphone] Modonomicon 书目或开书接口无法解析", failure);
        }
        return api;
    }

    private static Method findIconMethod(Class<?> book) {
        for (String name : List.of("getBookItem", "getCustomBookItem")) {
            try {
                return book.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Try the other supported Modonomicon API.
            }
        }
        return null;
    }

    private record Api(Method data, Method catalog, Method name, Method gui, Method open, Method address, Method icon) {
        Map<?, ?> books() throws ReflectiveOperationException {
            return (Map<?, ?>) catalog.invoke(data.invoke(null));
        }
    }
}
