package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.feature.reader.BookRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * GuideME 书源 —— 把用它做手册的模组一次全收进来，不用一个个白名单。
 *
 * GuideME 是什么
 *
 * AE2 的作者把自家那套手册系统单独拆成了一个模组（modid {@code guideme}），
 * 于是它成了第二个"多个模组共用一套手册"的体系。眼下已经在用它的至少有：
 *
 *   应用能源 2（AE2）      —— guideme 是它的【硬】依赖
 *   现代化工艺（Modern Industrialization）—— 同样是硬依赖
 *
 * 换句话说，整合包里只要有这两个中的任何一个，GuideME 就一定在场。
 *
 * 为什么这一支是自动发现，而沉浸工程那一支要白名单
 *
 * 因为 GuideME 自己就有"列出全部手册"这个能力，而沉浸工程只有它自己那一本。
 * 这里不写死任何一本书的 id：将来第三、第四个模组接上 GuideME，这个书源不改
 * 一行就把它们收进来了。白名单（{@link ExternalBookSource}）只留给那些各写各的、
 * 没有共同体系可循的。
 *
 * 接触面：一个内部接口 + 一个公开 API
 *
 *   guideme.internal.GuideMEProxy   instance() / getAvailableGuides()
 *                                   / getGuideDisplayName(id) / openGuide(player, id)
 *   guideme.Guides                  createGuideItem(id)   —— 只用来取图标
 *
 * Proxy 那个在 internal 包下，明知不够体面还是用它，理由有两条：一是【开手册
 * 这件事没有公开 API】，GuideScreen 同样在 internal 里，绕不开；二是它这几个方法
 * 的签名里一个 GuideME 的类型都没有，全是 ResourceLocation / Component / Player /
 * Stream，反射写起来干净，也不必解析对方的类型。
 *
 * 而且这样失败方式更好：Proxy 一旦对不上，列表与打开【一起】失效，书城里干脆
 * 不出现这些书。反过来（用公开 API 列表、用内部类打开）会列出一堆点了没反应的书，
 * 那比看不见更糟。
 *
 * 走的就是它手册物品右键那条路
 *
 * GuideItem.use() 在客户端调的正是 {@code GuideMEProxy.instance().openGuide(player, id)}，
 * 我们照抄。所以在手机里点开与在背包里右键，进的是同一个界面、同一段历史记录。
 *
 * 类型隔离：字段与方法签名里一个 guideme.* 都不许出现——本类里连 import 都没有，
 * 全靠反射，理由与 PatchouliSource 那条一样。
 */
public final class GuideMeSource implements BookSource {

    public static final String GUIDEME_MODID = "guideme";

    /** 与 {@link BookRef#sourceId()} 对应，别改：玩家书架里的条目按它记 */
    private static final String SOURCE_ID = "guideme";

    private static final String PROXY = "guideme.internal.GuideMEProxy";
    private static final String GUIDES = "guideme.Guides";

    /** 扫出来的书。类型必须是中性的，理由见类注释 */
    private List<BookRef> books = List.of();

    /** 上一次记进日志的本数，-1 表示还没记过 */
    private int loggedCount = -1;

    /** 解析过了吗。无论成没成都只做一次 */
    private boolean resolved;

    private Method instance;
    private Method availableGuides;
    private Method displayName;
    private Method openGuide;

    /** 取图标用，可以单独失败：没有图标只是退回兜底书图，不影响读书 */
    private Method createGuideItem;

    @Override
    public String id() {
        return SOURCE_ID;
    }

