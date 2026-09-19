package com.november.mcphone.feature.hotkey.client;

/**
 * 一个键位<b>自己声明</b>的生效场景 —— 列表里那个小标签，同时也是「要不要先收起手机」的依据。
 *
 * <h2>它从哪来</h2>
 *
 * Forge 与 NeoForge 都给每个 {@code KeyMapping} 配了一个「冲突上下文」
 * （{@code IKeyConflictContext}），键位作者用它说明"这个键该在哪儿管事"。
 * 本类把那个声明译成玩家看得懂的两三个字。
 *
 * <p>取值那一步在各目标的 {@code HotkeyBackend.contextOf} 里：原版没有这个概念
 * （Fabric 上根本没有），所以本类不碰任何加载器 API，共用侧只需要这几个值。
 *
 * <h2>为什么它还能决定"先收手机"</h2>
 *
 * 声明了只在世界里生效的键位，等于它自己说了"界面开着时不算数"——
 * {@code KeyConflictContext.IN_GAME.isActive()} 的定义就是 {@code mc.screen == null}。
 * 而手机界面<b>是</b>一个界面，所以对它们而言，在手机开着时注入的那一次没人会取。
 * 与其"先注入一次、隔两拍发现没人要、再收起手机重来"，不如直接收起手机再注入：
 * 玩家看到的是一次到位，而不是"点一下没反应、再点一下才行"。
 *
 * <h2>{@link #CUSTOM} 为什么不猜</h2>
 *
 * {@code IKeyConflictContext} 是<b>接口</b>，模组完全可以自己实现一个（比如"只在骑乘时生效"）。
 * 那种上下文是什么意思我们不知道，就不冒充知道：如实标成"其它"，触发那边照旧走
 * 「先在界面里试一次、没人取再收手机」那条通用路（也就是 {@link #closePhoneFirst()} 为 false）。
 *
 * <h2>它只是「按一下」的一半</h2>
 *
 * 在 Forge 系的目标上，键位作者常把条件写成一整句
 * {@code consumeClick() && isConflictContextAndModifierActive()}：本枚举管的是那句话里的
 * 「场景」，另一半是<b>修饰键</b>（{@code KeyModifier}，也就是 {@code Ctrl + T} 里的那个 Ctrl）。
 * 修饰键不在本枚举里，不是因为它不重要，而是因为它不是"玩家该看见的标签"那种东西 ——
 * 它要解决的是"触发的时候把那个要求让开"（模组那边看到的就是"没人拦着"，不是我们替玩家
 * 按住了 Ctrl；本功能不合成任何 GLFW 输入），落在 {@code HotkeyBackend} 的
 * {@code gatesOnModifier / suspendModifier / restoreModifier} 与 {@link KeyTrigger} 那边
 * （两处的类注释都有一节叫「第三条」／「「按下」的完整契约」）。分开之后，这一页上的标签
 * 仍然只有四个值，而且每个值都真的有话可说；神化那个开「世界层级选择」的键位
 * （Ctrl + T）就是被这一半挡住的。
 */
public enum HotkeyContext {

    /** 只在世界里生效（Forge / NeoForge 的 {@code IN_GAME}） */
    WORLD("mcphone.hotkey.ctx.world"),

    /** 只在界面里生效（{@code GUI}） */
    GUI("mcphone.hotkey.ctx.gui"),

    /** 不限（{@code UNIVERSAL}，也是原版键位与"没声明过"的默认值） */
    ANY("mcphone.hotkey.ctx.any"),

    /** 模组自定义的上下文，我们不知道它的意思 */
    CUSTOM("mcphone.hotkey.ctx.custom");

    private final String labelKey;

    HotkeyContext(String labelKey) {
        this.labelKey = labelKey;
    }

    /** 那一行右边小标签的翻译键。翻译在 {@code HotkeyPage} 里做完存进行对象 */
    public String labelKey() {
        return labelKey;
    }

    /**
     * 触发这个键位时，要不要<b>先收起手机</b>。
     *
     * <p>只有 {@link #WORLD} 为 true，理由见类注释。其余几种都先就地注入一次：
     * 就算那一次真的没人取，{@link KeyTrigger} 还有一条经验性的退路
     * （隔一拍读回计数器、发现没动过再收手机重来），所以这里保守一点不会漏掉谁，
     * 只是那类键位要多花两拍而已。
     */
    public boolean closePhoneFirst() {
        return this == WORLD;
    }
}
