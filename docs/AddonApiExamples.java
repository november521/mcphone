package com.example.mcphoneaddon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.november.mcphone.api.MCphoneApi;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.cost.EmcWallets;
import com.november.mcphone.api.cost.IAppPriceProvider;
import com.november.mcphone.api.cost.ICost;
import com.november.mcphone.api.cost.IEmcWallet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * docs/addon-api.md 里那些示例的可编译副本 —— 文档的守门人。
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

    static final ICost INGOTS = ICost.matching(
            stack -> stack.is(Tags.Items.INGOTS), 5,
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
        if (MCphoneApi.VERSION >= 2) {
            // NewFeatureBridge.doThing();
        }
    }

    //  ICost 三个方法都在 
    static boolean pay(Player p) {
        return EMC.canAfford(p) && EMC.consume(p) && EMC.describe() != null
                && INGOTS.canAfford(p);
    }
}
