import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/** 聊天表面的矢量原稿与八倍 PNG；固定细描边、三逻辑像素圆角和轻阴影，不处理玩家图片。 */
public class GenerateChatSurfaces {
    private static final int SCALE = 8;
    private record Surface(String folder,String name,int w,int h,String top,String bottom,
                           int topAlpha,int bottomAlpha,String edge,int edgeAlpha,boolean input) {}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("shared/src/main/resources"))) throw new IllegalArgumentException("需要仓库根目录");
        Path textures=root.resolve("shared/src/main/resources/assets/mcphone/textures");
        Path sources=root.resolve("docs/assets/chat-ui/surfaces");Files.createDirectories(sources);
        for (Surface s:new Surface[]{
                new Surface("chat","bubble_self",32,32,"9e5d82","754362",198,188,"f2d2e7",153,false),
                new Surface("chat","bubble_peer",32,32,"7d8ca3","586d87",194,184,"d5e2f6",145,false),
                new Surface("chat","input_bar",90,12,"24232b","15141b",174,158,"ccd6ea",117,true),
                new Surface("phone","unread_badge",12,8,"c65d93","9d3e6f",255,255,null,0,false)}) {
            Path folder=textures.resolve(s.folder());Files.createDirectories(folder);
            boolean outlined=s.edge()!=null;
            String topOpacity=s.topAlpha()==255?"":" stop-opacity=\""+opacity(s.topAlpha())+"\"";
            String bottomOpacity=s.bottomAlpha()==255?"":" stop-opacity=\""+opacity(s.bottomAlpha())+"\"";
            String gradient=outlined?"gradientUnits=\"userSpaceOnUse\" x2=\"0\" y2=\""+s.h()+"\"":"x2=\"0\" y2=\"1\"";
            String shape=outlined
                    ?"<rect y=\"0.5\" width=\""+s.w()+"\" height=\""+(s.h()-.5)+"\" rx=\"3\" fill=\"#050811\" fill-opacity=\""+opacity(12)+"\"/>"
                     +"<rect x=\"0.25\" y=\"0.25\" width=\""+(s.w()-.5)+"\" height=\""+(s.h()-.5)+"\" rx=\"2.75\" fill=\"url(#surface)\" stroke=\"#"+s.edge()+"\" stroke-opacity=\""+opacity(s.edgeAlpha())+"\" stroke-width=\"0.5\"/>"
                    :"<rect width=\""+s.w()+"\" height=\""+s.h()+"\" rx=\"3\" fill=\"url(#surface)\"/>";
            String svg="<svg xmlns=\"http://www.w3.org/2000/svg\" width=\""+s.w()*SCALE+"\" height=\""+s.h()*SCALE+"\" viewBox=\"0 0 "+s.w()+" "+s.h()+"\">"
                    +"<defs><linearGradient id=\"surface\" "+gradient+"><stop stop-color=\"#"+s.top()+"\""+topOpacity+"/><stop offset=\"100%\" stop-color=\"#"+s.bottom()+"\""+bottomOpacity+"/></linearGradient></defs>"+shape+"</svg>\n";
            Files.writeString(sources.resolve(s.name()+".svg"),svg,StandardCharsets.UTF_8);
            BufferedImage image=new BufferedImage(s.w()*SCALE,s.h()*SCALE,BufferedImage.TYPE_INT_ARGB);
            var g=image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.scale(SCALE,SCALE);
                if (outlined) { g.setColor(new Color(5,8,17,12));g.fill(new RoundRectangle2D.Float(0,.5f,s.w(),s.h()-.5f,6,6)); }
                var shapeGeometry=outlined?new RoundRectangle2D.Float(.25f,.25f,s.w()-.5f,s.h()-.5f,5.5f,5.5f)
                        :new RoundRectangle2D.Float(0,0,s.w(),s.h(),6,6);
                g.setPaint(new LinearGradientPaint(0,0,0,s.h(),new float[]{0,1},new Color[]{color(s.top(),s.topAlpha()),color(s.bottom(),s.bottomAlpha())}));
                g.fill(shapeGeometry);
                if (outlined) { g.setColor(color(s.edge(),s.edgeAlpha()));g.setStroke(new BasicStroke(.5f));g.draw(shapeGeometry); }
            } finally { g.dispose(); }
            ImageIO.write(image,"PNG",folder.resolve(s.name()+".png").toFile());
            Files.writeString(folder.resolve(s.name()+".png.mcmeta"),"{\"mcphone_skin\":{\"border\":24,\"scale\":8"+(s.input()?",\"text_color\":\"#FFF2F7\"":"")+"}}\n",StandardCharsets.UTF_8);
            System.out.println(s.name()+": "+image.getWidth()+"x"+image.getHeight());
        }
    }
    private static String opacity(int alpha) { return String.format(Locale.ROOT,"%.4f",alpha/255.0); }
    private static Color color(String hex,int alpha) { Color c=Color.decode("#"+hex);return new Color(c.getRed(),c.getGreen(),c.getBlue(),alpha); }
}
