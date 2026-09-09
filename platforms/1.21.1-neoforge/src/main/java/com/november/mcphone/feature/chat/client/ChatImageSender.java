package com.november.mcphone.feature.chat.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.GifCodec;
import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.chat.ChatImage;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ImageBody;
import com.november.mcphone.feature.chat.net.SendChatImagePacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把相册里的一张照片发给好友：压 → 切片 → 发。
 *
 * 为什么要在客户端压
 *
 * 截图是全屏分辨率的，一张 1920×1080 的 PNG 常有两三 MB。原样发上去，一是根本发不动
 * （客户端发给服务端的包上限 32767 字节），二是服主要替所有人存那么大的文件。而手机
 * 气泡里那张最宽只有 80 个 GUI 像素，但图是点得开的，放大后要铺满整块屏幕，见 {@link ChatImage#MAX_SIDE}。
 *
 * 压完还是太大怎么办
 *
 * 降一档尺寸再压一次（见 {@link #SIDES}）。PNG 是无损的，一张噪点多的截图
 * （雨天、树叶、粒子）压出来能比一张干净的大好几倍，光按尺寸算压不出准头。
 *
 * 但【读盘与解码只做一次】：那是这条路上最贵的一步（一张 4096 的 PNG 解一遍就是一秒出头），
 * 每降一档重读一遍文件的话，光解码就能花掉三四秒，而玩家从点下去到看见气泡一直在等。
 *
 * 同一张只压一次
 *
 * 压出来的结果只取决于文件内容，所以按文件记在 {@link #ENCODED} 里。表情天生要反复发
 * 同一张，第二次起直接拿现成的字节走，那一段等待就没有了。
 *
 * 动图
 *
 * GIF 拆成帧、拼成一张雪碧图发出去（见 {@link ChatImage} 的"动图"一节）。压不进上限时
 * 先降帧的尺寸、再隔帧抽稀（见 {@link #ANIM_STEPS}）；抽到不成动画了就退回去发第一帧，
 * 那总比发不出去强。
 *
 * 一次只发一张，点快了的排队
 *
 * 同时只允许一次上传：压缩是异步的，两次上传交错着发上去，而服务端按"片号必须连续"收
 * （见 ChatImageUploads），交错的结果是两张都发不成。但"这会儿不能发"不等于"当你没点过"——
 * 表情天生就是要连着发的，冷却期里点的那几张排进 {@link #QUEUE}，闸一开自己走。
 * 排满了才提示一句"太快了"。
 *
 * 线程：读盘与压缩在后台，发包回到渲染线程——网络那一端不该被后台线程碰。
 */
public final class ChatImageSender {

    private ChatImageSender() {}

    /**
     * 依次试这几档长边，第一个压进上限的就是发出去的那一张。
     *
     * 第一档就是 {@link ChatImage#MAX_SIDE}；往下每降一档面积少三成多，噪点最狠的画面
     * 也能落进上限。
     */
    private static final int[] SIDES = {ChatImage.MAX_SIDE, 384, 320, 256, 192};

    /**
     * 动图的降档梯子：先降一帧的尺寸，再隔帧抽稀。
     *
     * 顺序是"先试更好看的那个"：尺寸掉一档只是糊一点，抽帧掉的是流畅度，而一张动图
     * 之所以是动图，靠的正是流畅。所以 160→128 走在抽帧前面。
     *
     * 帧最宽 224：气泡里显示出来只有 80 像素宽，但图是点得开的，放大之后铺满内容区，
     * 在 4 倍 GUI 缩放下是 448 个真实像素。再往上收益就很薄了，而体积按面积涨。
     * 帧数多的时候一帧会自动更小——雪碧图整张有上限，见 {@link #frameSide}。
     */
    private record AnimStep(int side, int keepEvery) {}

    private static final AnimStep[] ANIM_STEPS = {
            new AnimStep(224, 1),
            new AnimStep(176, 1),
            new AnimStep(128, 1),
            new AnimStep(96, 2),
            new AnimStep(96, 3),
    };

    /** 压好的字节留几张。一张至多 {@link ChatImage#MAX_BYTES_CEILING}，八张封顶 6 MB */
    private static final int MAX_CACHED = 8;

    /**
     * 压好的字节：文件 → 已经压进上限的那一张 PNG。
     *
     * 键里带上改动时间与大小（见 {@link #cacheKey}），是为了让"同名文件被换掉"——
     * 重新导一张同名表情——之后旧的那份失效。
     *
     * 退出世界不清：表情跟着客户端走，下一个服务器里发的多半还是这几张。
     *
     * 同步包着：读写都在后台线程，而访问序的 LinkedHashMap 连 get 都会改结构。
     */
    private static final Map<String, ImageCodec.Encoded> ENCODED = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHED + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ImageCodec.Encoded> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    /** 排队的位置。四张是一次手快的量，再多就不是"手快"而是刷屏了 */
    private static final int MAX_QUEUED = 4;

    /** 排着等发的一张 */
    private record Queued(UUID peer, Path photo) {}

    /** 只有主线程碰它：点击、拖放、客户端 tick 都在主线程 */
    private static final Deque<Queued> QUEUE = new ArrayDeque<>();

    /** 一次上传最多允许拖这么久，超时就当它没发出去，放开下一次 */
    private static final long SEND_TIMEOUT_MS = 15_000L;

    /**
     * 发完一张之后多久才能再发。与服务端 RequestThrottle 的 CHAT_IMAGE 一致。
     *
     * 客户端这边也拦一道，是为了别让正常操作撞上服务端那道闸：撞上了服务端只会回一句
     * "缓一下"，而客户端还在等一个永远不会来的回声，那个键要一直灰到超时。
     */
    private static final long COOLDOWN_MS = 2_000L;

    private static long sendingSince;

    /** 冷却到期的时刻 */
    private static long readyAt;

    /** 刚发上去的那张图的字节，等回声带着 id 回来时塞进缓存，见 {@link #onNewMessage} */
    private static byte[] pendingPng;

    /** 这会儿有一张正在发，或者刚发完还在冷却。再点的会排队，见 {@link #send} */
    public static boolean isBusy() {
        long now = System.currentTimeMillis();

        if (sendingSince != 0L) {
            if (now - sendingSince <= SEND_TIMEOUT_MS) return true;
            // 服务端拒收时不会有回声（拒收的理由它已经单独说过了），不能让界面永远卡在"发送中"
            finish();
        }
        return now < readyAt;
    }

    /** 队伍也满了，这一下真的收不下。界面据此把「+」画成灰的并且点不动 */
    public static boolean isFull() {
        return QUEUE.size() >= MAX_QUEUED;
    }

    /**
     * 发一张。立刻返回，压缩与发包都在后面。
     *
     * 正忙着就排队而不是丢掉：玩家点了一下，界面上却什么都没发生，那看起来是消息丢了，
     * 而不是"缓一下"。队伍满了才提示一句。
     *
     * @param peer  收件人
     * @param photo 相册或表情目录里那张图的路径
     */
    public static void send(UUID peer, Path photo) {
        if (peer == null || photo == null) return;

        if (isBusy()) {
            if (isFull()) tell("mcphone.chat.image_too_fast");
            else QUEUE.add(new Queued(peer, photo));
            return;
        }
        start(peer, photo);
    }

    /**
     * 闸一开就把排在头里的那张发出去。挂在客户端 tick 上，见 MCphoneClient。
     *
     * 挂 tick 而不是挂会话界面的每帧：玩家点完表情就退出手机是常事，那一张照样该发出去。
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (QUEUE.isEmpty() || isBusy()) return;

        Queued next = QUEUE.poll();
        start(next.peer(), next.photo());
    }

    /** 真的开始发这一张：压缩在后台，发包回渲染线程 */
    private static void start(UUID peer, Path photo) {
        sendingSince = System.currentTimeMillis();
        pendingPng = null;

        // 上限在这里取，不在后台线程里取：那是服主的配置（连上来时同步过来的），
        // 读它是主线程的事，而且一次上传从头到尾该按同一个数来
        final int maxBytes = ChatImage.maxBytes();

        Util.backgroundExecutor().execute(() -> {
            Attempt attempt;
            try {
                attempt = encodeWithinLimit(photo, maxBytes);
            } catch (RuntimeException | OutOfMemoryError e) {
                // 在后台线程里抛出去等于没人接：界面会一直"发送中"到 15 秒超时，
                // 而玩家一句话也看不到。兜住，当"打不开"报出去
                MCphone.LOGGER.warn("[MCphone] 压这张图时出错 {}: {}", photo.getFileName(), e.toString());
                attempt = Attempt.failed("mcphone.chat.image_unreadable");
            }
            Attempt done = attempt;
            Minecraft.getInstance().execute(() -> upload(peer, done));
        });
    }

    /**
     * 压缩的结果：成了带着那张 PNG，没成带着要跟玩家说的那句话。
     *
     * 为什么失败也得分个类：以前不论什么原因失败都只说一句"这张图太大了，压不进上限"，
     * 而实际最常见的失败根本不是大小——是【压根读不出来】：改了扩展名的 WebP、下到一半的
     * 文件。玩家照着那句话去换更小的图，换几张都一样发不出去，因为问题不在那儿。
     */
    private record Attempt(ImageCodec.Encoded encoded, String failureKey) {

        static Attempt of(ImageCodec.Encoded encoded) { return new Attempt(encoded, null); }

        static Attempt failed(String failureKey) { return new Attempt(null, failureKey); }
    }

    /**
     * 压到上限之内。在后台线程。
     *
     * 读盘、解码、缩到最大那一档，这三件事一共只做一次：往下几档都从那张 384 的再缩。
     * 降档是为了压体积，不是为了更清楚，而 384 → 320 只有 0.83 倍，一次插值就够
     * （{@link ImageCodec#scaleDown} 的逐级减半是给"缩掉一半以上"准备的）。
     */
    private static Attempt encodeWithinLimit(Path photo, int maxBytes) {
        String key = cacheKey(photo, maxBytes);
        if (key != null) {
            ImageCodec.Encoded cached = ENCODED.get(key);
            if (cached != null) return Attempt.of(cached);
        }

        // 动图先按动图试；不是动图、或者怎么抽都塞不进上限，就当一张静态图发第一帧
        ImageCodec.Encoded animated = encodeAnimated(photo, maxBytes);
        Attempt attempt = animated != null ? Attempt.of(animated) : encodeStill(photo, maxBytes);

        if (attempt.encoded() != null && key != null) ENCODED.put(key, attempt.encoded());
        return attempt;
    }

    /** 静态图那条路：缩到最大那一档，压不进上限就一档档往下降 */
    private static Attempt encodeStill(Path photo, int maxBytes) {
        BufferedImage src = ImageCodec.read(photo);
        if (src == null) return Attempt.failed(unreadableKey(photo));

        BufferedImage base = ImageCodec.scaleDown(src, ChatImage.MAX_SIDE);

        for (int side : SIDES) {
            ImageCodec.Encoded encoded = ImageCodec.encodePng(base, side);
            if (encoded != null && encoded.png().length <= maxBytes) return Attempt.of(encoded);
        }
        // 走到这儿才是真的"太大了"：最小那一档 192 像素还压不进服主定的上限
        return Attempt.failed("mcphone.chat.image_encode_failed");
    }

    /**
     * 读不出来时该说哪一句。
     *
     * 单把 WebP 拎出来，是因为它是最常见的那一种：现在从网上存下来的"GIF 表情"很多其实是
     * .webp，而把扩展名改成 .gif 不管用——ImageIO 没有 WebP 解码器，认的也是文件头。
     * 玩家看到"格式不认得"多半会再试一次同样的操作，看到"这是 WebP"才知道该去转一下。
     */
    private static String unreadableKey(Path photo) {
        return looksLikeWebp(photo) ? "mcphone.chat.image_webp" : "mcphone.chat.image_unreadable";
    }

    /** RIFF....WEBP。只认文件头：扩展名是拖进来的那个人说了算的 */
    private static boolean looksLikeWebp(Path photo) {
        try (InputStream in = Files.newInputStream(photo)) {
            byte[] head = in.readNBytes(12);
            return head.length == 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 动图那条路：拆帧 → 拼雪碧图 → 压。不是动图、或者每一档都压不进上限，返回 null。
     *
     * 抽稀之后延迟要跟着乘上去：每两帧取一帧，剩下的每一帧就得停两倍的时间，
     * 不然整张动图会播成两倍速。
     */
    private static ImageCodec.Encoded encodeAnimated(Path photo, int maxBytes) {
        // 帧数上限与"留下来的那一帧多大"都在这一步就交代清楚：拆帧那边照着办，
        // 它才不必把每一帧都按原尺寸留在堆里（见 GifCodec 的"内存"一节）
        GifCodec.Animation anim = GifCodec.read(photo, ANIM_STEPS[0].side(), ChatImage.MAX_FRAMES);
        if (anim == null) return null;

        List<BufferedImage> all = anim.frames();

        for (AnimStep ladder : ANIM_STEPS) {
            int step = ladder.keepEvery();

            List<BufferedImage> picked = new ArrayList<>();
            for (int i = 0; i < all.size(); i += step) picked.add(all.get(i));

            // 这条闸只管抽稀，不管原图本来有几帧：本来就只有三帧的眨眼表情该原样发出去，
            // 拿它跟"抽到只剩三帧"一视同仁的话，短动图就永远发不成动图
            if (picked.size() < Math.min(ChatImage.MIN_FRAMES, all.size())) continue;

            BufferedImage sheet = sheet(picked, frameSide(ladder.side(), picked.size()));
            if (sheet == null) continue;

            ImageCodec.Encoded encoded = ImageCodec.encodePng(sheet, ChatImage.SHEET_MAX_SIDE);
            if (encoded == null || encoded.png().length > maxBytes) continue;

            // encodePng 记的是整张雪碧图的大小，这里换成一帧的——界面排版要的是一帧多大
            int cols = ChatImage.cols(picked.size());
            return new ImageCodec.Encoded(encoded.png(),
                    encoded.width() / cols, encoded.height() / ChatImage.rows(picked.size()),
                    picked.size(), anim.frameMs() * step);
        }
        return null;
    }

    /** 这么多帧摆开之后，一帧最多还能有多大——雪碧图整张不能超过 {@link ChatImage#SHEET_MAX_SIDE} */
    private static int frameSide(int wanted, int frames) {
        int grid = Math.max(ChatImage.cols(frames), ChatImage.rows(frames));
        return Math.max(16, Math.min(wanted, ChatImage.SHEET_MAX_SIDE / grid));
    }

    /**
     * 把这几帧摆成一张网格图，每帧等比缩到长边不超过 side。
     *
     * 缩放走 {@link ImageCodec#scaleDown} 的逐级减半，而不是一步拉到位：一步到位等于
     * 每个目标像素只采样了源图的 2×2，细密的表情缩完全是噪点。
     */
    private static BufferedImage sheet(List<BufferedImage> frames, int side) {
        BufferedImage first = ImageCodec.scaleDown(frames.get(0), side);
        int fw = first.getWidth();
        int fh = first.getHeight();
        if (fw <= 0 || fh <= 0) return null;

        int cols = ChatImage.cols(frames.size());
        BufferedImage sheet = new BufferedImage(
                cols * fw, ChatImage.rows(frames.size()) * fh, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = sheet.createGraphics();
        // Src 而不是 SrcOver：每一格都是独立的一帧，要的是照抄，不是叠上去
        g.setComposite(AlphaComposite.Src);
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage frame = i == 0 ? first : ImageCodec.scaleDown(frames.get(i), side);
            g.drawImage(frame, (i % cols) * fw, (i / cols) * fh, fw, fh, null);
        }
        g.dispose();
        return sheet;
    }

    /**
     * {@link #ENCODED} 的键：路径 + 改动时间 + 大小 + 当时的字节上限。属性读不到就返回 null，
     * 这一张不缓存。
     *
     * 上限也要进键：换一台服务器就可能换一个上限，而按 512 KB 压出来的那一张，
     * 在只收 128 KB 的服务器上是发不出去的。
     */
    private static String cacheKey(Path photo, int maxBytes) {
        try {
            return photo.toAbsolutePath() + "|" + Files.getLastModifiedTime(photo).toMillis()
                    + "|" + Files.size(photo) + "|" + maxBytes;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 切片发上去。在渲染线程 */
    private static void upload(UUID peer, Attempt attempt) {
        ImageCodec.Encoded encoded = attempt.encoded();
        if (encoded == null) {
            tell(attempt.failureKey());
            finish();
            return;
        }
        if (Minecraft.getInstance().getConnection() == null) {
            finish();   // 压缩那会儿工夫里断线了
            return;
        }

        byte[] png = encoded.png();
        int chunkCount = (png.length + ChatImage.CHUNK_BYTES - 1) / ChatImage.CHUNK_BYTES;

        for (int index = 0; index < chunkCount; index++) {
            int from = index * ChatImage.CHUNK_BYTES;
            int to = Math.min(png.length, from + ChatImage.CHUNK_BYTES);

            byte[] chunk = new byte[to - from];
            System.arraycopy(png, from, chunk, 0, chunk.length);

            MCphoneNetwork.sendToServer(new SendChatImagePacket(
                    peer, encoded.width(), encoded.height(), encoded.frames(), encoded.frameMs(),
                    index, chunkCount, chunk));
        }

        // 等回声。同一条连接上包是有序的，服务端拼齐后会把消息发回来
        pendingPng = png;
    }

    /**
     * 收到任何一条新消息时都过一道：如果是自己刚发的那张图的回声，把像素直接塞进缓存。
     *
     * 回声里只有图片 id（见 ImageBody），不这么做的话，发件人会看着自己刚发出去的图
     * 转一圈"加载中"，再从服务器把自己上传的东西下回来。
     *
     * 由 {@link ChatNotifier#onMessage} 转调——那里本来就是"每条新消息都会经过"的地方。
     */
    public static void onNewMessage(ChatMessage message) {
        if (pendingPng == null) return;
        if (!(message.body() instanceof ImageBody image)) return;

        var player = Minecraft.getInstance().player;
        if (player == null || !message.sender().equals(player.getUUID())) return;

        ChatImageCache.seed(image.image(), pendingPng);
        finish();
    }

    /**
     * 退出世界时清掉，免得下一个服务器里冒出一次莫名其妙的"发送中"，也不必带着冷却过去。
     *
     * 排着的那几张一并倒掉：收件人是上一个服务器里的人，换个地方发过去没有意义。
     * 压好的字节留着（{@link #ENCODED}）——那只跟文件有关，跟在哪个服务器无关。
     */
    public static void clear() {
        sendingSince = 0L;
        pendingPng = null;
        readyAt = 0L;
        QUEUE.clear();
    }

    /** 一次上传就此结束（成了、超时了、或者压根没发出去），并开始冷却 */
    private static void finish() {
        sendingSince = 0L;
        pendingPng = null;
        readyAt = System.currentTimeMillis() + COOLDOWN_MS;
    }

    private static void tell(String translationKey) {
        var player = Minecraft.getInstance().player;
        // 与服务端拒收时同一个位置：动作栏。玩家的眼睛正看着手机屏幕，聊天框那一行他看不见
        if (player != null) player.displayClientMessage(Component.translatable(translationKey), true);
    }
}
