package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 把一张 GIF 拆成一叠画好的帧。只有客户端用得上——服务端从不解码图片。
 *
 * 为什么不能直接用 ImageIO.read(i) 当作第 i 帧
 *
 * GIF 的每一帧存的是【与上一帧的差】：它可能只有画面的一小块（带自己的偏移），
 * 而画完之后要按 disposal 决定这一块怎么收场——留着、擦成透明、还是还原成上一帧。
 * 逐帧直接读出来的是那些碎片，不是玩家看到的画面。所以这里自己铺一张画布，
 * 一帧一帧盖上去，每盖完一张就拍一张快照。
 *
 * 内存：边合成边缩，不留全分辨率的帧
 *
 * 差分要在原尺寸上合成才对，但【留下来】的那一份不必是原尺寸——发出去的一帧最宽也就
 * 一百多像素。逐帧留全分辨率的话，一张 100 帧 512 见方的 GIF 就是两百兆堆，
 * 在玩家那台正跑着游戏的机器上这是会出事的。所以画布只有一张，每留一帧就当场缩到
 * 调用方要的尺寸；帧数超过上限时更是连留都不留（见 {@code keepEvery}）。
 *
 * 延迟只取一个数
 *
 * GIF 允许每帧的停留时间都不一样，但表情几乎都是匀速的，而把一串延迟塞进每条聊天消息
 * 会让消息本身胖一圈。所以取出现次数最多的那个值（见 {@link #commonDelay}），
 * 代价是极少数不匀速的动图会被拉匀。
 *
 * 0 与 10 毫秒当 100 处理：那是历史遗留，浏览器几十年来都是这么干的——
 * 真按 0 播，一秒钟几百帧，看到的是一团糊。
 *
 * 线程：读盘与解码，后台线程调用。解不动一律返回 null，绝不抛给调用方——
 * 那段字节可能是玩家从任何地方拖进来的。
 */
public final class GifCodec {

    private GifCodec() {}

    /** 拆好的一张动图：每一帧都是画好的整幅画面，尺寸一致 */
    public record Animation(List<BufferedImage> frames, int frameMs) {}

    /** 延迟低于这个数的一律按 {@link #DEFAULT_DELAY_MS} 算，理由见类注释 */
    private static final int MIN_DELAY_MS = 20;

    private static final int DEFAULT_DELAY_MS = 100;

    /** 再离谱的动图也不让它一帧停超过这么久——那多半是坏元数据 */
    private static final int MAX_DELAY_MS = 5000;

    /**
     * 读一张 GIF 的帧。
     *
     * 不是 GIF、只有一帧、或者读不动，都返回 null——调用方按静态图那条路走就是了。
     *
     * @param maxSide 留下来的每一帧缩到长边不超过这么大。调用方按自己最大的那一档给，
     *                再往下降档时从这几帧上接着缩就够了
     * @param maxFrames 至多留这么多帧；原图更长就隔帧留，返回的 frameMs 会跟着乘上去
     */
    public static Animation read(Path path, int maxSide, int maxFrames) {
        if (!looksLikeGif(path)) return null;

        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) return null;
        ImageReader reader = readers.next();

        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            if (in == null) return null;
            reader.setInput(in, false);

            int count = reader.getNumImages(true);
            if (count <= 1) return null;   // 单帧 GIF 就是一张静态图

            return compose(reader, count, Math.max(1, maxSide), Math.max(1, maxFrames));
        } catch (IOException | RuntimeException e) {
            MCphone.LOGGER.warn("[MCphone] GIF 读取失败 {}: {}", path.getFileName(), e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            MCphone.LOGGER.warn("[MCphone] GIF 过大，内存不足: {}", path.getFileName());
            return null;
        } finally {
            reader.dispose();
        }
    }

    /** 只认文件头，不认扩展名：扩展名是拖进来的人说了算的 */
    private static boolean looksLikeGif(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] magic = in.readNBytes(6);
            return magic.length == 6
                    && magic[0] == 'G' && magic[1] == 'I' && magic[2] == 'F'
                    && magic[3] == '8' && (magic[4] == '7' || magic[4] == '9') && magic[5] == 'a';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 只要第一帧，但要的是【铺在逻辑屏幕上】的那一整幅。不是 GIF、或者读不动，返回 null。
     *
     * 为什么不能就地用 ImageIO.read(文件)
     *
     * 那个方法给的是 GIF 里的第一个【子图】，而子图不等于画面：它可能只有画面的一小块，
     * 还带着自己的偏移。优化过的动图开头很常见这种形状——透明的边被裁掉了，也有工具会先摆
     * 一个 1×1 的占位帧。把子图直接当成"这张图长什么样"，表情页里就是一个缩得莫名其妙的
     * 小方块（碰上占位帧则干脆是一个点），而它同时也是发送那条路的兜底
     * （见 ChatImageSender.encodeStill）——那就等于把那一小块发给了对方。
     *
     * 所以这里与 {@link #read} 用同一套：按逻辑屏幕铺画布，把第一帧摆到它该在的位置上。
     * 单帧 GIF 也走这里——{@link #read} 对它返回 null，画面却一样要铺对。
     */
    public static BufferedImage firstFrame(Path path) {
        if (!looksLikeGif(path)) return null;

        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) return null;
        ImageReader reader = readers.next();

        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            if (in == null) return null;
            reader.setInput(in, false);

            Dimension size = canvasSize(reader, 1);
            if (size == null) return null;

            BufferedImage frame = reader.read(0);
            Descriptor d = descriptorOf(reader.getImageMetadata(0));

            BufferedImage canvas = new BufferedImage(size.width, size.height,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(frame, d.x(), d.y(), null);
            g.dispose();
            return canvas;
        } catch (IOException | RuntimeException e) {
            MCphone.LOGGER.warn("[MCphone] GIF 首帧读取失败 {}: {}", path.getFileName(), e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            MCphone.LOGGER.warn("[MCphone] GIF 过大，内存不足: {}", path.getFileName());
            return null;
        } finally {
            reader.dispose();
        }
    }

    /**
     * 一帧一帧铺到画布上，每铺完一张就（按需）拍一张缩过的快照。
     *
     * 每一帧都要合成，哪怕它不会被留下：跳过的话后面那些差分就没有底可差了。
     * 但只有留下的那几帧才拍快照，而快照是缩过的——理由见类注释。
     */
    private static Animation compose(ImageReader reader, int count, int maxSide, int maxFrames)
            throws IOException {

        Dimension size = canvasSize(reader, count);
        if (size == null) return null;

        // 帧数超上限就隔着留。每留一帧就相当于把中间跳过的那几帧的时间也算给它
        int keepEvery = Math.max(1, (count + maxFrames - 1) / maxFrames);

        List<BufferedImage> out = new ArrayList<>();
        List<Descriptor> kept = new ArrayList<>();
        BufferedImage canvas = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);

        for (int i = 0; i < count; i++) {
            BufferedImage frame = reader.read(i);
            Descriptor d = descriptorOf(reader.getImageMetadata(i));

            // restoreToPrevious 要在画之前留一份底稿：那是"这一帧结束后回到画它之前的样子"
            BufferedImage previous = "restoreToPrevious".equals(d.disposal) ? copy(canvas) : null;

            Graphics2D g = canvas.createGraphics();
            g.drawImage(frame, d.x, d.y, null);   // 默认 SrcOver：GIF 的透明像素要让下面透出来
            g.dispose();

            if (i % keepEvery == 0) {
                out.add(ImageCodec.scaleDown(copy(canvas), maxSide));
                kept.add(d);
            }

            if ("restoreToBackgroundColor".equals(d.disposal)) {
                // 擦成透明而不是填背景色：手机里没有"背景色"这回事，图是画在壁纸上的
                Graphics2D clear = canvas.createGraphics();
                clear.setComposite(java.awt.AlphaComposite.Clear);
                clear.fillRect(d.x, d.y, frame.getWidth(), frame.getHeight());
                clear.dispose();
            } else if (previous != null) {
                canvas = previous;
            }
        }

        if (out.size() < 2) return null;   // 抽完只剩一帧，那就是静态图
        return new Animation(List.copyOf(out), commonDelay(kept) * keepEvery);
    }

    /**
     * 画布多大：优先信流元数据里的"逻辑屏幕"，那是 GIF 自己声明的画面尺寸。
     *
     * 取不到就退回去看第一帧——扫完所有帧再取最大值当然更准，但那要把每一帧都解一遍，
     * 而这一步的目的正是不解两遍。畸形到连逻辑屏幕都没有的 GIF，超出画布的部分被裁掉，
     * 总好过为它把所有图都读两遍。
     */
    private static Dimension canvasSize(ImageReader reader, int count) throws IOException {
        try {
            Node root = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
            for (Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (!"LogicalScreenDescriptor".equals(c.getNodeName())) continue;
                NamedNodeMap at = c.getAttributes();
                if (at == null) continue;
                int w = intAttr(at, "logicalScreenWidth", 0);
                int h = intAttr(at, "logicalScreenHeight", 0);
                if (w > 0 && h > 0) return new Dimension(w, h);
            }
        } catch (IOException | RuntimeException e) {
            // 没有流元数据，退回去看第一帧
        }
        if (count <= 0) return null;
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        return w > 0 && h > 0 ? new Dimension(w, h) : null;
    }

    private record Descriptor(int x, int y, int delayMs, String disposal) {}

    /**
     * 从一帧的元数据里取偏移、延迟、disposal。
     *
     * 缺哪一项就用默认值：畸形的 GIF 满地都是，而"少一个属性"不值得让整张图发不出去。
     */
    private static Descriptor descriptorOf(IIOMetadata meta) {
        int x = 0;
        int y = 0;
        int delay = DEFAULT_DELAY_MS;
        String disposal = "none";

        Node root = meta.getAsTree("javax_imageio_gif_image_1.0");
        for (Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) {
            NamedNodeMap at = c.getAttributes();
            if (at == null) continue;

            if ("GraphicControlExtension".equals(c.getNodeName())) {
                delay = intAttr(at, "delayTime", DEFAULT_DELAY_MS / 10) * 10;
                Node d = at.getNamedItem("disposalMethod");
                if (d != null) disposal = d.getNodeValue();
            } else if ("ImageDescriptor".equals(c.getNodeName())) {
                x = intAttr(at, "imageLeftPosition", 0);
                y = intAttr(at, "imageTopPosition", 0);
            }
        }
        if (delay < MIN_DELAY_MS) delay = DEFAULT_DELAY_MS;
        return new Descriptor(x, y, Math.min(delay, MAX_DELAY_MS), disposal);
    }

    private static int intAttr(NamedNodeMap attrs, String name, int fallback) {
        Node node = attrs.getNamedItem(name);
        if (node == null) return fallback;
        try {
            return Integer.parseInt(node.getNodeValue());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 出现次数最多的那个延迟；并列时取小的，宁可播快一点也别把一张动图拖成幻灯片 */
    private static int commonDelay(List<Descriptor> descriptors) {
        Map<Integer, Integer> tally = new HashMap<>();
        for (Descriptor d : descriptors) tally.merge(d.delayMs(), 1, Integer::sum);

        int best = DEFAULT_DELAY_MS;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> e : tally.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() < best)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }
}
