package com.november.mcphone.core.client;

import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneViewport;
import com.november.mcphone.core.script.client.VaultPage;
import com.november.mcphone.feature.chat.client.ChatConversation;
import com.november.mcphone.feature.chat.client.ChatImageCache;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/** 无窗口运行实际 HUD 交接、removed() 和页面关闭，跳过原生游戏构造。 */
public final class PhoneHudTransitionTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Object field(Class<?> type, Object owner, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void field(Class<?> type, Object owner, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
    private static <T> T withoutConstructor(Class<T> type) throws Exception {
        // Minecraft 的构造会创建窗口；这里只需要 screen 字段，不启动原生客户端。
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return type.cast(unsafe.getMethod("allocateInstance", Class.class)
                .invoke(field(unsafe, null, "theUnsafe"), type));
    }
    private static final class Page implements IPhonePage {
        int closes;
        String draft = "保留草稿";
        @Override public void render(PhoneCanvas canvas) {}
        @Override public void onClose() { closes++; }
    }
    public static void main(String[] args) throws Exception {
        Object originalMinecraft = field(Minecraft.class, null, "instance");
        Object originalPhone = field(PhoneHud.class, null, "phone");
        Object originalManual = field(PhoneHud.class, null, "manual");
        Object originalInteracting = field(PhoneHud.class, null, "interacting");
        Object originalAuto = field(PhoneHud.class, null, "lastAuto");
        try {
            Minecraft mc = withoutConstructor(Minecraft.class);
            field(Minecraft.class, null, "instance", mc);
            PhoneScreen phone = withoutConstructor(PhoneScreen.class);
            PhonePageHost host = new PhonePageHost((page, error) -> { throw new AssertionError(error); });
            Page page = new Page();
            host.open(page, new PhoneViewport(0, 0, 120, 176, 1), true);
            field(PhoneScreen.class, phone, "addonHost", host);
            field(PhoneScreen.class, phone, "mode", PhoneScreen.Mode.ADDON_PAGE);
            field(PhoneScreen.class, phone, "chatConversation", withoutConstructor(ChatConversation.class));
            field(PhoneScreen.class, phone, "vaultPage", new VaultPage());
            field(PhoneScreen.class, phone, "location", new PhoneLocation.InHand(InteractionHand.OFF_HAND));
            phone.adoptByHud();
            field(PhoneHud.class, null, "phone", phone);
            field(PhoneHud.class, null, "interacting", true);
            field(PhoneHud.class, null, "lastAuto", true);
            mc.screen = phone;

            phone.removed();
            check(page.closes == 0 && phone.isHudMode(), "退出 HUD 交互仍保留页面");
            PhoneScreen other = withoutConstructor(PhoneScreen.class);
            PhoneHud.showFullscreen(other);
            check(PhoneHud.hudPhone() == phone, "其他 Screen 不能解除这部手机的托管");
            mc.screen = null;
            PhoneHud.showFullscreen(phone);
            check(PhoneHud.hudPhone() == phone, "被动 HUD 不能冒充方块键交接");
            mc.screen = phone;
            field(PhoneScreen.class, phone, "hudDragging", true);

            PhoneHud.showFullscreen(phone);
            check(PhoneHud.hudPhone() == null, "全屏后不再由 HUD 持有");
            check(mc.screen == phone, "保留同一个 Screen，不重复打开");
            check(!phone.isHudMode(), "使用全屏布局");
            check(!(boolean) field(PhoneScreen.class, phone, "hudOwned"), "关闭责任交还 removed()");
            check(!(boolean) field(PhoneScreen.class, phone, "hudDragging"), "结束旧坐标系的拖动");
            check(!(boolean) field(PhoneHud.class, null, "interacting"), "结束 HUD 鼠标交互标记");
            check(field(PhoneHud.class, null, "manual").toString().equals("HIDE"), "覆盖副手自动显示，避免下个 tick 重新挂出");
            check((boolean) field(PhoneHud.class, null, "lastAuto"), "维持自动状态基线，避免清除明确的收起请求");
            check(field(PhoneScreen.class, phone, "mode") == PhoneScreen.Mode.ADDON_PAGE
                    && host.page() == page && page.draft.equals("保留草稿"), "保留页面、宿主及草稿状态");
            check(page.closes == 0, "切换本身不能提前关机");
            PhoneHud.showFullscreen(phone);
            check(page.closes == 0 && mc.screen == phone, "重复请求不重开、不关机");
            Object generation = field(ChatImageCache.class, null, "generation");
            phone.removed();
            check(page.closes == 1 && host.page() == null, "全屏真正关闭时释放页面");
            Object closedGeneration = field(ChatImageCache.class, null, "generation");
            check(!closedGeneration.equals(generation), "执行实际关机清理");
            PhoneHud.onWorldLeave();
            check(page.closes == 1 && field(ChatImageCache.class, null, "generation").equals(closedGeneration),
                    "HUD 已解绑，断线清理不重复关机");
        } finally {
            field(Minecraft.class, null, "instance", originalMinecraft);
            field(PhoneHud.class, null, "phone", originalPhone);
            field(PhoneHud.class, null, "manual", originalManual);
            field(PhoneHud.class, null, "interacting", originalInteracting);
            field(PhoneHud.class, null, "lastAuto", originalAuto);
        }
        System.out.println("PhoneHudTransitionTest: " + checks + " checks passed");
    }
}
