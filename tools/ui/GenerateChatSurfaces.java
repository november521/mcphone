import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 聊天面板矢量原稿与八倍 PNG：只生成本体 UI 背景，不重采样玩家的图片或像素图标。
 * 在仓库根运行：java tools/ui/GenerateChatSurfaces.java .
 * 固定三逻辑像素边角，由 mcphone_skin 的 scale 元数据缩回；输入栏为偏黑的透明底。
 */
public class GenerateChatSurfaces {
    private static final int SCALE = 8;
    private record Surface(String folder, String name, int w, int h, String top, String bottom, boolean input) {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("shared/src/main/resources"))) throw new IllegalArgumentException("需要仓库根目录");
        Path textures = root.resolve("shared/src/main/resources/assets/mcphone/textures");
        Path sources = root.resolve("docs/assets/chat-ui/surfaces");
        Files.createDirectories(sources);
        for (Surface s : new Surface[]{
                new Surface("chat", "bubble_self", 32, 32, "753957", "592c48", false),
                new Surface("chat", "bubble_peer", 32, 32, "343447", "272838", false),
                new Surface("chat", "input_bar", 90, 12, "24232b", "15141b", true),
                new Surface("phone", "unread_badge", 12, 8, "c65d93", "9d3e6f", false)}) {
            Path folder = textures.resolve(s.folder());
            Files.createDirectories(folder);
            String topOpacity = s.input() ? " stop-opacity=\"0.75\"" : "";
            String bottomOpacity = s.input() ? " stop-opacity=\"0.6863\"" : "";
            String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + s.w() * SCALE
                    + "\" height=\"" + s.h() * SCALE + "\" viewBox=\"0 0 " + s.w() + " " + s.h() + "\">"
                    + "<defs><linearGradient id=\"surface\" x2=\"0\" y2=\"1\"><stop stop-color=\"#"
                    + s.top() + "\"" + topOpacity + "/><stop offset=\"100%\" stop-color=\"#" + s.bottom()
                    + "\"" + bottomOpacity + "/></linearGradient></defs><rect width=\"" + s.w() + "\" height=\"" + s.h()
                    + "\" rx=\"3\" fill=\"url(#surface)\"/></svg>\n";
            Files.writeString(sources.resolve(s.name() + ".svg"), svg, StandardCharsets.UTF_8);
            BufferedImage image = new BufferedImage(s.w() * SCALE, s.h() * SCALE, BufferedImage.TYPE_INT_ARGB);
            var g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.scale(SCALE, SCALE);
                float[] fractions = new float[]{0, 1};
                Color top = Color.decode("#" + s.top()), bottom = Color.decode("#" + s.bottom());
                if (s.input()) {
                    top = new Color(top.getRed(), top.getGreen(), top.getBlue(), 191);
                    bottom = new Color(bottom.getRed(), bottom.getGreen(), bottom.getBlue(), 175);
                }
                Color[] colors = new Color[]{top, bottom};
                g.setPaint(new LinearGradientPaint(0, 0, 0, s.h(), fractions, colors));
                g.fill(new RoundRectangle2D.Float(0, 0, s.w(), s.h(), 6, 6));
            } finally {
                g.dispose();
            }
            ImageIO.write(image, "PNG", folder.resolve(s.name() + ".png").toFile());
            Files.writeString(folder.resolve(s.name() + ".png.mcmeta"),
                    "{\"mcphone_skin\":{\"border\":24,\"scale\":8"
                    + (s.input() ? ",\"text_color\":\"#FFF2F7\"" : "") + "}}\n", StandardCharsets.UTF_8);
            System.out.println(s.name() + ": " + image.getWidth() + "x" + image.getHeight());
        }
    }
}
