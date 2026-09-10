package com.november.mcphone.core.client;

import com.november.mcphone.core.PhoneLocation;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link PhoneSession} 按设备分开记的断言测试。
 *
 * <h2>钉的是哪一条</h2>
 *
 * 这个类原先是两个静态字段、全局一份，而它的意图从第一句起就是 per-device 的。
 * 后果是一次正常操作会静默擦掉另一台的状态：
 *
 * <ol>
 *   <li>平板挂在副手 HUD 上，停在主屏；
 *   <li>主手的手机翻到聊天页，关掉 —— 记下「续开聊天」；
 *   <li>把平板从副手拿下来 —— {@code PhoneHud.dismiss()} 替它调 {@code shutdown()}，
 *       于是 {@code save(MAIN)}，而 MAIN 不在白名单里。
 * </ol>
 *
 * 全局一份时第 3 步会把第 2 步记的东西清掉：<b>平板什么都没做，手机的续开状态没了</b>，
 * 不崩、不报错、下次开哪一台都回主屏。
 *
 * <h2>为什么只用 resumePeer 观测</h2>
 *
 * {@code resumeMode} 会查 {@code PhoneScreenRegistry.isInstalled}，那一步要触发 SPI 扫描 ——
 * 在断言测试这种没有游戏的环境里不稳，而它与这里要钉的性质无关。
 * {@code resumePeer} 只读这张表，够用。
 */
public final class PhoneSessionTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    public static void main(String[] args) {
        PhoneLocation phone  = new PhoneLocation.InHand(InteractionHand.MAIN_HAND);
        PhoneLocation tablet = new PhoneLocation.InHand(InteractionHand.OFF_HAND);
        UUID peer = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        // ── 那条触发序列 ──
        PhoneSession.clearAll();
        PhoneSession.save(phone, PhoneScreen.Mode.CHAT_CONVERSATION, peer);
        check(peer.equals(PhoneSession.resumePeer(phone)), "手机关机时应当记下续开的会话");

        PhoneSession.save(tablet, PhoneScreen.Mode.MAIN, null);
        check(peer.equals(PhoneSession.resumePeer(phone)),
                "平板从主屏收起【不许】动到手机记的东西");
        check(PhoneSession.resumePeer(tablet) == null, "平板自己没有可续开的东西");

        // ── 自己那份该清还是要清 ──
        PhoneSession.save(phone, PhoneScreen.Mode.MAIN, null);
        check(PhoneSession.resumePeer(phone) == null, "手机自己停在主屏时应当清掉自己那份");

        // ── 两部各记各的，互不覆盖 ──
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        PhoneSession.save(phone, PhoneScreen.Mode.CHAT_CONVERSATION, peer);
        PhoneSession.save(tablet, PhoneScreen.Mode.CHAT_CONVERSATION, other);
        check(peer.equals(PhoneSession.resumePeer(phone)), "两部各记各的：手机那份不该被平板覆盖");
        check(other.equals(PhoneSession.resumePeer(tablet)), "两部各记各的：平板那份不该被手机覆盖");

        // ── 位置换了就续不上，是有意的降级 ──
        check(PhoneSession.resumePeer(new PhoneLocation.InInventory(17)) == null,
                "按一个没记过的位置查，应当查不到");

        // ── 拿不到位置时什么都不做，尤其不许清别人的 ──
        PhoneSession.save(null, PhoneScreen.Mode.MAIN, null);
        check(peer.equals(PhoneSession.resumePeer(phone)), "位置为 null 时不许动任何记录");

        // ── 进世界时整张表清掉 ──
        PhoneSession.clearAll();
        check(PhoneSession.resumePeer(phone) == null && PhoneSession.resumePeer(tablet) == null,
                "clearAll 应当把所有设备的记录清掉");

        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
        } else {
            System.out.println("失败 " + failures.size() + " 项，共 " + checks + " 项断言：");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }
}
