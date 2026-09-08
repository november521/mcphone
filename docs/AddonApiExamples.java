package com.example.mcphoneaddon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.november.mcphone.api.MCphoneApi;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneMultiLineEditBox;
import com.november.mcphone.api.cost.EmcWallets;
import com.november.mcphone.api.cost.IAppPriceProvider;
import com.november.mcphone.api.cost.ICost;
import com.november.mcphone.api.cost.IEmcWallet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * wiki 的附属接口文档里那些示例的可编译副本 —— 文档的守门人。
 *
 * 它不是测试，【只要求编得过】，跑不跑无所谓。存在的理由只有一个：谁改了 api 包里的
 * 方法名、参数顺序或返回类型，这个文件当场编不过，那就是"该回来改文档了"的信号。
 * 靠人记得回去改文档是靠不住的，那份文档已经烂过一次——整整一版只写了 IPhoneApp，
 * 而 IPhonePage / PhoneCanvas / 商店 / 代价那几套一个字都没有。
 *
 * 跑法（先 ./gradlew compileJava 生成 build/moddev 的类路径文件）：
 *
 *   CP="build/classes/java/main:build/moddev/artifacts/neoforge-21.1.248-merged.jar:$(tr '\n' ':' < build/moddev/serverLegacyClasspath.txt)"
 *   javac -cp "$CP" -d /tmp/doccheck docs/AddonApiExamples.java
 *
 * 换 NeoForge 版本时上面那个 jar 名要跟着改，它写在 gradle.properties 的 neo_version 里。
 */
public final class AddonApiExamples {

    //  第 0/1 节：一个 App 
    public static final class CalculatorApp implements IPhoneApp {

        @Override
        public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("mymod", "calculator");
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("mymod.app.calculator");
        }

        @Override
        public ResourceLocation getIconTexture() {
            return ResourceLocation.fromNamespaceAndPath("mymod", "textures/app/calculator.png");
        }

        @Override
        public IPhonePage openPage() {
            return new CalculatorPage();
        }

        @Override
        public void onPress() {
            Minecraft.getInstance().setScreen(null);   // 文档里是 new CalculatorScreen()
        }

        @Override
        public List<RequiredMod> requiredMods() {
            return List.of(new RequiredMod("someothermod", "Some Other Mod（显示名）"));
        }

        // 文档「动态图标」与「记得自己开混合」那两段
        private static final int FRAMES = 8;
        private static final int FRAME_MS = 100;

