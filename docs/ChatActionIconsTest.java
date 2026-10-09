package com.november.mcphone.feature.chat.client.contextmenu;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** 用户图标的资源回归：尺寸、透明边界、白色可着色像素、细轮廓和许可记录。 */
public final class ChatActionIconsTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("shared/src/main/resources")))root=root.getParent();
        check(root!=null,"找到素材根目录");
        for(String name:new String[]{"copy","delete","paste"}) {
            var image=ImageIO.read(root.resolve("shared/src/main/resources/assets/mcphone/textures/chat/ui/"+name+".png").toFile());
            check(image.getWidth()==96&&image.getHeight()==96,"用户提供的图标保持 96×96");
            boolean ink=false,partial=false,white=true,margin=true;
            for(int y=0;y<96;y++)for(int x=0;x<96;x++) {
                int pixel=image.getRGB(x,y),alpha=pixel>>>24;
                if(x==0||y==0||x==95||y==95)margin&=alpha==0;
                if(alpha>0) { ink=true;white&=(pixel&0xFFFFFF)==0xFFFFFF; }
                partial|=alpha>0&&alpha<255;
            }
            check(margin,"图标四边透明，不带白底或黑底");
            check(ink&&white,"可见像素为白色，菜单能够统一着色");
            check(partial,"细轮廓保留抗锯齿覆盖");
            check(Files.isRegularFile(root.resolve("docs/assets/chat-ui/clipboard-actions-96px/"+name+".svg")),"保留用户矢量原稿");
        }
        check(Files.size(root.resolve("shared/src/main/resources/licenses/lucide-ISC.txt"))>100,"Lucide 许可随包保存");
        System.out.println("全部通过："+checks+" 条断言");
    }
}
