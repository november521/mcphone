package com.november.mcphone.feature.chat.client.contextmenu;

import com.november.mcphone.core.client.PhoneSkin;
import net.minecraft.client.gui.GuiGraphics;

/** 用户提供的白色透明图标随菜单颜色着色；缺图时以小型几何轮廓兜底。 */
final class ChatContextMenuIcon {
    private ChatContextMenuIcon() {}
    static void draw(GuiGraphics g,ChatContextMenuView.Icon icon,float x,float centerY,int color) {
        PhoneSkin.Element element = switch(icon) {
            case COPY -> PhoneSkin.Element.CHAT_COPY;
            case DELETE -> PhoneSkin.Element.CHAT_DELETE;
            case PASTE -> PhoneSkin.Element.CHAT_PASTE;
        };
        g.pose().pushPose();
        try {
            // 96px 源图的可见轮廓中心：复制向右 1/6、粘贴向左 1/6 逻辑像素，删除居中。
            float correction = switch(icon) { case COPY -> 1f/6f; case PASTE -> -1f/6f; default -> 0; };
            g.pose().translate(x+correction,centerY-ChatContextMenuLayout.ICON_SIZE/2f,0);
            g.setColor(((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,((color>>>24)&255)/255f);
            boolean drawn;
            try { drawn = PhoneSkin.draw(g,element,0,0,ChatContextMenuLayout.ICON_SIZE,ChatContextMenuLayout.ICON_SIZE); }
            finally { g.setColor(1,1,1,1); }
            if (drawn) return;
            g.pose().scale(ChatContextMenuLayout.ICON_SIZE/24f,ChatContextMenuLayout.ICON_SIZE/24f,1);
            switch(icon) {
                case COPY -> { outline(g,7,2,20,18,color); g.fill(3,6,5,22,color); g.fill(3,20,16,22,color); }
                case DELETE -> { outline(g,6,7,18,21,color); g.fill(4,4,20,6,color); g.fill(9,2,15,4,color); }
                case PASTE -> { outline(g,4,4,20,22,color); g.fill(8,2,16,6,color); g.fill(10,13,22,15,color); }
            }
        } finally { g.pose().popPose(); }
    }
    private static void outline(GuiGraphics g,int x1,int y1,int x2,int y2,int color) {
        g.fill(x1,y1,x2,y1+2,color); g.fill(x1,y2-2,x2,y2,color);
        g.fill(x1,y1+2,x1+2,y2-2,color); g.fill(x2-2,y1+2,x2,y2-2,color);
    }
}
