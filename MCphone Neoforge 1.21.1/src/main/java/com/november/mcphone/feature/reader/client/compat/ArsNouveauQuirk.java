package com.november.mcphone.feature.reader.client.compat;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.feature.reader.BookRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 新生魔艺（Ars Nouveau）—— 点它的笔记本，打开它【自己】的文档界面。
 *
 * 它早就不用 Patchouli 了
 *
 * 5.x 起，新生魔艺整套手册换成了自研的文档系统（{@code api.documentation} 那一包，
 * 带分类、搜索、书签、法术样式预览）。玩家右键那本破旧笔记本时，走的是
 *
 *     WornNotebook.use → DocClientUtils.openBook()
 *
 * 与 Patchouli 无关。
 *
 * 但它的 jar 里【仍然留着】一本 Patchouli 书：
 *
 *     data/ars_nouveau/patchouli_books/worn_notebook/book.json
 *
 * 于是 Patchouli 照常把它注册进书目，我们照常把它列在书架上——而点开得到的是一本
 * 没人维护的旧书：条目只有 en_us 一个语言目录，内容停在换系统那一刻，
 * 新东西一样都没有。整个模组里现在只剩实体动画还用 Patchouli（
 * {@code PatchouliHandler.isPatchouliWorld} 判断"我是不是被画在书页里"），
 * 开书那两个方法一处都没人调了。
 *
 * 所以这条特例把"打开"换掉，别的不动
 *
 * 书名（ars_nouveau.book.name）、出处、图标都是对的，改了反而不像玩家认得的那本。
 * 也【不】把它从架子上拿掉——拿掉等于手机里查不到新生魔艺的文档，而这个 App
 * 存在的意义正相反。
 *
 * 图标用回它老版本那张
 *
 * 它的笔记本物品现在是 GeckoLib 的 3D 模型（parent 是 builtin/entity），在列表里
 * 画它会伤到整个界面，那件事由 {@code GuiUtil.canDrawItemIcon} 统一挡掉——那不是
 * 新生魔艺一家的毛病，所以不在这条特例里管。
 *
 * 但挡掉之后退回的是一张通用书图，一排书里认不出哪本是它。好在换 3D 模型之前的
 * 那张 16×16 平面小图还在它 jar 里（{@code textures/item/worn_notebook.png}），
 * 直接画那张——玩家认得的正是这张图。
 *
 * 为什么走反射而不是加一条编译依赖
 *
 * 我们要的只是一个静态无参方法。而为它加编译依赖的代价是：一个 20 MB 的 jar，
 * 外加 GeckoLib、nuggets 几个前置库——只为了写出 {@code DocClientUtils.openBook()}
 * 这一行。更要紧的是特例名单会越来越长，那些模组多半都是我们【不愿意】为之加
 * 编译依赖的。
 *
 * 反射的代价是没有编译期检查，对方改个方法名我们就断。所以断了要能退回默认：
 * {@link #open} 失败时返回 false，玩家点开的是那本遗留的 Patchouli 书——正是
 * 没有这条特例时的行为，不是一个点了没反应的死按钮。
 */
public final class ArsNouveauQuirk implements BookQuirk {

    public static final String ARS_NOUVEAU_MODID = "ars_nouveau";

    /** 它留在 Patchouli 里的那本遗留书 */
    private static final ResourceLocation WORN_NOTEBOOK =
            ResourceLocation.fromNamespaceAndPath(ARS_NOUVEAU_MODID, "worn_notebook");

    /**
     * 它自己的开书入口。用 openBook() 而不是 IndexScreen.open()：前者是它的物品
     * 按下去走的那一个，会回到玩家上次看的那一页；后者每次都从目录开始。
     * 照它自己的做法做，玩家在手机里点开和在背包里右键，看到的就是同一个东西。
     */
    private static final String DOC_CLIENT_UTILS =
            "com.hollingsworth.arsnouveau.api.documentation.DocClientUtils";

    private static final String OPEN_BOOK = "openBook";

    /**
     * 它换 3D 模型之前那张平面小图，至今还在 jar 里。
     *
     * 不是从物品模型推出来的，是写死的一条路径——所以对方哪天真删了这张图，
     * 我们得能发现：{@link #hasLegacyIcon} 查不到就退回通用书图，不会画成紫黑格子。
     */
    private static final ResourceLocation LEGACY_ICON =
            ResourceLocation.fromNamespaceAndPath(ARS_NOUVEAU_MODID, "textures/item/worn_notebook.png");

    /** 图标尺寸，与文件一致。写成常量是为了让"这张图多大"只有一处说法 */
    private static final int LEGACY_ICON_SIZE = 16;

    /** 解析过了吗。解析只做一次，无论成没成 */
    private boolean resolved;

    /** 解析出来的那个方法，null 表示这条路走不通，一律退回默认 */
    private Method openBook;

    /** 老图标查过了吗，以及查到没有。每帧每行都要问，不能每次都去翻资源管理器 */
    private boolean iconProbed;
    private boolean iconFound;

    @Override
    public String targetModId() {
        return ARS_NOUVEAU_MODID;
    }

    /**
     * 只认这一本。按命名空间一网打尽是错的——它将来完全可能再出一本真正的
     * Patchouli 书，那本该照常打开。
     */
    @Override
    public boolean matches(BookRef book) {
        return WORN_NOTEBOOK.equals(book.bookId());
    }

    @Override
    public boolean open(BookRef book) {
        Method method = resolve();
        if (method == null) return false;

        try {
            method.invoke(null);
            return true;
        } catch (Throwable t) {
            // 记一次就把方法丢掉：这条路已经证明走不通，之后每次点都记一行没有意义
            MCphone.LOGGER.error("[MCphone] 调用新生魔艺的 {}.{}() 失败，改为打开它遗留的 Patchouli 书",
                    DOC_CLIENT_UTILS, OPEN_BOOK, t);
            openBook = null;
            return false;
        }
    }

    @Override
    public boolean renderIcon(GuiGraphics g, BookRef book, int x, int y, int size) {
        if (!hasLegacyIcon()) return false;

        GuiUtil.drawTexture(g, LEGACY_ICON, x, y, size, size,
                LEGACY_ICON_SIZE, LEGACY_ICON_SIZE);
        return true;
    }

    /**
     * 那张老图还在不在。查一次存着——这是每帧每行都会问到的路。
     *
     * 代价是资源包中途换了不会重查。可以接受：这张图来自模组自己的 jar，
     * 而不是我们公开出去的换肤位，没人有理由在运行中把它换掉。
     */
    private boolean hasLegacyIcon() {
        if (iconProbed) return iconFound;
        iconProbed = true;

        try {
            Minecraft mc = Minecraft.getInstance();
            iconFound = mc != null && mc.getResourceManager().getResource(LEGACY_ICON).isPresent();
            if (!iconFound) {
                MCphone.LOGGER.info("[MCphone] 新生魔艺的老图标 {} 不在了，书架上这一本画通用书图",
                        LEGACY_ICON);
            }
        } catch (Throwable t) {
            iconFound = false;
        }
        return iconFound;
    }

    /** 解析对方的开书方法。找不到不是错误，只是这个版本没有——记一行，退回默认 */
    private Method resolve() {
        if (resolved) return openBook;
        resolved = true;

        try {
            Method method = Class.forName(DOC_CLIENT_UTILS).getMethod(OPEN_BOOK);
            if (!Modifier.isStatic(method.getModifiers())) {
                MCphone.LOGGER.warn("[MCphone] 新生魔艺的 {}.{}() 不再是静态方法，"
                        + "「阅读」里点它的笔记本将打开遗留的 Patchouli 书",
                        DOC_CLIENT_UTILS, OPEN_BOOK);
                return null;
            }
            openBook = method;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 没找到新生魔艺的 {}.{}()（{}），"
                    + "「阅读」里点它的笔记本将打开遗留的 Patchouli 书。"
                    + "这多半是它改了包名或方法名，特例需要跟进",
                    DOC_CLIENT_UTILS, OPEN_BOOK, t.toString());
        }
        return openBook;
    }
}
