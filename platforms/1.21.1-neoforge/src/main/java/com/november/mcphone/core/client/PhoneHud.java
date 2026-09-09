package com.november.mcphone.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.PhoneItem;
import com.november.mcphone.core.PhoneLocation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;

/**
 * 手机放进副手就挂到画面上 —— 一直亮着，停在哪一页就显示哪一页。
 *
 * <h2>只有一部手机</h2>
 *
 * 这里握着一个 {@link PhoneScreen} 实例，从手机进副手起活到它离开副手为止。挂着的时候
 * 由本类逐帧画出来；按住 Alt 时把【同一个实例】交给 Minecraft 当 mc.screen，于是鼠标、
 * 滚轮、键盘、输入法、拖文件全部照原样走原版那条路，一行输入代码都不用新写。
 *
 * 同一个实例是这件事的关键。做成"HUD 一份、界面另一份"的话，两份各记各的页码与滚动位置，
 * 玩家在 Alt 里翻到第三页、松手一看 HUD 还停在第一页——那不是手机，是两块屏幕。
 *
 * <h2>两条唤出的路：副手自动，G 手动</h2>
 *
 * 自动那条是"手机放进副手就亮"。但手机也可以挂在 Curios 的饰品槽里，那时候副手是空的
 * ——挂饰品栏的意思本来就是"腾出两只手"——自动那条规矩根本够不着它。所以另给一个键
 * （{@link MCphoneKeyBindings#HUD_TOGGLE}，默认 G）：手机收在饰品栏、背包、主手上时
 * 同样叫得出来。
 *
 * 两条不是并列的，手动那条【顶掉】自动那条，见 {@link Override}。
 *
 * <h2>Alt 是开关，不是按住</h2>
 *
 * 按一下唤出鼠标，再按一下收起。做成按住不放的话，凡是需要两只手的操作全都做不了——
 * 拖手机挪位置要按住左键拖，打一条消息要按几十个键，而那只手正压在 Alt 上。
 *
 * 退出的路不止一条，随便哪条都行：再按一下 Alt、按 ESC、点机身外面、按背包键、
 * 手机离开副手。一个进得去出不来的模式比没有这个模式更糟。
 *
 * 唤出鼠标之后除了正常操作手机，还能就地摆放它：拖边框或状态栏挪位置、Ctrl+滚轮改大小，
 * 两条都在 {@link PhoneScreen} 那边实现（{@code isOnHudHandle} 与 {@code mouseScrolled}）。
 *
 * <h2>为什么 Alt 非得开一个真的 Screen</h2>
 *
 * 想过只放开鼠标、自己接管点击。但原版 {@code KeyboardHandler.charTyped} 只把字符发给
 * mc.screen，{@code MouseHandler} 的点击、滚轮、拖动同样只发给 mc.screen。不开 Screen
 * 就意味着聊天、便签、书架搜索这些带输入框的页面在 HUD 上全都打不出字——而那恰恰是最想
 * 边走边用的几个。开一个 Screen 则什么都不缺。
 *
 * 代价是这段时间里人走不动（原版界面一开就不吃移动键）。这是可以接受的：唤出鼠标本来
 * 就是"停下来操作一会儿"的动作，再按一下 Alt 立刻恢复。
 *
 * <h2>Alt 为什么直接查物理按键</h2>
 *
 * 因为 {@link Minecraft#setScreen} 在开界面的同时会调 {@code KeyMapping.releaseAll()}。
 * 用 {@code KeyMapping.isDown()} 判断的话，界面开起来的那一刻它就变成 false——而本类靠
 * "按下去的那一沿"来切换开关，一个永远回不到按下状态的键切不动任何东西。
 * 所以问的是 GLFW："这个键此刻按着没有"，那个答案不受界面影响。键位仍然来自 {@link MCphoneKeyBindings#HUD_INTERACT}，玩家照样
 * 能在原版按键设置里改。
 *
 * <h2>它什么时候不画</h2>
 *
 * F1 隐藏 HUD 时不画——这一条得自己写：原版把 hideGui 的判断烘在它自己那些层的 lambda
 * 里，用 {@code registerAbove} 插进 {@link net.neoforged.neoforge.client.gui.GuiLayerManager}
 * 的新层【不带】这道保护。
 *
 * 有任何界面开着时也不画：要么是别的界面把手机顶掉了，要么手机自己就是那个界面
 * （按住 Alt，或按开机键全屏打开），后一种它会自己画自己，这里再画一遍就是画两次。
 */
public final class PhoneHud {

    private PhoneHud() {}