    /** 这个方法里不能出现任何 guideme 的东西，它得在对方缺席时也能安全执行 */
    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded(GUIDEME_MODID);
    }

    @Override
    public List<BookRef> list() {
        return books;
    }

    @Override
    public void refresh() {
        books = scan();

        if (books.size() != loggedCount) {
            loggedCount = books.size();
            MCphone.LOGGER.info("[MCphone] 书城扫到 {} 本 GuideME 手册", loggedCount);
        }
    }

    @Override
    public void open(BookRef book) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;      // 不在世界里不可能点到这儿，防一手而已

        Object proxy = proxy();
        if (proxy == null) return;

        try {
            Object opened = openGuide.invoke(proxy, player, book.bookId());
            if (Boolean.FALSE.equals(opened)) {
                MCphone.LOGGER.warn("[MCphone] GuideME 拒绝打开手册 {}（它说这本不存在）", book.bookId());
            }
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 打开 GuideME 手册 {} 失败", book.bookId(), t);
        }
    }

    /**
     * 画那本手册对应的物品。
     *
     * GuideME 给每本手册造一个带 id 组件的手册物品，模型按 id 分发，所以画出来
     * 就是那本书自己的封面——AE2 的和现代化工艺的不一样。
     */
    @Override
    public boolean renderIcon(GuiGraphics g, BookRef book, int x, int y, int size) {
        if (!resolve() || createGuideItem == null) return false;

        try {
            Object stack = createGuideItem.invoke(null, book.bookId());
            if (!(stack instanceof ItemStack itemStack)) return false;
            return GuiUtil.drawItemIcon(g, itemStack, x, y, size);
        } catch (Throwable t) {
            // 每帧每行都会走到，不记日志：画不出来只是退回兜底图
            return false;
        }
    }

    /** 问 GuideME 现在有哪些手册。任何一步不成就当一本都没有 */
    private List<BookRef> scan() {
        Object proxy = proxy();
        if (proxy == null) return List.of();

        List<BookRef> out = new ArrayList<>();
        try {
            Object result = availableGuides.invoke(proxy);
            if (!(result instanceof Stream<?> stream)) return List.of();

            for (Object id : stream.toList()) {
                if (!(id instanceof ResourceLocation guideId)) continue;
                out.add(new BookRef(SOURCE_ID, guideId, titleOf(proxy, guideId), null,
                        BookSource.modName(guideId.getNamespace())));
            }
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 列 GuideME 手册失败，这一类书这次不显示", t);
            return List.of();
        }

        return List.copyOf(out);
    }

    /**
     * 书名。
     *
     * 它给的是本地化好的名字，与玩家在背包里那本手册物品上看到的完全一致——
     * 那个物品的 getName() 走的就是这同一个方法。取不到就退回 id 的路径段，
     * 至少还认得出是哪一本。
     */
    private Component titleOf(Object proxy, ResourceLocation guideId) {
        try {
            Object name = displayName.invoke(proxy, guideId);
            if (name instanceof Component component) return component;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 取 GuideME 手册 {} 的名字失败，改用 id", guideId, t);
        }
        return Component.literal(guideId.getPath());
    }

    /** 拿到它的 Proxy 实例，拿不到就是这一支整个不可用 */
    private Object proxy() {
        if (!resolve()) return null;
        try {
            return instance.invoke(null);
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 取 GuideME 的 Proxy 实例失败", t);
            return null;
        }
    }

    /** 解析对方那几个方法。找不到不是错误，只是这个版本对不上——记一行，这一支就此静默 */
    private boolean resolve() {
        if (resolved) return instance != null;
        resolved = true;

        try {
            Class<?> proxyClass = Class.forName(PROXY);
            instance = proxyClass.getMethod("instance");
            availableGuides = proxyClass.getMethod("getAvailableGuides");
            displayName = proxyClass.getMethod("getGuideDisplayName", ResourceLocation.class);
            openGuide = proxyClass.getMethod("openGuide", Player.class, ResourceLocation.class);
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 没对上 GuideME 的 {}（{}），书城里不会有它那批手册。"
                    + "这多半是它改了内部接口，书源需要跟进", PROXY, t.toString());
            instance = null;
            return false;
        }

        // 图标是可选的：取不到只是退回兜底书图，不该拖累整支书源
        try {
            createGuideItem = Class.forName(GUIDES)
                    .getMethod("createGuideItem", ResourceLocation.class);
        } catch (Throwable t) {
            MCphone.LOGGER.info("[MCphone] 没对上 GuideME 的 {}.createGuideItem，"
                    + "它那批手册在书城里画通用书图", GUIDES);
            createGuideItem = null;
        }

        return true;
    }
}
