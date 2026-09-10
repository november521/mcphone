package com.november.mcphone.feature;

import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.core.net.PhoneScreenOnPacket;
import com.november.mcphone.feature.enderchest.net.OpenEnderChestPacket;
import com.november.mcphone.feature.settings.net.SetDeviceNamePacket;
import com.november.mcphone.feature.settings.net.SetWallpaperPacket;
import com.november.mcphone.feature.settings.net.SyncWallpaperPacket;
import com.november.mcphone.feature.store.PurchasedApps;
import com.november.mcphone.feature.store.net.PurchaseAppPacket;
import com.november.mcphone.feature.store.net.RequestPurchasedAppsPacket;
import com.november.mcphone.feature.store.net.SyncPurchasedAppsPacket;
import com.november.mcphone.feature.terminal.net.TerminalActionPacket;
import com.november.mcphone.feature.waystone.net.OpenWaystoneSelectionPacket;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 商店、终端、末影箱、传送石、设置这几组包的线格式断言。
 *
 * <h2>为什么写它</h2>
 *
 * 这几组的编解码正要从组合子换成手写的 {@code encode} / {@code decode}，为的是让
 * 1.20.1 那一支能用同一份代码 —— 那边没有 {@code StreamCodec} 与 {@code ByteBufCodecs}。
 * <b>换写法不许换字节</b>，而两者之间最容易出的岔子是无声的：字段顺序调个个儿、
 * 上限写错、枚举的序号对不上。收发两侧一起改的话自己跟自己还能对上，跟旧版本的客户端
 * 就对不上了。
 *
 * 所以这里断言的是<b>字节序列本身</b>。先对着组合子那一版跑绿，再对着手写那一版跑，
 * 两次都绿才说明格式没动。
 *
 * <h2>缓冲区用 RegistryFriendlyByteBuf</h2>
 *
 * 这一组里 {@code PurchaseAppPacket} 的编解码器声明在 {@code RegistryFriendlyByteBuf}
 * 上，而它是 {@code FriendlyByteBuf} 的<b>子类</b>：{@code StreamCodec<RegistryFriendlyByteBuf, T>}
 * 不是 {@code StreamCodec<? super FriendlyByteBuf, T>}，用父类缓冲区传不进去。
 * 统一收 {@code ? super RegistryFriendlyByteBuf} 并给一个 RegistryFriendlyByteBuf，
 * 三种声明（ByteBuf / FriendlyByteBuf / RegistryFriendlyByteBuf）就都吃得下。
 *
 * 注册表给的是 {@link RegistryAccess#EMPTY}：这几个包一个都不查注册表，
 * ResourceLocation 走的是纯字符串。
 *
 * <h2>各包的线格式</h2>
 *
 * <pre>
 * RequestPurchasedApps    （空包，零字节）
 * OpenEnderChest          （空包，零字节）
 * OpenWaystoneSelection   （空包，零字节）
 * TerminalAction          VarInt 枚举序号（认不出的退回 OPEN_SLOT_MENU）
 * SetWallpaper            Utf8(32767) 文件名
 * SyncWallpaper           Utf8(32767) 文件名
 * PurchaseApp             Utf8 ResourceLocation
 * PurchasedApps           VarInt 条数(≤256), 然后逐个 Utf8 ResourceLocation
 * SyncPurchasedApps       PurchasedApps
 * SetDeviceName           Utf8(24) 名字, 然后 PhoneLocation
 * </pre>
 */
public class MiscPacketCodecTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    static RegistryFriendlyByteBuf buf(byte[] raw) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(raw), RegistryAccess.EMPTY);
    }

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static <T> byte[] bytes(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf b = buf();
        codec.encode(b, value);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        return out;
    }

    static <T> void eqBytes(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T value,
                            byte[] expected, String what) {
        checks++;
        byte[] actual = bytes(codec, value);
        if (!Arrays.equals(actual, expected)) {
            failures.add(what + "  期望 " + Arrays.toString(expected)
                    + "，实际 " + Arrays.toString(actual));
        }
    }

    static <T> void roundTrip(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T value, String what) {
        checks++;
        T back = codec.decode(buf(bytes(codec, value)));
        if (!value.equals(back)) {
            failures.add(what + "  往返之后变了：" + value + " -> " + back);
        }
    }

    /** 编完之后缓冲区必须正好读空。只报得出"读少了"；读多了会在 decode 里直接抛 */
    static <T> void exact(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T value, String what) {
        checks++;
        RegistryFriendlyByteBuf b = buf();
        codec.encode(b, value);
        codec.decode(b);
        if (b.readableBytes() != 0) {
            failures.add(what + "  解完还剩 " + b.readableBytes() + " 字节没读");
        }
    }

    /**
     * 喂一段伪造的字节，断言解码侧拒收，<b>而且抛的是 DecoderException</b>。
     *
     * 类型要追究，因为只有它会被 netty 当成解码失败正常处理。组合子那一版在"条数为负"
     * 这一条上抛的是 {@code IllegalArgumentException}（{@code ByteBufCodecs.collection}
     * 里 {@code -1 > max} 为假，直接落到 {@code new ArrayList<>(-1)} —— 这是 vanilla
     * 自己的洞，不是本仓引入的）。换成 {@code Wire.readList} 之后才是 DecoderException。
     */
    static <T> void decodeRejects(StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                  byte[] raw, String what) {
        checks++;
        try {
            T got = codec.decode(buf(raw));
            failures.add(what + "  应当拒收，实际解出了 " + got);
        } catch (DecoderException expected) {
            // 正是要的
        } catch (RuntimeException other) {
            failures.add(what + "  抛的是 " + other.getClass().getSimpleName()
                    + " 而不是 DecoderException：" + other.getMessage());
        }
    }

    /** "mcphone:notes" 这类 id 在线上就是一个 Utf8 字符串 */
    static byte[] utf(String s) {
        byte[] raw = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length + 1];
        out[0] = (byte) raw.length;                 // 长度都 < 128，VarInt 就是一个字节
        System.arraycopy(raw, 0, out, 1, raw.length);
        return out;
    }

    public static void main(String[] args) {

        ResourceLocation notes = ResourceLocation.fromNamespaceAndPath("mcphone", "notes");
        ResourceLocation camera = ResourceLocation.fromNamespaceAndPath("mcphone", "camera");

        //  一、上限与枚举序号：它们进线格式，改一个就是协议变更
        eq(SetDeviceNamePacket.MAX_NAME_LENGTH, 24, "SetDeviceNamePacket.MAX_NAME_LENGTH");
        eq(PurchasedApps.MAX_COUNT, 256, "PurchasedApps.MAX_COUNT");
        eq(TerminalActionPacket.Action.OPEN_TERMINAL.ordinal(), 0, "OPEN_TERMINAL 的序号");
        eq(TerminalActionPacket.Action.OPEN_SLOT_MENU.ordinal(), 1, "OPEN_SLOT_MENU 的序号");

        //  二、三个空包：一个字节都不该写
        // 多写一个字节，对面就会把它当成下一个包的开头
        eqBytes(RequestPurchasedAppsPacket.STREAM_CODEC, new RequestPurchasedAppsPacket(),
                new byte[]{}, "RequestPurchasedApps 是零字节");
        eqBytes(OpenEnderChestPacket.STREAM_CODEC, new OpenEnderChestPacket(),
                new byte[]{}, "OpenEnderChest 是零字节");
        eqBytes(OpenWaystoneSelectionPacket.STREAM_CODEC, new OpenWaystoneSelectionPacket(),
                new byte[]{}, "OpenWaystoneSelection 是零字节");

        //  三、终端动作：枚举按【序号】上线，不是名字
        eqBytes(TerminalActionPacket.STREAM_CODEC,
                new TerminalActionPacket(TerminalActionPacket.Action.OPEN_TERMINAL),
                new byte[]{0}, "TerminalAction(OPEN_TERMINAL)");
        eqBytes(TerminalActionPacket.STREAM_CODEC,
                new TerminalActionPacket(TerminalActionPacket.Action.OPEN_SLOT_MENU),
                new byte[]{1}, "TerminalAction(OPEN_SLOT_MENU)");
        // 认不出的序号退回 OPEN_SLOT_MENU 而不是抛：版本不一致或伪造客户端送来未知值时，
        // 宁可退回一个无害的动作，也不要打断整条连接
        eq(TerminalActionPacket.STREAM_CODEC.decode(buf(new byte[]{9})),
                new TerminalActionPacket(TerminalActionPacket.Action.OPEN_SLOT_MENU),
                "未知序号 9 退回 OPEN_SLOT_MENU");
        eq(TerminalActionPacket.STREAM_CODEC.decode(buf(new byte[]{(byte) 0xFF, (byte) 0xFF,
                        (byte) 0xFF, (byte) 0xFF, 0x0F})),
                new TerminalActionPacket(TerminalActionPacket.Action.OPEN_SLOT_MENU),
                "序号 -1 退回 OPEN_SLOT_MENU");

        //  四、壁纸：一个字符串
        eqBytes(SetWallpaperPacket.STREAM_CODEC, new SetWallpaperPacket(""),
                new byte[]{0}, "SetWallpaper 空串只有一个 0");
        eqBytes(SetWallpaperPacket.STREAM_CODEC, new SetWallpaperPacket("a.png"),
                utf("a.png"), "SetWallpaper(\"a.png\")");
        eqBytes(SyncWallpaperPacket.STREAM_CODEC, new SyncWallpaperPacket("a.png"),
                utf("a.png"), "SyncWallpaper 与 SetWallpaper 同格式");

        //  五、ResourceLocation 就是它的字符串形式
        eqBytes(PurchaseAppPacket.STREAM_CODEC, new PurchaseAppPacket(notes),
                utf("mcphone:notes"), "PurchaseApp(mcphone:notes)");

        //  六、已购列表：VarInt 条数在前
        // 只钉 0 条与 1 条 —— PurchasedApps 内部是 Set，多于一条时的【迭代顺序不保证】
        // （Set.copyOf 的顺序每次 JVM 启动都可能不同），所以多条只测往返，不测字节
        eqBytes(PurchasedApps.STREAM_CODEC, new PurchasedApps(Set.of()),
                new byte[]{0}, "PurchasedApps 空集只有一个 0");
        byte[] oneId = utf("mcphone:notes");
        byte[] oneExpected = new byte[oneId.length + 1];
        oneExpected[0] = 1;
        System.arraycopy(oneId, 0, oneExpected, 1, oneId.length);
        eqBytes(PurchasedApps.STREAM_CODEC, new PurchasedApps(Set.of(notes)),
                oneExpected, "PurchasedApps 一条");
        eqBytes(SyncPurchasedAppsPacket.STREAM_CODEC,
                new SyncPurchasedAppsPacket(new PurchasedApps(Set.of(notes))),
                oneExpected, "SyncPurchasedApps 就是一个 PurchasedApps");

        //  七、设备名：名字在前、位置在后
        eqBytes(SetDeviceNamePacket.STREAM_CODEC,
                new SetDeviceNamePacket("hi", new PhoneLocation.InHand(InteractionHand.MAIN_HAND)),
                new byte[]{2, 'h', 'i', 0}, "SetDeviceName 名字在前、位置在后");
        eqBytes(SetDeviceNamePacket.STREAM_CODEC,
                new SetDeviceNamePacket("", new PhoneLocation.InInventory(7)),
                new byte[]{0, 2, 7}, "SetDeviceName 空名字 + 背包第 7 格");

        // 两只手能同时亮；多余位在构造时清掉。
        for (int mask = 0; mask <= PhoneScreenOnPacket.ALL_HANDS; mask++) {
            PhoneScreenOnPacket packet = new PhoneScreenOnPacket(mask);
            eqBytes(PhoneScreenOnPacket.STREAM_CODEC, packet, new byte[]{(byte) mask},
                    "PhoneScreenOn 掩码 " + mask);
            roundTrip(PhoneScreenOnPacket.STREAM_CODEC, packet,
                    "PhoneScreenOn 往返 " + mask);
        }
        eq(new PhoneScreenOnPacket(0xff).litHands(), PhoneScreenOnPacket.ALL_HANDS,
                "PhoneScreenOn 清理越界位");

        //  八、往返与「不多写不少写」
        for (String s : new String[]{"", "a.png", "带中文的.png", "x".repeat(200)}) {
            roundTrip(SetWallpaperPacket.STREAM_CODEC, new SetWallpaperPacket(s), "SetWallpaper 往返");
            roundTrip(SyncWallpaperPacket.STREAM_CODEC, new SyncWallpaperPacket(s), "SyncWallpaper 往返");
        }
        exact(SetWallpaperPacket.STREAM_CODEC, new SetWallpaperPacket("a.png"), "SetWallpaper");

        roundTrip(PurchaseAppPacket.STREAM_CODEC, new PurchaseAppPacket(camera), "PurchaseApp 往返");
        exact(PurchaseAppPacket.STREAM_CODEC, new PurchaseAppPacket(camera), "PurchaseApp");

        roundTrip(TerminalActionPacket.STREAM_CODEC,
                new TerminalActionPacket(TerminalActionPacket.Action.OPEN_TERMINAL), "TerminalAction 往返");
        roundTrip(RequestPurchasedAppsPacket.STREAM_CODEC, new RequestPurchasedAppsPacket(), "空包往返");
        exact(RequestPurchasedAppsPacket.STREAM_CODEC, new RequestPurchasedAppsPacket(), "空包");

        Set<ResourceLocation> many = new LinkedHashSet<>();
        for (int i = 0; i < PurchasedApps.MAX_COUNT; i++) {
            many.add(ResourceLocation.fromNamespaceAndPath("mcphone", "app" + i));
        }
        roundTrip(PurchasedApps.STREAM_CODEC, new PurchasedApps(many), "PurchasedApps 满 256 条往返");
        exact(PurchasedApps.STREAM_CODEC, new PurchasedApps(many), "PurchasedApps 满 256 条");

        for (PhoneLocation where : new PhoneLocation[]{
                new PhoneLocation.InHand(InteractionHand.OFF_HAND),
                new PhoneLocation.InInventory(35),
                new PhoneLocation.InCurio("charm", 0)}) {
            roundTrip(SetDeviceNamePacket.STREAM_CODEC,
                    new SetDeviceNamePacket("我的手机", where), "SetDeviceName 往返 " + where);
        }
        exact(SetDeviceNamePacket.STREAM_CODEC,
                new SetDeviceNamePacket("n", new PhoneLocation.InCurio("charm", 3)), "SetDeviceName");

        //  九、上限：编码侧与解码侧都要拦
        checks++;
        try {
            Set<ResourceLocation> over = new LinkedHashSet<>(many);
            over.add(ResourceLocation.fromNamespaceAndPath("mcphone", "one_too_many"));
            bytes(PurchasedApps.STREAM_CODEC, new PurchasedApps(over));
            failures.add("已购 257 条应当在编码时被拦下，实际没抛");
        } catch (RuntimeException expected) {
            // 意料之中
        }
        checks++;
        try {
            bytes(SetDeviceNamePacket.STREAM_CODEC,
                    new SetDeviceNamePacket("x".repeat(SetDeviceNamePacket.MAX_NAME_LENGTH + 1),
                            new PhoneLocation.InHand(InteractionHand.MAIN_HAND)));
            failures.add("设备名 25 字符应当在编码时被拦下，实际没抛");
        } catch (RuntimeException expected) {
            // 意料之中
        }
        roundTrip(SetDeviceNamePacket.STREAM_CODEC,
                new SetDeviceNamePacket("x".repeat(SetDeviceNamePacket.MAX_NAME_LENGTH),
                        new PhoneLocation.InHand(InteractionHand.MAIN_HAND)),
                "设备名正好 24 字符");

        // 解码侧：喂手拼字节，不经过本仓的编码器
        decodeRejects(PurchasedApps.STREAM_CODEC, new byte[]{(byte) 0x81, 0x02},
                "已购条数 257 超过上限 256，解码侧应拒收");
        decodeRejects(PurchasedApps.STREAM_CODEC,
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F},
                "已购条数 -1 必须是 DecoderException");
        decodeRejects(SetDeviceNamePacket.STREAM_CODEC, new byte[]{25},
                "设备名长度前缀 25 超过上限，解码侧应拒收");

        //  收尾
        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 项：");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }
}