    /** 层的 id。插在原版战利品条之后 —— 玩法 HUD 之上，F3 与聊天框之下 */
    private static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "offhand_phone");

    /** 副手上那部一直开着的手机，null 表示此刻没挂 */
    private static PhoneScreen phone;

    /** 正按着 Alt —— 此刻 phone 就是 mc.screen，且仍画在 HUD 那个角上 */
    private static boolean interacting;

    /**
     * 上一 tick 两个键各自按着没有 —— 用来认"按下去的那一沿"。
     *
     * 只看"此刻按着"是不行的：按一下少说压住三四个 tick，每个 tick 都当成一次切换，
     * 手机会在这几十毫秒里开开关关好几轮，看着就是闪一下什么也没发生。
     */
    private static boolean interactKeyWasDown;

    private static boolean toggleKeyWasDown;

    /**
     * 玩家用 G 把自动那条规矩顶掉了没有。
     *
     * 为什么不能做成"手动开"与"自动开"取或：那样手机在副手上时按 G 就关不掉了——
     * 自动那条仍然说要显示。反过来只做成"手动关"也不行，饰品栏里的手机就永远叫不出来。
     * 所以它是个覆盖：说显示就显示，说不显示就不显示，什么都没说才轮到自动那条。
     *
     * <b>什么时候恢复自动</b>：自动那条的答案本身翻面的时候（手机进副手、离开副手）。
     * 不恢复的话会出现这种事——手机在饰品栏，按 G 叫出来又按 G 收起去，此后把手机
     * 放进副手，它却不亮了，因为那个"收起"还压着。玩家只会认为副手那个功能坏了。
     * 恒温器上的"临时调节"就是这么设计的：手动值一直有效，直到自动那侧的情况变了。
     */
    private enum Override { NONE, SHOW, HIDE }

    private static Override manual = Override.NONE;

    /** 上一 tick 自动那条的答案，用来发现它翻了面 */
    private static boolean lastAuto;

    //  注册

    /**
     * HUD 上现在挂着的那部，null ＝ 没挂。
     *
     * 给 {@link PhoneItemProperties} 判"手上这部亮不亮"用：挂在 HUD 上的那部屏幕是亮着的，
     * 哪怕玩家没按 Alt。
     */
    static PhoneScreen hudPhone() { return phone; }

    /** 由 MCphoneClient 构造函数挂到模组总线 */
    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, LAYER_ID, PhoneHud::render);
    }

    //  每 tick 决定挂不挂、要不要唤起鼠标

    /** 由 MCphoneClient 构造函数挂到游戏总线 */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        // 按沿判定要在所有提前返回之前做完，否则在那些分支里按下的一次会被吞掉，
        // 玩家会遇到"按了一下没反应，再按一下才开"
        boolean interactDown = keyDown(mc, MCphoneKeyBindings.HUD_INTERACT);
        boolean interactPressed = interactDown && !interactKeyWasDown;
        interactKeyWasDown = interactDown;

        boolean toggleDown = keyDown(mc, MCphoneKeyBindings.HUD_TOGGLE);
        boolean togglePressed = toggleDown && !toggleKeyWasDown;
        toggleKeyWasDown = toggleDown;

        if (togglePressed) onTogglePressed(mc);

        PhoneLocation target = resolveTarget(mc);
        if (target == null) {
            dismiss(mc);
            return;
        }

        if (phone == null) {
            phone = new PhoneScreen(target);
            phone.adoptByHud();
            syncSize(mc);
        }

        boolean isScreen = mc.screen == phone;

        // 全屏那副面孔正开着：人已经在正经操作手机了，Alt 在这儿没有意义
        if (isScreen && !phone.isHudMode()) return;

        // 刚从全屏退出来（ESC、点了机身外）：把它挪回 HUD 那个角上。
        //
        // 只在【什么界面都没开】时才翻。全屏那副面孔中途开出别的界面是正常操作——
        // 设置里点「位置」会开位置编辑器，退出来还该回到那一页。这时候要是把它翻回
        // HUD 模式，编辑器一关，全屏的手机就画到角落里去了
        if (!isScreen && !phone.isHudMode() && mc.screen == null) {
            phone.setHudMode(true);
        }

        if (interacting) {
            // 手机不再是当前界面了：点了机身外自己关的，或者被别的界面顶掉
            // （例如刚从书架点开一本书）。两种都算退出，手机退回 HUD 继续挂着
            if (!isScreen) {
                interacting = false;
            } else if (interactPressed) {
                stopInteracting(mc);
            }
            return;
        }

        if (!interactPressed) return;

        // 背包、聊天框开着时不抢。玩家正在那儿操作，凭空把手机拽到最上面只会打断他
        if (mc.screen != null) return;

        startInteracting(mc);
    }

    //  渲染

    private static void render(GuiGraphics g, DeltaTracker delta) {
        if (phone == null) return;

        Minecraft mc = Minecraft.getInstance();

        // 这一条得自己写，理由见类注释
        if (mc.options.hideGui) return;

        // 有界面开着就不画，理由见类注释
        if (mc.screen != null) return;

        syncSize(mc);

        // false ＝ 不算暂停时的那一份。手机挂着的时候游戏没暂停，两者一样，
        // 取跟着游戏时间走的那个更合语义（相机那条覆盖层同理）
        phone.renderAsHud(g, delta.getGameTimeDeltaPartialTick(false));
    }

    //  开关手机

    /**
     * 这一 tick 该挂哪一部手机，null 表示不该挂。
     *
     * 三步：自动那条怎么说、玩家有没有用 G 顶掉它、要挂的话手机在身上哪儿。
     */
    private static PhoneLocation resolveTarget(Minecraft mc) {
        Player player = mc.player;
        if (player == null || mc.level == null) return null;

        // 全屏那副面孔正开着的时候一律不撤。玩家很可能正是在【那一页】上把 HUD 关掉的
        // ——那一下不该把他正看着的手机也一并合上。等他自己关掉，下一 tick 自然走到收
        // 手机那条路上。手机在这期间离开副手也照此办理，与从背包里开的那部一个规矩：
        // 界面开着就开着，不会因为物品没了自己合上
        if (phone != null && mc.screen == phone && !phone.isHudMode()) return phone.location();

        boolean auto = PhoneHudPlacement.enabled() && PhoneItem.isPhone(player.getOffhandItem());

        // 自动那条翻了面就把手动那份作废，理由见 Override 的注释
        if (auto != lastAuto) {
            lastAuto = auto;
            manual = Override.NONE;
        }

        boolean want = switch (manual) {
            case SHOW -> true;
            case HIDE -> false;
            case NONE -> auto;
        };
        if (!want) return null;

        if (phone != null) {
            // 还在记着的那个位置上，最常见的情形，什么都不用做
            if (PhoneItem.isPhone(phone.location().resolve(player))) return phone.location();

            // 不在了。自动那条盯的就是副手那一格，那儿空了就是空了
            if (manual != Override.SHOW) return null;

            // 手动叫出来的那部：玩家多半只是把它在背包里挪了个格、或者从饰品栏取到了
            // 手上。重新找一下把位置改过来就是，别关掉重开——重开会把他正看着的那一页
            // 一并退回主屏，而他做的只不过是整理了一下背包
            PhoneLocation moved = PhoneLocation.find(player).orElse(null);
            phone.relocate(moved);
            return moved;
        }

        // 自动那条要的是【副手上那一部】，不能用 find：玩家两只手各拿一部时，
        // find 先挑主手，挂出来的就成了另一部手机
        if (auto) return new PhoneLocation.InHand(InteractionHand.OFF_HAND);

        // 手动叫出来的那部：手上、背包、饰品槽都找，顺序见 PhoneLocation.find
        return PhoneLocation.find(player).orElse(null);
    }

    /**
     * 按了 G。
     *
     * 正挂着就收起，没挂着就叫出来。身上一部手机都没有时什么都不做，也不提示——
     * 与开机键一个规矩：按错键是很常见的事，为此弹一句"你没有手机"反而聒噪。
     */
    private static void onTogglePressed(Minecraft mc) {
        // 全屏那副面孔开着时不管：人已经在正经用手机了，收起的路是 ESC。
        // 背包、聊天框开着时也不管，别去抢别人正在操作的界面
        if (mc.screen != null && !(mc.screen == phone && phone.isHudMode())) return;

        if (phone != null) {
            manual = Override.HIDE;
            return;
        }
        if (mc.player != null && PhoneItem.isCarriedBy(mc.player)) {
            manual = Override.SHOW;
        }
    }

    /**
     * 手机离开副手（或者玩家把这个功能关了）—— 真的关机。
     *
     * 先把字段置空再动界面：{@link Minecraft#setScreen} 会顺手触发一遍 removed()，
     * 那里面会回头看 phone 这个字段，留着旧值就会看到一部半死的手机。
     */
    private static void dismiss(Minecraft mc) {
        PhoneScreen closing = phone;
        phone = null;
        interacting = false;
        if (closing == null) return;

        // removed() 认得 hudOwned，不会重复拆，见 PhoneScreen.removed()
        if (mc.screen == closing) mc.setScreen(null);
        closing.shutdown();
    }

    /**
     * 退出世界时清掉。
     *
     * 与 {@link #dismiss} 分开只为一件事：这条路上【不能】碰 setScreen。断开连接、
     * 世界正在拆的过程中调 {@code setScreen(null)}，原版会直接抛
     * "Trying to return to in-game GUI during disconnection"。反正 Minecraft 紧接着
     * 就会把断线界面顶上来，那一下的 removed() 认得 hudOwned，不会重复拆。
     */
    public static void onWorldLeave() {
        PhoneScreen closing = phone;
        phone = null;
        interacting = false;
        // 手动那份只活在这一局：换个世界还压着上一局的"收起"是说不通的
        manual = Override.NONE;
        lastAuto = false;
        if (closing != null) closing.shutdown();
    }

    /**
     * 按开机键或右键手机时问一句：要开的就是副手上挂着的这一部吗？
     *
     * 是的话把它挪到屏幕正中，而不是另开一部。另开的话玩家身上就有了两部各记各页面的
     * 手机——全屏那部里翻了半天，收起来一看 HUD 还停在原处。
     *
     * @return 真的接管了才返回 true；返回 false 时调用方照常新开一部
     */
    public static boolean openFullscreen(PhoneLocation location) {
        if (phone == null || !phone.location().equals(location)) return false;

        interacting = false;
        phone.setHudMode(false);
        Minecraft.getInstance().setScreen(phone);
        return true;
    }

    //  交互

    private static void startInteracting(Minecraft mc) {
        interacting = true;
        mc.setScreen(phone);
        putCursorOnPhone(mc);
    }

    private static void stopInteracting(Minecraft mc) {
        interacting = false;
        // setScreen(null) 会把鼠标重新抓回去，视角控制随之恢复
        if (mc.screen == phone) mc.setScreen(null);
    }

    /**
     * 把光标放到手机中央。
     *
     * 不放的话它会停在屏幕正中——{@code MouseHandler.releaseMouse()} 每次都把光标弹到
     * 那儿，而手机多半贴在某个角上。玩家按下 Alt 的意思就是"我要点它"，让他先满屏找一趟
     * 光标再拖过去是没道理的。
     *
     * 坐标换算照抄原版 {@code MouseHandler.onMove} 的那一句反过来：GLFW 的光标坐标与
     * 窗口像素同一套，而 GUI 坐标是它除以 {@code 屏幕宽 / GUI 宽}。高分屏上这两者不等，
     * 直接拿 GUI 坐标去设光标会偏到左上角一小块里。
     */
    private static void putCursorOnPhone(Minecraft mc) {
        Window window = mc.getWindow();
        int guiW = window.getGuiScaledWidth();
        int guiH = window.getGuiScaledHeight();
        if (guiW <= 0 || guiH <= 0) return;

        double centerX = PhoneHudPlacement.originX(guiW, guiH)
                + PhoneHudPlacement.width(guiW, guiH) / 2.0;
        double centerY = PhoneHudPlacement.originY(guiW, guiH)
                + PhoneHudPlacement.height(guiW, guiH) / 2.0;

        GLFW.glfwSetCursorPos(window.getWindow(),
                centerX * window.getScreenWidth() / guiW,
                centerY * window.getScreenHeight() / guiH);
    }

    /**
     * 某个键此刻按着没有 —— 直接问 GLFW，理由见类注释。
     *
     * 键位允许绑到鼠标键上（原版按键设置里绑得到），所以两类都答得出来。没绑键时
     * getKey() 是 UNKNOWN，值为 -1，拿它去问 glfwGetKey 是未定义行为，得先挡掉。
     */
    private static boolean keyDown(Minecraft mc, net.minecraft.client.KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        int value = key.getValue();
        if (value < 0) return false;

        long handle = mc.getWindow().getWindow();
        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(handle, value);
            case MOUSE -> GLFW.glfwGetMouseButton(handle, value) == GLFW.GLFW_PRESS;
            // SCANCODE：原版按键设置绑不出这一类，真出现了就当没按
            default -> false;
        };
    }

    /**
     * 位置编辑器用：把副手上挂着的那部手机画在当前位置上当预览。
     *
     * 有真手机就画真的，玩家摆的是他自己那一页的内容，比一块占位的黑屏好判断得多。
     * 手机不在副手时（在背包里也点得进这一页）返回 false，由编辑器自己画一部空壳。
     */
    public static boolean renderPreview(GuiGraphics g, float partialTick) {
        if (phone == null) return false;
        syncSize(Minecraft.getInstance());
        phone.renderAsHud(g, partialTick);
        return true;
    }

    /**
     * 窗口尺寸变了就重新 init 一遍。
     *
     * 挂在 HUD 上的这部手机不是 mc.screen，原版那条 resize 通知路过它——不同步的话，
     * 它会一直按旧的窗口尺寸算位置，拖一下窗口手机就跑到画面外面去了。
     */
    private static void syncSize(Minecraft mc) {
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        if (phone.width == w && phone.height == h) return;
        phone.init(mc, w, h);
    }
}
