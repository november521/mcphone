package com.november.mcphone.core.client;

import com.formdev.flatlaf.FlatLightLaf;
import com.november.mcphone.MCphone;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * 选一张图 —— {@link JFileChooser} 装在我们自己的 {@link JFrame} 里，用 FlatLaf 换掉灰色皮肤。
 *
 * <h2>为什么不用系统的原生选择器</h2>
 *
 * 试过了，抬不起来。我们创建的任何窗口都能设成 always-on-top，于是浮在 Minecraft 的
 * 全屏窗口之上；而 Windows 原生的 {@code GetOpenFileName} 既不继承 {@code WS_EX_TOPMOST}，
 * 也不理会我们设的任何提示，它永远待在 z 序最底下——玩家点"选一张图"，什么都看不见，
 * 只会以为游戏卡住了。所以窗口得是我们自己的，外观改由 FlatLaf 提供。
 *
 * <h2>线程</h2>
 *
 * 弹窗跑在 EDT 上，调用线程（渲染线程）在闩上等——这样游戏不冻住，
 * 鼠标移动、动画照常。这是 AtomChat 那边验证过的做法，原样沿用。
 *
 * <h2>与 AtomChat 版的差别</h2>
 *
 * 那边是独立 mod 的完整实现（含表情包专用的过滤与保存对话框）。这里只留手机需要的两件事：
 * 选一张图（壁纸/表情导入）、以及"打开文件夹"。缩略图解码复用 {@link ImageCodec}，
 * 不再单独维护一份解码器。
 */
public final class ImagePicker {

    private static boolean lookAndFeelInstalled;

    private ImagePicker() {}

