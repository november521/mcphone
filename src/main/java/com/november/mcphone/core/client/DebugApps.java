package com.november.mcphone.core.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 调试用的占位 App —— 只为把主屏塞满，好测分页与拖动排序。
 *
 * ================================================================
 * 为什么不走 SPI
 * ================================================================
 *
 * 走 SPI（META-INF/services）的话它们一开游戏就躺在目录里，玩家的应用商店会
 * 平白多出一堆没有任何用处的东西。把 isAvailable() 写成 false 也救不了：那样
 * 它们会落进 UNAVAILABLE，转头出现在商店的「联动 App」页上，还会被标成"缺某个
 * 模组"——那更糟，玩家会去找一个根本不存在的前置。
 *
 * 所以改成"要了才有"：只有在游戏里敲命令才登记进目录。不敲的玩家从头到尾
 * 一个占位 App 都看不到，这个类除了被 JVM 加载一次之外什么都不做。
 *
 * ================================================================
 * 用法
 * ================================================================
 *
 *   /mcphone debugapps 25     主屏上恰好有 25 个占位 App（够翻两页）
 *   /mcphone debugapps 0      全部卸掉
 *
 * 这是【客户端命令】：服务器不需要装任何东西，联机时照样能敲，也不需要 OP。
 *
 * 退出世界再进来它们不会自己回来——目录是运行时才建的，而安装状态只认目录里
 * 有的 id，对不上的会被自动丢掉（见 {@link PhoneScreenRegistry} 的 loadState）。
 * 这一点是白捡的：不必专门写清理逻辑，也不会在玩家的存档里留下垃圾。
 */
public final class DebugApps {

    private DebugApps() {}

    /** 最多造这么多。上限只是为了防手滑把 25 打成 2500 */
    public static final int MAX = 64;

    /**
     * 目录里已经有几个占位 App。
     *
     * 只涨不落：注册表的目录是只增不减的（卸载可逆是它的设计前提），所以"卸掉"
     * 只是把它们从已装集合里摘出去，条目还在。
     */
    private static int spawned = 0;

    // ============================================================
    //  命令
    // ============================================================

    /**
     * 注册 /mcphone debugapps。
     *
     * 挂在【游戏总线】上，由 MCphoneClient 显式添加监听——那个文件里其余几处
     * 也是这么做的，理由见它开头的注释：不依赖注解自动路由，省得哪天路由规则
     * 变了，事件静悄悄地不再触发。
     */
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(MCphone.MODID)
                        .then(Commands.literal("debugapps")
                                .then(Commands.argument("count",
                                                IntegerArgumentType.integer(0, MAX))
                                        .executes(ctx -> {
                                            int n = setCount(IntegerArgumentType
                                                    .getInteger(ctx, "count"));
                                            ctx.getSource().sendSuccess(() -> Component
                                                    .translatable("mcphone.command.debug_apps", n),
                                                    false);
                                            return n;
                                        }))));
    }

    /**
     * 让主屏上恰好有 count 个占位 App。
     *
     * 多退少补，而不是"每敲一次加一批"：调试时想要的是一个确定的数量（"给我 25 个
     * 看看第二页"），累加的话还得自己记现在有几个。
     *
     * @return 实际的个数（已按 {@link #MAX} 夹过）
     */
    public static int setCount(int count) {
        int want = Math.max(0, Math.min(count, MAX));

        for (int i = 0; i < want; i++) {
            if (i < spawned) {
                PhoneScreenRegistry.install(idOf(i));            // 目录里有了，装回来就行
            } else {
                PhoneScreenRegistry.install(new Placeholder(i)); // 登记 + 安装
                spawned = i + 1;
            }
        }
        for (int i = want; i < spawned; i++) {
            PhoneScreenRegistry.uninstall(idOf(i));
        }

        MCphone.LOGGER.info("[MCphone] 占位 App 现在有 {} 个（目录里累计 {} 个）", want, spawned);
        return want;
    }

    private static ResourceLocation idOf(int index) {
        return ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "debug_" + index);
    }

    // ============================================================
    //  占位 App 本体
    // ============================================================

    /**
     * 一个什么都不做的 App。
     *
     * 主屏上的图标画成带编号的色块，【不】用贴图：分页和拖动要靠肉眼确认"第 13 个
     * 确实挪到了第二页第一格"，编号比任何图案都管用，而一堆长得一样的图标根本没法
     * 验证。这一处刻意不留换肤接口——它不该出现在玩家的手机上，给它留接口等于承诺
     * 一件我们不打算维护的事。
     *
     * 应用商店那边是另一回事：商店画的是 {@link #getIconTexture()} 给的贴图，不走
     * renderIcon。所以还是得有一张真图，否则卸掉几个之后商店列表里会出现紫黑格子。
     */
    private record Placeholder(int index) implements IPhoneApp {

        /** 只为让相邻的图标一眼能分开，没有别的含义 */
        private static final int[] PALETTE = {
                0xFFE57373, 0xFF81C784, 0xFF64B5F6, 0xFFFFB74D,
                0xFFBA68C8, 0xFF4DB6AC, 0xFFA1887F, 0xFF90A4AE,
        };

        private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(
                MCphone.MODID, "textures/app/debug_placeholder.png");

        @Override
        public ResourceLocation getId() {
            return idOf(index);
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("mcphone.app.debug_placeholder", index + 1);
        }

        @Override
        public ResourceLocation getIconTexture() {
            return ICON;
        }

        @Override
        public void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
            g.fill(x, y, x + size, y + size, PALETTE[index % PALETTE.length]);

            Font font = Minecraft.getInstance().font;
            String label = String.valueOf(index + 1);
            g.drawString(font, label,
                    x + (size - font.width(label)) / 2,
                    y + (size - font.lineHeight) / 2 + 1,
                    0xFF000000, false);
        }

        /** 点开走的是 openPage()，这里不会被调到 */
        @Override
        public void onPress() { }

        /** 顺带把"附属 App 画在手机屏幕里"那条路也一起踩了，不白造这些壳 */
        @Override
        public IPhonePage openPage() {
            return new PlaceholderPage(index);
        }

        /**
         * 不预装。
         *
         * 它们是靠命令直接 install 的，走不到预装判断；写 false 是为了万一哪天
         * 有人把它们接上 SPI，也不会一开局就糊玩家一脸
         */
        @Override
        public boolean isPreinstalled() { return false; }

        @Override
        public String getAuthor() { return "MCphone"; }

        @Override
        public String getDescription() {
            return Component.translatable("mcphone.app.debug_placeholder.desc").getString();
        }
    }

    /** 点开之后的那一页：只说明自己是谁。返回键走 PhoneScreen 的默认路径 */
    private record PlaceholderPage(int index) implements IPhonePage {

        @Override
        public void render(PhoneCanvas c) {
            Font font = c.font();
            String title = Component
                    .translatable("mcphone.app.debug_placeholder", index + 1).getString();
            String id = "debug_" + index;

            int cy = c.y() + c.height() / 2;
            c.graphics().drawString(font, title,
                    c.x() + (c.width() - font.width(title)) / 2,
                    cy - font.lineHeight, c.style().titleColor(), false);
            c.graphics().drawString(font, id,
                    c.x() + (c.width() - font.width(id)) / 2,
                    cy + 2, c.style().subtleColor(), false);
        }
    }
}
