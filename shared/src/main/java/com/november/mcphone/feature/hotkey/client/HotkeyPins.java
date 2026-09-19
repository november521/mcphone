package com.november.mcphone.feature.hotkey.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.AtomicWrite;
import net.minecraft.client.KeyMapping;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「遥控器」里被玩家置顶的那几条 —— 存客户端全局，一份。
 *
 * <h2>为什么按名字存，而不是按 {@link KeyMapping} 对象</h2>
 *
 * 对象活不过一次重启：{@code KeyMapping} 是启动时由各模组 {@code new} 出来的，
 * 每次启动都是新的一批。所以要存的是一个<b>名字</b>，而 {@code KeyMapping.getName()}
 * 正好是注册时那个<b>唯一的翻译键</b>（如 {@code key.mekanism.armor_mode}）——
 * 它同时也是原版 {@code ALL} 那张表的键，模组挪不动、玩家改键也改不到它。
 * 它就是这条键位的身份。
 *
 * <h2>为什么是客户端全局，不是按存档</h2>
 *
 * 键位本来就是全客户端一份（它们在 {@code options.txt} 里，与存档无关），
 * 置顶跟着键位走才一致。按存档存会得到"换个世界置顶的键位就没了、
 * 而那个键位本身还在"这种自相矛盾的状态。
 *
 * <h2>为什么用 {@link AtomicWrite}</h2>
 *
 * 与主屏装了哪些 App、书架、阅读进度同一条理由：直写目标文件时，打开的那一刻
 * 文件就被截成 0 字节，写到一半崩了留下的就是一份截断的 JSON，而读的那侧一律
 * 按空处理 —— 于是丢的不是一条置顶，是整份。理由的全文在 {@code AtomicWrite} 的类注释里。
 */
public final class HotkeyPins {

    private HotkeyPins() {}

    private static final Path FILE = Path.of("config/mcphone/hotkey-pins.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 存盘用的形状。用对象包一层而不是裸数组：以后要加字段（比如"置顶顺序"以外的偏好）不用改文件格式 */
    private static final class State {
        List<String> pinned;
    }

    /**
     * 用 LinkedHashSet：只为让写出去的文件稳定（同样的置顶集合每次存出来一个样），
     * <b>不是</b>因为置顶区按它排序 —— 列表那边是按显示名重排的（见 {@code HotkeyPage.rebuild}）。
     * 用无序集合的话，同一个状态每次启动会写出不同的文件，diff 里就全是噪声。
     */
    private static final LinkedHashSet<String> PINNED = new LinkedHashSet<>();

    private static boolean loaded;

    /** 这个键位被置顶了吗 */
    public static boolean isPinned(KeyMapping mapping) {
        if (mapping == null) return false;
        ensureLoaded();
        return PINNED.contains(mapping.getName());
    }

    /** 置顶／取消置顶。改完立刻落盘 —— 玩家刚做的那一下不该等关机才生效 */
    public static void toggle(KeyMapping mapping) {
        if (mapping == null) return;
        ensureLoaded();
        String name = mapping.getName();
        if (!PINNED.remove(name)) PINNED.add(name);
        save();
    }

    /**
     * 读一次盘。
     *
     * <p>读不出来（文件不在、半份、手改坏了）就当没置顶过，并留一行日志：
     * 置顶是个便利功能，为它把整个 App 打不开不值当。丢掉的那些会在下一次
     * 存盘时从文件里消失。
     */
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(FILE)) return;

        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            State state = GSON.fromJson(r, new TypeToken<State>() {}.getType());
            if (state == null || state.pinned == null) return;
            for (String name : state.pinned) {
                if (name != null && !name.isBlank()) PINNED.add(name);
            }
        } catch (Exception e) {
            MCphone.LOGGER.warn("[MCphone] 快捷键置顶记录读不出来，按没置顶处理：{}", e.toString());
        }
    }

    private static void save() {
        State state = new State();
        state.pinned = List.copyOf(PINNED);
        try {
            AtomicWrite.replace(FILE, w -> GSON.toJson(state, w));
        } catch (IOException e) {
            // 存不下就这一次白置顶了，但界面已经变了；不抛给界面，只留日志
            MCphone.LOGGER.warn("[MCphone] 快捷键置顶记录写不进去：{}", e.toString());
        }
    }
}