    /**
     * 选一张图。返回选中的路径，取消或失败返回 null。
     *
     * @param nameFilter 可选的额外过滤（如只收 {@code .png}）；null 表示收常见图片后缀
     */
    public static Path pickImage(Predicate<String> nameFilter) {
        // 第二道防线：启动器可能带 -Djava.awt.headless=true
        System.setProperty("java.awt.headless", "false");

        AtomicReference<Path> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            try {
                result.set(showChooser(nameFilter));
            } catch (Throwable t) {
                MCphone.LOGGER.warn("[MCphone] 文件选择器失败", t);
            } finally {
                done.countDown();
            }
        });

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result.get();
    }

    private static Path showChooser(Predicate<String> nameFilter) {
        installLookAndFeel();

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(tr("mcphone.picker.title"));
        chooser.setAcceptAllFileFilterUsed(false);

        Predicate<String> accept = nameFilter != null ? nameFilter : ImagePicker::isImageName;
        chooser.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isDirectory() || accept.test(file.getName());
            }

            @Override
            public String getDescription() {
                return tr("mcphone.picker.filter.image");
            }
        });

        chooser.setCurrentDirectory(defaultDirectory());
        // 详情视图更适合从一堆图里挑一张：文件名/日期/大小 三列与缩略图并排
        switchToDetailsView(chooser);
        // 每一行直接显示缩略图，不必点一下再看右边
        chooser.setFileView(new ThumbnailFileView(chooser));
        // 右侧大图预览
        chooser.setAccessory(previewPane(chooser));

        JFrame frame = new JFrame(tr("mcphone.picker.title"));
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(chooser, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        // always-on-top 只在窗口已经可见、可聚焦之后才可靠，抬到 Minecraft 全屏窗口之上
        // 靠的正是后面这句 toFront
        frame.setAlwaysOnTop(true);
        frame.toFront();

        Path result = null;
        try {
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                if (file != null && file.isFile()) {
                    result = file.toPath();
                }
            }
        } finally {
            frame.dispose();
        }
        return result;
    }

    /** 常见图片后缀。与 {@link ImageCodec} 能读的一致（webp 读不了，故意不收） */
    public static boolean isImageName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".png", ".jpg", ".jpeg", ".gif", ".bmp"}) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * FlatLaf 是进程级的：必须在任何 Swing 组件出现之前装好，装完也影响 JVM 其余部分。
     * Minecraft 基本不用 Swing，所以代价可以忽略。装不上就退回系统外观，不报错。
     */
    private static void installLookAndFeel() {
        if (lookAndFeelInstalled) return;
        lookAndFeelInstalled = true;
        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] FlatLaf 不可用，退回系统外观", t);
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Throwable t2) {
                MCphone.LOGGER.warn("[MCphone] 系统外观也不可用，保持 Swing 默认", t2);
            }
        }
    }

    /** 从玩家真正放图片的地方开始：Pictures / 图片 / 主目录 */
    private static File defaultDirectory() {
        String home = System.getProperty("user.home");
        File pictures = new File(home, "Pictures");
        if (pictures.isDirectory()) return pictures;
        File localized = new File(home, "图片");
        if (localized.isDirectory()) return localized;
        return new File(home);
    }

    /**
     * JFileChooser 没有公开的视图切换接口；FilePane 在 chooser 的 actionMap 上注册了
     * 标准动作，弹窗之前调一次"详情视图"那个动作，效果等同玩家点了工具栏上的按钮。
     */
    private static void switchToDetailsView(JFileChooser chooser) {
        Action details = chooser.getActionMap().get("viewTypeDetails");
        if (details != null) {
            details.actionPerformed(new ActionEvent(chooser, ActionEvent.ACTION_PERFORMED,
                    "viewTypeDetails"));
        }
    }

    //  右侧大图预览

    private static JComponent previewPane(JFileChooser chooser) {
        JLabel preview = new JLabel(tr("mcphone.picker.preview.none"), SwingConstants.CENTER);
        preview.setPreferredSize(new Dimension(160, 160));
        preview.setHorizontalAlignment(SwingConstants.CENTER);
        preview.setVerticalAlignment(SwingConstants.CENTER);
        preview.setForeground(new Color(0xFF8A8F98));
        preview.setOpaque(true);
        preview.setBackground(new Color(0xFFF2F3F5));

        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY, event -> {
            if (event.getNewValue() instanceof File file) {
                showPreview(preview, file);
            }
        });

        JPanel pane = new JPanel(new BorderLayout());
        pane.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        pane.add(preview, BorderLayout.CENTER);
        return pane;
    }

    private static void showPreview(JLabel target, File file) {
        if (file == null || !file.isFile() || !isImageName(file.getName())) {
            target.setText(tr("mcphone.picker.preview.none"));
            target.setIcon(null);
            return;
        }
        target.setText(tr("mcphone.picker.preview.loading"));
        target.setIcon(null);

        Thread loader = new Thread(() -> {
            BufferedImage image = decode(file, 160, 160);
            SwingUtilities.invokeLater(() -> {
                if (image == null) {
                    target.setText(tr("mcphone.picker.preview.none"));
                    target.setIcon(null);
                } else {
                    target.setText(null);
                    target.setIcon(new ImageIcon(image));
                }
            });
        }, "MCphone-Preview");
        loader.setDaemon(true);
        loader.start();
    }

    //  缩略图

    /**
     * 把每张图的缩略图作为它的文件图标。
     *
     * 第一帧先返回 null（用 FlatLaf 自带的文件图标），后台线程解出 48px 缩略图后再重画——
     * 解图绝不能发生在 EDT 上，否则目录一打开界面就卡住。
     */
    private static final class ThumbnailFileView extends FileView {
        private static final int THUMB_SIZE = 48;

        private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "MCphone-Thumbnail");
            t.setDaemon(true);
            return t;
        });

        private final JFileChooser chooser;
        private final ConcurrentMap<String, ImageIcon> cache = new ConcurrentHashMap<>();
        private final Set<String> loading = ConcurrentHashMap.newKeySet();

        private ThumbnailFileView(JFileChooser chooser) {
            this.chooser = chooser;
        }

        @Override
        public Icon getIcon(File file) {
            if (file == null || !file.isFile() || !isImageName(file.getName())) return null;

            String key = file.getAbsolutePath();
            ImageIcon cached = cache.get(key);
            if (cached != null) return cached;
            if (!loading.add(key)) return null;

            LOADER.execute(() -> {
                BufferedImage image = decode(file, THUMB_SIZE, THUMB_SIZE);
                if (image != null) {
                    cache.put(key, new ImageIcon(image));
                }
                loading.remove(key);
                if (image != null) {
                    SwingUtilities.invokeLater(chooser::repaint);
                }
            });
            return null;
        }
    }

    /**
     * 按比例解到不超过 maxWidth×maxHeight。
     *
     * 用 {@code ImageReadParam.setSourceSubsampling} 让解码器【跳像素】而不是全解再缩——
     * 一张 4K 截图因此只解出几百个像素，EDT 上不会卡。
     * ImageIO 读不了的（webp 等）返回 null。
     */
    static BufferedImage decode(File file, int maxWidth, int maxHeight) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;

            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                int step = Math.max(1, Math.min(
                        sourceWidth / Math.max(1, maxWidth),
                        sourceHeight / Math.max(1, maxHeight)));
                var params = reader.getDefaultReadParam();
                params.setSourceSubsampling(step, step, 0, 0);
                return fit(reader.read(0, params), maxWidth, maxHeight);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 不放大小图：小图按原尺寸显示，糊掉不如小着看 */
    private static BufferedImage fit(BufferedImage source, int maxWidth, int maxHeight) {
        if (source == null) return null;

        double scale = Math.min(1.0D, Math.min(
                maxWidth / (double) source.getWidth(),
                maxHeight / (double) source.getHeight()));
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage fitted = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = fitted.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return fitted;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