        @Override
        public void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
            int frame = (int) ((System.currentTimeMillis() / FRAME_MS) % FRAMES);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.blit(getIconTexture(), x, y, size, size,
                    frame * size, 0, size, size, size * FRAMES, size);
            RenderSystem.disableBlend();
        }
    }

    //  第 2 节：一页 
    public static final class CalculatorPage implements IPhonePage {
        @Override
        public void render(PhoneCanvas c) {
            c.graphics().drawString(c.font(), "1 + 1 = 2",
                    c.x() + 4, c.y() + 4, c.style().bodyColor(), false);

            // 文档里列的每一个访问器与配色都点一遍
            boolean hit = c.hovered(c.x(), c.y(), c.width(), c.height()) || c.hoveredContent();
            int unused = c.style().titleColor() | c.style().subtleColor() | c.style().accentColor()
                    | c.style().screenBackground() | c.style().pressedOverlay()
                    | c.style().buttonColor() | c.style().buttonHoverColor()
                    | c.style().buttonDisabledColor() | c.style().buttonDisabledTextColor()
                    | c.mouseX() | c.mouseY() | (int) c.partialTick() | (hit ? 1 : 0);
        }

        @Override public boolean mouseClicked(double x, double y, int button) { return true; }
        @Override public boolean mouseScrolled(double x, double y, double amount) { return false; }
        @Override public boolean keyPressed(int key, int scan, int mods) { return false; }
        @Override public boolean charTyped(char ch, int mods) { return false; }
        @Override public boolean capturesKeyboard() { return true; }
        @Override public boolean onBack() { return false; }
        @Override public void onOpen() {}
        @Override public void onClose() {}
    }

    /**
     * 一页可以滚的列表 —— 附属十有八九要写的形状，也是唯一一处非用
     * {@link PhoneCanvas#clipped} 不可的地方。
     *
     * 【别写成 c.graphics().enableScissor(...)】：那句收窗口坐标、不看 PoseStack，
     * 玩家把界面调到 150% 之后你的列表就会缺一块，而你在 100% 下永远测不出来。
     */
    public static final class ScrollingListPage implements IPhonePage {

        private static final int ROW_H = 12;
        private final java.util.List<String> rows = java.util.List.of("一", "二", "三");

        private int scrollPx;

        @Override
        public void render(PhoneCanvas c) {
            int contentH = rows.size() * ROW_H;
            int maxScroll = Math.max(0, contentH - c.height());
            scrollPx = Mth.clamp(scrollPx, 0, maxScroll);

            // 起点在 lambda 外面算好：里面那个 y 是要变的，捕获的量必须是定的
            final int startY = c.y() - scrollPx;

            c.clipped(c.x(), c.y(), c.width(), c.height(), () -> {
                int y = startY;
                for (String row : rows) {
                    // 画在视野外的整行跳过——裁剪只是保证画不出去，不替你省绘制
                    if (y + ROW_H > c.y() && y < c.y() + c.height()) {
                        c.graphics().drawString(c.font(), row, c.x() + 4, y + 2,
                                c.style().bodyColor(), false);
                    }
                    y += ROW_H;
                }
            });
        }

        @Override
        public boolean mouseScrolled(double x, double y, double amount) {
            scrollPx -= (int) (amount * ROW_H);
            return true;
        }

        @Override public boolean mouseClicked(double x, double y, int button) { return true; }
        @Override public boolean keyPressed(int key, int scan, int mods) { return false; }
        @Override public boolean charTyped(char ch, int mods) { return false; }
        @Override public boolean capturesKeyboard() { return false; }
        @Override public boolean onBack() { return false; }
        @Override public void onOpen() {}
        @Override public void onClose() {}
    }

    /**
     * 一页带多行输入框的 —— 原版 {@code MultiLineEditBox} 直接摆进来，玩家把界面调大之后
     * 正文顶上几行会整行不见（它自己内部那句裁剪不看 PoseStack）。用这个替身就没事。
     *
     * 【不用把 PhoneCanvas 交给它】：缩放是它自己从 GuiGraphics 的变换矩阵里读的。
     */
    public static final class NotePadPage implements IPhonePage {

        private PhoneMultiLineEditBox box;

        @Override
        public void render(PhoneCanvas c) {
            // 坐标就是 canvas 给的那一套，不用换算；右边留出 8 像素给滚动条
            final int x = c.x() + 4;
            final int y = c.y() + 4;

            if (box == null) {
                box = new PhoneMultiLineEditBox(c.font(), x, y,
                        c.width() - 8 - 8, c.height() - 8,
                        Component.translatable("mymod.notepad.placeholder"),
                        Component.translatable("mymod.notepad.title"));
                box.setCharacterLimit(2000);
                box.setFocused(true);
            } else {
                // 手机居中的位置随窗口大小变，每帧同步一次
                box.setX(x);
                box.setY(y);
            }

            box.render(c.graphics(), c.mouseX(), c.mouseY(), c.partialTick());
        }

        // 键盘全转给它，并且必须 capturesKeyboard，否则打拼音按到 e 会命中背包键
        @Override public boolean capturesKeyboard() { return true; }

        @Override public boolean keyPressed(int key, int scan, int mods) {
            return box != null && box.keyPressed(key, scan, mods);
        }

        @Override public boolean charTyped(char ch, int mods) {
            return box != null && box.charTyped(ch, mods);
        }

        @Override public boolean mouseClicked(double x, double y, int button) {
            return box != null && box.mouseClicked(x, y, button);
        }

        @Override public boolean mouseScrolled(double x, double y, double amount) {
            // 原版收的是 scrollX / scrollY 两个量，横向给 0
            return box != null && box.mouseScrolled(x, y, 0.0, amount);
        }
    }

    //  第 3 节：商店来源 
    public static final class MySource implements IAppSource {
        @Override
        public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("mymod", "official_repo");
        }

        @Override
        public Component getDisplayName() { return Component.literal("My Repo"); }

        @Override
        public void listAvailable(Consumer<List<AppInfo>> callback) {
            AppInfo info = AppInfo.builder(
                            ResourceLocation.fromNamespaceAndPath("mymod", "calculator"),
                            Component.literal("Calculator"),
                            getId())
                    .icon(ResourceLocation.fromNamespaceAndPath("mymod", "textures/app/calculator.png"))
                    .version("1.0.0")
                    .author("你")
                    .description("一句话")
                    .build();

            AppInfo fromApp = AppInfo.of(new CalculatorApp(), getId());

            List<AppInfo> list = List.of(info, fromApp);
            Minecraft.getInstance().execute(() -> callback.accept(list));
        }

        @Override
        public void install(AppInfo info, Consumer<IPhoneApp> onSuccess, Consumer<Component> onError) {
            Minecraft.getInstance().execute(() -> onSuccess.accept(new CalculatorApp()));
        }
    }

    //  第 4 节：代价 
    public static final class MyPrices implements IAppPriceProvider {
        @Override
        public Map<ResourceLocation, ICost> prices() {
            return Map.of(
                    ResourceLocation.fromNamespaceAndPath("mymod", "calculator"),
                    ICost.of(Items.DIAMOND, 3));
        }
    }

    static final ICost EMC = ICost.emc(1000);

    // 原版是 Tags.Items.INGOTS（NeoForge 物品标签）；Fabric 侧没有那套标签常量，
    // 示例改用原版物品判定，语义不变（"值 5 点的金属锭"）
    static final ICost INGOTS = ICost.matching(
            stack -> stack.is(Items.IRON_INGOT) || stack.is(Items.GOLD_INGOT), 5,
            Component.translatable("mymod.cost.ingots"));

    public static final class MyWallet implements IEmcWallet {
        @Override public boolean isAvailable() { return true; }
        @Override public Component unavailableReason() { return Component.empty(); }
        @Override public boolean canAfford(Player player, long amount) { return true; }
        @Override public boolean withdraw(Player player, long amount) { return true; }
        @Override public Component describeBalance(Player player) { return Component.empty(); }
    }

    static void mount() {
        EmcWallets.set(new MyWallet());
    }

    //  第 5 节：版本判断 
    static void versionGate() {
        // VERSION 从 1.10.4 起是在静态块里赋的，读它编出来的是 getstatic，问的是运行时
        // 真正装着的那个宿主。【前提是拿 1.10.4 或更新的 MCphone 编译】——在那之前它是
        // 编译期常量，会被内联进这里，判断到的是编译时的版本。
        // 【另一条坑】：别在这个方法里直接 new PhoneMultiLineEditBox——旧版上 JVM 校验这个
        // 方法时就抛 NoClassDefFoundError，轮不到那句 if。两条坑各管各的。
        if (MCphoneApi.VERSION >= 2) {
            // MultiLineBridge.create(...);   // 这个类里才引用 PhoneMultiLineEditBox
        }
    }

    //  ICost 三个方法都在 
    static boolean pay(Player p) {
        return EMC.canAfford(p) && EMC.consume(p) && EMC.describe() != null
                && INGOTS.canAfford(p);
    }
}
