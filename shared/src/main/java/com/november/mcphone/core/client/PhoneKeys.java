package com.november.mcphone.core.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * MCphone 有哪些按键 —— <b>一份声明，所有目标共用</b>。
 *
 * <h2>为什么要有这个类</h2>
 *
 * 从前每个加载器层各有一份 {@code MCphoneKeyBindings}，键在里面各声明一遍、各注册一遍。
 * 那意味着<b>加一个键要改 N 处</b>，而漏掉其中一处不会报错：那个目标上按键设置里少一行、
 * 依赖它的功能静默失灵。这不是假设——本仓的 {@code READER_PREV}/{@code READER_NEXT}
 * 就只进了 NeoForge 那一份，Forge 那份至今没有，两侧漂了两个字段而没有任何东西响过一声。
 *
 * 现在键的<b>身份</b>（翻译键、默认键位、分类）只写在这里，加载器层照 {@link #ALL} 循环
 * 建 {@link KeyMapping} 并注册。加一个键＝在这里加一行，结构上不可能只加给某一个加载器。
 *
 * <h2>为什么身份能共用，构造不能</h2>
 *
 * {@link KeyMapping} 是原版类，两个加载器、两档版本上都在。而"这个键只在游戏里生效"
 * （KeyConflictContext）与"往哪个总线注册"是加载器自己的东西：
 * NeoForge 是 {@code net.neoforged.neoforge.client.settings.KeyConflictContext} 与
 * {@code RegisterKeyMappingsEvent}，Forge 是 {@code net.minecraftforge.*} 下的同名物。
 * 所以那两件留在 {@code layers/loader/<加载器>/} 下，这里只回答"有哪些键、叫什么、默认是哪个"。
 *
 * <h2>默认键位一律选原版未占用的</h2>
 *
 * 玩家可以在原版「选项 → 按键设置」里改键，分类为 MCphone。代码里一律通过 {@link Key}
 * 判断按下，不要写死键码 —— 写死了玩家改键之后就失效。
 */
public final class PhoneKeys {

    /** 按键设置界面里的分类名 */
    public static final String CATEGORY = "key.categories.mcphone";

    /**
     * 一个键的身份，以及它在本目标上对应的那个 {@link KeyMapping}。
     *
     * 后者由加载器层在注册时 {@link #bind} 进来 —— 这个类不碰任何加载器 API，
     * 它只是"这个键是什么"与"这个键现在绑到哪个 KeyMapping 上"之间的那一格。
     */
    public static final class Key {

        private final String id;
        private final int defaultCode;

        /** 由加载器层在注册时填。没填过就是这个目标漏了注册，见 {@link #mapping()} */
        private KeyMapping mapping;

        private Key(String id, int defaultCode) {
            this.id = id;
            this.defaultCode = defaultCode;
        }

        /** 翻译键，同时也是这个键的唯一 id */
        public String id() {
            return id;
        }

        /** 默认键位（GLFW 键码） */
        public int defaultCode() {
            return defaultCode;
        }

        /** 加载器层建好 KeyMapping 之后交回来 */
        public void bind(KeyMapping value) {
            this.mapping = value;
        }

        /**
         * 这个键对应的 KeyMapping。
         *
         * <b>没绑过就抛</b>，不返回 null、也不静默当成"没按下"：那意味着这个目标的加载器层
         * 没有照 {@link #ALL} 注册。让它当场炸出来，比让某个功能在那个目标上永远不响强——
         * 后者正是这个类要消掉的那种坏。
         */
        public KeyMapping mapping() {
            if (mapping == null) {
                throw new IllegalStateException(
                        "按键 " + id + " 没有被注册。这个目标的 MCphoneKeyBindings "
                                + "没有照 PhoneKeys.ALL 建 KeyMapping —— 那份循环不能漏。");
            }
            return mapping;
        }

        /** 距上次问之后按下过没有（原版的边沿队列，问一次消一次） */
        public boolean consumeClick() {
            return mapping().consumeClick();
        }

        /** 玩家实际绑的是哪个键，用来在界面上写"按 X 做什么" */
        public Component translatedName() {
            return mapping().getTranslatedKeyMessage();
        }
    }

    /** 拍照 */
    public static final Key CAMERA_SHUTTER = new Key("key.mcphone.camera_shutter", GLFW.GLFW_KEY_V);

    /** 退出相机 */
    public static final Key CAMERA_EXIT = new Key("key.mcphone.camera_exit", GLFW.GLFW_KEY_X);

    /**
     * 开机。手机放在背包或饰品槽里也能直接打开，不必先切到手上。
     * 这个键与 Curios 无关：没装任何附属模组时照样从背包里把手机翻出来。
     */
    public static final Key OPEN_PHONE = new Key("key.mcphone.open_phone", GLFW.GLFW_KEY_H);

    /**
     * 挂在 HUD 上看书时往前翻一页。
     *
     * <b>为什么非有这两个键不可</b>：手机一旦成了 {@code mc.screen}，人就走不动路了
     * （原版界面一开就不吃移动键）。而"看小说"最想要的恰恰是边走边看、挂机时看——那种时候
     * 手机挂在副手 HUD 上，而 HUD 上那副面孔<b>收不到任何输入</b>（它不是 mc.screen）。
     * 没有这两个键，边走边看就等于"每翻一页停下来一次"。
     *
     * 手机界面开着时这两个键不管事——那时候方向键、翻页键、点屏幕左右两半都在，
     * 见 {@code TxtReaderPage.keyPressed}。
     */
    public static final Key READER_PREV = new Key("key.mcphone.reader_prev", GLFW.GLFW_KEY_PAGE_UP);

    /** 挂在 HUD 上看书时往后翻一页，理由同 {@link #READER_PREV} */
    public static final Key READER_NEXT = new Key("key.mcphone.reader_next", GLFW.GLFW_KEY_PAGE_DOWN);

    /**
     * 按一下唤出鼠标操作副手 HUD 上那部手机，再按一下收起。
     *
     * <b>这个键不能用 consumeClick / isDown 判断。</b>它要答的是"此刻按着没有"，而按下的
     * 那一刻 {@code Minecraft.setScreen} 会调 {@code KeyMapping.releaseAll()} 把所有
     * KeyMapping 的按下状态清掉——手机界面一开 isDown() 立刻变 false，而 {@code PhoneHud}
     * 认的是"按下去的那一沿"，一个永远回不到按下状态的键切不动任何东西。
     *
     * 所以 {@code PhoneHud} 直接查物理按键（{@code InputConstants.isKeyDown}）。留着
     * KeyMapping 是为了让玩家能在原版按键设置里改键，并让冲突提示照常工作。
     */
    public static final Key HUD_INTERACT = new Key("key.mcphone.hud_interact", GLFW.GLFW_KEY_LEFT_ALT);

    /**
     * 唤出 / 收起副手 HUD 上那部手机。
     *
     * 为什么"放进副手就自动亮"之外还要这一个：手机也可以挂在 Curios 的饰品槽里，那时候
     * 副手是空的（挂饰品栏的意思本来就是"腾出两只手"），自动那条规矩够不着它。这个键让手机
     * 收在饰品栏、背包、甚至主手上时同样能把 HUD 叫出来。
     *
     * 它<b>顶掉</b>自动那条规矩，而不是与之并列——手机在副手上时按它也要能收起来，
     * 否则会出现"按了没反应"。
     */
    public static final Key HUD_TOGGLE = new Key("key.mcphone.hud_toggle", GLFW.GLFW_KEY_G);

    /**
     * 全部的键，加载器层照这个列表建与注册。
     *
     * <b>顺序不影响玩家看到的排列</b>：原版的按键设置界面自己按「分类 + 译名」排序，
     * 注册顺序从来没进过那条路。往这个列表里加、或者调整顺序，都不会动到界面。
     */
    public static final List<Key> ALL = List.of(
            CAMERA_SHUTTER,
            CAMERA_EXIT,
            OPEN_PHONE,
            READER_PREV,
            READER_NEXT,
            HUD_INTERACT,
            HUD_TOGGLE);

    private PhoneKeys() {}
}
