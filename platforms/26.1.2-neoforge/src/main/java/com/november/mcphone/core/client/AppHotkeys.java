package com.november.mcphone.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.november.mcphone.MCphone;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.settings.KeyModifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 每个 App 一个快捷键 —— 在世界里按一下，直接开机并进这个 App。支持组合键。
 *
 * 绑在哪里、由谁改，见「设置 → App 管理器 → 某个 App」那一页；这里只管
 * 「哪个组合是哪个 App 的」这张表本身，以及它怎么落盘、怎么判冲突。
 *
 * 为什么不做成 KeyMapping
 *
 * 原版的按键设置界面要求键位在【游戏启动时】就全部注册好（
 * {@code RegisterKeyMappingsEvent} 在客户端 setup 阶段发一次，此后原版
 * 不认新的）。而 App 的名单是运行期才知道的：SPI 扫描要等模组加载完，
 * 玩家还能在商店里装、在管理器里卸，附属模组随时可以再添一个。为一份
 * 会变的名单去注册一批固定的 KeyMapping，只有两条路——要么预留 N 个
 * "App 快捷键 1..N" 这种玩家看不懂的空位，要么每次名单变了就骗原版重扫。
 * 两条都比自己认一下键码难看得多。
 *
 * 所以这张表是我们自己的：存在客户端配置里（见 {@link ClientConfig}），
 * 按下时由 {@link AppHotkeyHandler} 认。代价是它不出现在原版的「按键设置」
 * 界面里，因此 {@link #conflictingMapping} 得替玩家把冲突【说出来】——玩家
 * 没法在原版界面里发现"这个键被手机占了"。说出来之后要不要照绑，是他的事。
 *
 * 修饰键为什么借 NeoForge 的 KeyModifier
 *
 * 它把三件麻烦事都办好了：Mac 上 Ctrl 实际是 Command（{@code matches} 与
 * {@code isActive} 都按 {@code Minecraft.ON_OSX} 分岔）、"这个键本身是不是
 * 修饰键"、以及"Ctrl + K"这个名字的本地化（{@code getCombinedName}，翻译由
 * NeoForge 自己带）。自己写一套的话，这三件每一件都得单独踩一遍。
 *
 * 只是它一条绑定只允许【一个】修饰键，而我们允许 Ctrl+Shift+K 这种，所以
 * 组合存成一个 Set，判冲突时再按 KeyModifier 的语义换算回去。
 */
public final class AppHotkeys {

    private AppHotkeys() {}

    /**
     * 一条绑定：一个主键 + 按住的那几个修饰键。
     *
     * modifiers 里不会有 {@link KeyModifier#NONE}——"没有修饰键"就是空集合，
     * 两种表示法并存的话，判等和序列化都要各写两遍。
     */
    public record Binding(InputConstants.Key key, Set<KeyModifier> modifiers) {

        /** 显示用的包裹顺序。Ctrl 包在最外层，念出来就是"Ctrl + Shift + Alt + K" */
        private static final KeyModifier[] DISPLAY_ORDER = {
                KeyModifier.ALT, KeyModifier.SHIFT, KeyModifier.CONTROL
        };

        public Binding {
            modifiers = normalize(modifiers);
        }

        public static Binding of(InputConstants.Key key, Collection<KeyModifier> modifiers) {
            return new Binding(key, normalize(modifiers));
        }

        private static Set<KeyModifier> normalize(Collection<KeyModifier> in) {
            EnumSet<KeyModifier> out = EnumSet.noneOf(KeyModifier.class);
            if (in != null) {
                for (KeyModifier m : in) {
                    if (m != null && m != KeyModifier.NONE) out.add(m);
                }
            }
            return out;
        }

        /** 界面上写的那串，如 "Ctrl + K"。翻译由 NeoForge 带，跟着玩家的语言走 */
        public Component displayName() {
            Supplier<Component> name = key::getDisplayName;
            for (KeyModifier m : DISPLAY_ORDER) {
                if (!modifiers.contains(m)) continue;
                Supplier<Component> inner = name;   // 抓住上一层，不能让 lambda 引用还在变的 name
                name = () -> m.getCombinedName(key, inner);
            }
            return name.get();
        }

        /** 配置里的那一段，如 "CONTROL+key.keyboard.k"。修饰键在前，主键永远在最后 */
        public String serialize() {
            StringBuilder sb = new StringBuilder();
            for (KeyModifier m : KeyModifier.MODIFIER_VALUES) {     // 固定顺序，文件才不会无谓地变
                if (modifiers.contains(m)) sb.append(m.name()).append('+');
            }
            return sb.append(key.getName()).toString();
        }

        /**
         * 反过来读一段。读不出来就是 null，由调用方决定怎么办。
         *
         * 主键名里不会出现加号（原版那套是 key.keyboard.xxx / scancode.nn），
         * 所以按加号切开、最后一段当主键就够。
         */
        public static Binding parse(String text) {
            String[] parts = text.split("\\+");
            if (parts.length == 0) return null;

            EnumSet<KeyModifier> mods = EnumSet.noneOf(KeyModifier.class);
            for (int i = 0; i < parts.length - 1; i++) {
                KeyModifier m = KeyModifier.valueFromString(parts[i].trim());
                if (m == KeyModifier.NONE) return null;     // 认不出来的修饰键，整条不要
                mods.add(m);
            }

            try {
                InputConstants.Key key = InputConstants.getKey(parts[parts.length - 1].trim());
                if (key.equals(InputConstants.UNKNOWN)) return null;
                return new Binding(key, mods);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** appId → 绑定。用 LinkedHashMap：写回配置时顺序稳定，不然每次存盘文件都在无谓地变 */
    private static final Map<ResourceLocation, Binding> BOUND = new LinkedHashMap<>();

    /** 配置里一条的写法：{@code <appId>=<绑定>}。appId 里不可能有等号，切一刀就够 */
    private static final char SEP = '=';

    //  查

    /** 这个 App 绑的组合，没绑就是 null */
    public static Binding get(ResourceLocation appId) {
        return BOUND.get(appId);
    }

    /** 这个组合绑给了哪个 App，没有就是 null。主键与修饰键都要一模一样 */
    public static ResourceLocation appFor(Binding binding) {
        for (Map.Entry<ResourceLocation, Binding> e : BOUND.entrySet()) {
            if (e.getValue().equals(binding)) return e.getKey();
        }
        return null;
    }

    /** 此刻按住的修饰键。Mac 上的 Command 由 KeyModifier 自己换算成 CONTROL */
    public static Set<KeyModifier> activeModifiers() {
        EnumSet<KeyModifier> out = EnumSet.noneOf(KeyModifier.class);
        out.addAll(KeyModifier.getActiveModifiers());
        out.remove(KeyModifier.NONE);
        return out;
    }

    /**
     * 这个组合在原版的按键设置里已经有主了吗，有就把那一条还回来。
     *
     * 包括别的模组的键位：{@code options.keyMappings} 装的是所有注册过的
     * KeyMapping，不分是谁的。我们自己那三个（开机、拍照、退出相机）也在里面。
     *
     * 【加了修饰键不等于就不冲突了】，这一点反直觉，所以按 KeyModifier 真正的
     * 语义来判：
     *
     * - 对方没有修饰键（NONE）：它判活的写法是"上下文与 IN_GAME 冲突就一律算活"，
     *   而原版自己的键位全是 UNIVERSAL 上下文。也就是说按住 Ctrl 再按 E 照样会开
     *   背包——所以 Ctrl+E 与背包键【算冲突】
     * - 对方有修饰键：它只看自己那一个按住没有，多按了别的不管。所以我们的组合里
     *   只要含着它那一个就算冲突（我们的 Ctrl+Shift+K 会连它的 Ctrl+K 一起响）
     */
    public static KeyMapping conflictingMapping(Binding binding) {
        List<KeyMapping> all = conflictingMappings(binding);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 撞上的【所有】键位，不只是第一条。
     *
     * 界面上说一条就够（说全了那一行也放不下），但按下去要处理的是全部：同一个键
     * 上挂着原版一条、某个模组再挂一条，是很常见的事，只倒掉其中一条，剩下那条
     * 照样会在关掉手机之后补触发一次。
     */
    public static List<KeyMapping> conflictingMappings(Binding binding) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.options.keyMappings == null) return List.of();

        List<KeyMapping> out = new ArrayList<>(1);
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (!binding.key().equals(mapping.getKey())) continue;
            KeyModifier m = mapping.getKeyModifier();
            if (m == KeyModifier.NONE || binding.modifiers().contains(m)) out.add(mapping);
        }
        return out;
    }

    //  改

    /**
     * 绑上去并存盘。同一个 App 再绑一次就是换键。
     *
     * 不在这里判冲突：判冲突要给玩家说清楚"被谁占了"、还要让他能坚持照绑，
     * 那是界面的事。这里只保证一个组合不会同时挂在两个 App 上——真出现了以
     * 后来的为准，因为玩家刚按的那一下是他现在的意思。
     */
    public static void bind(ResourceLocation appId, Binding binding) {
        if (binding == null || binding.key().equals(InputConstants.UNKNOWN)) return;
        BOUND.entrySet().removeIf(e -> e.getValue().equals(binding));
        BOUND.put(appId, binding);
        // 留一行日志：绑键是玩家一次一次做的事，不会刷屏；而"按了没反应"这类问题，
        // 有没有这一行就是"没绑上"与"绑上了但按下去没认出来"的分界线
        MCphone.LOGGER.info("[MCphone] 快捷键：{} 绑到 {}", appId, binding.serialize());
        ClientConfig.saveAppHotkeys(serialize());
    }

    /** 解绑并存盘。本来就没绑就什么都不做，省一次无谓的写盘 */
    public static void clear(ResourceLocation appId) {
        if (BOUND.remove(appId) == null) return;
        MCphone.LOGGER.info("[MCphone] 快捷键：{} 解绑", appId);
        ClientConfig.saveAppHotkeys(serialize());
    }

    //  配置 ←→ 这张表

    /**
     * 配置读进来（或被改了）时把整张表换掉。
     *
     * 认不出来的条目【丢掉并留一行日志】，不让整份配置作废：这份配置里还有
     * 字体颜色和音量，为一条手改坏了的快捷键把它们一起退回默认值不值当。
     * 丢掉的那条会在下一次存盘时从文件里消失。
     */
    static void load(List<? extends String> entries) {
        BOUND.clear();
        if (entries == null) return;

        for (String entry : entries) {
            int sep = entry.indexOf(SEP);
            if (sep <= 0 || sep == entry.length() - 1) {
                MCphone.LOGGER.warn("[MCphone] 快捷键配置里这一条不认识，已忽略: {}", entry);
                continue;
            }

            ResourceLocation appId = ResourceLocation.tryParse(entry.substring(0, sep).trim());
            if (appId == null) {
                MCphone.LOGGER.warn("[MCphone] 快捷键配置里的 App id 不合法，已忽略: {}", entry);
                continue;
            }

            // 键名是原版那套 "key.keyboard.k"，修饰键是 CONTROL / SHIFT / ALT，
            // 手改配置时最容易写错的就是这两段
            Binding binding = Binding.parse(entry.substring(sep + 1).trim());
            if (binding == null) {
                MCphone.LOGGER.warn("[MCphone] 快捷键配置里的键名不认识，已忽略: {}", entry);
                continue;
            }

            BOUND.entrySet().removeIf(e -> e.getValue().equals(binding));   // 手改出的重复
            BOUND.put(appId, binding);
        }
    }

    /** 写回配置用的那一串。顺序跟着 BOUND，稳定 */
    static List<String> serialize() {
        List<String> out = new ArrayList<>(BOUND.size());
        for (Map.Entry<ResourceLocation, Binding> e : BOUND.entrySet()) {
            out.add(e.getKey() + String.valueOf(SEP) + e.getValue().serialize());
        }
        return out;
    }
}
