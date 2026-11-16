package com.november.mcphone.feature.settings.client.changelog;

import java.io.StringReader;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.FontPreset;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** 无游戏/声卡/网络：验证提醒语义、翻译、包内契约、隔离和重启后的持久化。 */
public final class ChangelogTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static final class TestFont extends com.november.mcphone.test.TestFontBase {
        int splits;
        private final net.minecraft.client.StringSplitter splitter = new net.minecraft.client.StringSplitter((cp, style) -> 6);
        @Override public List<FormattedCharSequence> split(FormattedText text, int width) {
            splits++;
            return splitter.splitLines(text, width, Style.EMPTY).stream()
                    .map(line -> FormattedCharSequence.forward(line.getString(), Style.EMPTY)).toList();
        }
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void field(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    public static void main(String[] args) throws Exception {
        String data = """
                [{"version":"2.0.0","date":"2026-10-23","languages":{
                  "zh_cn":[{"title":"新增","entries":["中文 [详情](https://example.com)"]}],
                  "en_us":[{"title":"Added","entries":["English"]}]}},
                 {"version":"1.9.0","date":"","languages":{
                  "zh_cn":[{"title":"修复","entries":["旧版"]}],
                  "en_us":[{"title":"Fixed","entries":["Previous"]}]}}]
                """;
        var releases = Changelog.read(new StringReader(data));
        check(releases.size() == 2);
        check(Changelog.current(releases, "2.0.0").sections("zh_cn").get(0).title().equals("新增"));
        check(Changelog.current(releases, "2.0.0").sections("de_de").get(0).title().equals("Added"));
        check(Changelog.current(releases, "2.0.0-beta.1").version().equals("2.0.0"));
        check(Changelog.current(releases, "3.0.0") == null);
        check(Changelog.shouldNotify("2.0.0", "", true));
        check(Changelog.shouldNotify("2.0.0", "1.0.0", true));
        check(!Changelog.shouldNotify("2.0.0", "2.0.0", true));
        check(!Changelog.shouldNotify("2.0.0-beta.1", "", true));
        check(!Changelog.shouldNotify("2.0.0-alpha.1", "", true));
        check(!Changelog.shouldNotify("2.0.0", "", false));
        // 没有版本高水位/降级特殊规则，任何不同的正式版本都只展示自己的正文。
        check(Changelog.shouldNotify("1.9.0", "2.0.0", true));
        check(Changelog.current(releases, "1.9.0").sections("en_us").get(0).entries().equals(List.of("Previous")));

        var entry = ChangelogText.parse("[Forge] A [details](https://example.com/a?q=1&x=2) B [more](http://example.org)");
        check(entry.text().equals("[Forge] A details B more"));
        check(entry.links().size() == 2);
        check(entry.links().get(0).url().equals("https://example.com/a?q=1&x=2"));
        check(ChangelogText.parse("[bad](file:///tmp/a) [bad](javascript:alert)").links().isEmpty());
        check(ChangelogText.parse("[bad](https://user@example.com)").links().isEmpty());
        check(ChangelogText.parse("[bad](https://)").links().isEmpty());

        // 真实生产排版：连续帧/滚动不重排；宽度、语言、配色、字体、重新打开会重排。
        var page = new ChangelogPage();
        page.open(releases);
        var font = new TestFont();
        var layout = ChangelogPage.class.getDeclaredMethod("layout", Font.class, int.class, String.class);
        layout.setAccessible(true);
        layout.invoke(page, font, 96, "zh_cn");
        int initialSplits = font.splits;
        var initialRows = field(page, "rows");
        check(initialSplits > 0);
        for (int i = 0; i < 120; i++) layout.invoke(page, font, 96, "zh_cn");
        check(font.splits == initialSplits && field(page, "rows") == initialRows);
        layout.invoke(page, font, 48, "zh_cn");
        check(font.splits > initialSplits && ((List<?>) field(page, "rows")).size() > ((List<?>) initialRows).size());
        int widthSplits = font.splits;
        layout.invoke(page, font, 48, "en_us");
        check(font.splits > widthSplits);
        var originalPalette = FontPalette.current();
        try {
            int languageSplits = font.splits;
            FontPalette.set(originalPalette == FontPreset.BLACK ? FontPreset.WHITE : FontPreset.BLACK);
            layout.invoke(page, font, 48, "en_us");
            check(font.splits > languageSplits);
        } finally { FontPalette.set(originalPalette); }
        var otherFont = new TestFont();
        layout.invoke(page, otherFont, 48, "en_us");
        check(otherFont.splits > 0);
        int beforeOpen = otherFont.splits;
        page.open(releases);
        layout.invoke(page, otherFont, 48, "en_us");
        check(otherFont.splits > beforeOpen);

        // 无窗口验证实际按钮命中和滚动状态；不打开系统浏览器。
        field(page, "modal", true); field(page, "left", 10); field(page, "width", 100);
        field(page, "textTop", 20); field(page, "textBottom", 120); field(page, "bottom", 150);
        field(page, "maxScroll", 200); field(page, "scroll", 80);
        check(!page.mouseClicked(5, 130, 0));
        check(!page.mouseClicked(20, 125, 1));
        check(page.mouseClicked(20, 125, 0));
        check(page.mouseClicked(13, 123, 0));
        check(page.mouseClicked(106.99, 146.99, 0));
        check(!page.mouseClicked(12.99, 125, 0));
        check(!page.mouseClicked(107, 125, 0));
        check(!page.mouseClicked(20, 122.99, 0));
        check(!page.mouseClicked(20, 147, 0));
        field(page, "modal", false);
        check(!page.mouseClicked(20, 125, 0));
        field(page, "modal", true);
        @SuppressWarnings("unchecked") var hits = (List<Object>) field(page, "links");
        hits.add(null); // 模拟上一帧的命中区域；滚动后不可再使用它。
        page.scroll(20, 30, 1);
        check((int) field(page, "scroll") == 56 && hits.isEmpty());
        page.scroll(0, 0, 1);
        check((int) field(page, "scroll") == 56);
        page.scroll(20, 30, -100);
        check((int) field(page, "scroll") == 200);
        page.scroll(20, 30, 100);
        check((int) field(page, "scroll") == 0);
        int beforeScrollLayout = otherFont.splits;
        layout.invoke(page, otherFont, 48, "en_us");
        check(otherFont.splits == beforeScrollLayout);

        var directory = Files.createTempDirectory("mcphone-changelog-test");
        UUID account = UUID.randomUUID();
        var a = ChangelogStore.path(directory, account, "local:world-a");
        var b = ChangelogStore.path(directory, account, "local:world-b");
        var c = ChangelogStore.path(directory, account, "remote:example.com:25565");
        var d = ChangelogStore.path(directory, UUID.randomUUID(), "local:world-a");
        check(!a.equals(b) && !a.equals(c) && !a.equals(d));
        check(a.startsWith(directory) && c.startsWith(directory));
        check(ChangelogStore.read(a).isEmpty());
        ChangelogStore.shown(a, "2.0.0");
        check(ChangelogStore.read(a).equals("2.0.0"));
        check(ChangelogStore.read(b).isEmpty() && ChangelogStore.read(c).isEmpty());
        check(!Changelog.shouldNotify("2.0.0", ChangelogStore.read(a), true));
        ChangelogStore.shown(a, "2.1.0");
        check(ChangelogStore.read(a).equals("2.1.0"));
        try { Changelog.read(new StringReader("[{\"version\":\"1.0.0\",\"date\":\"\",\"languages\":{}}]")); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }

        try (var stream = ChangelogTest.class.getResourceAsStream("/assets/mcphone/changelog.json")) {
            check(stream != null);
            var bundled = Changelog.read(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            check(!bundled.isEmpty());
            check(bundled.stream().allMatch(r -> !r.sections("en_us").isEmpty() && !r.sections("zh_cn").isEmpty()));
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        System.out.println("ChangelogTest: " + checks + " checks passed");
    }
}
