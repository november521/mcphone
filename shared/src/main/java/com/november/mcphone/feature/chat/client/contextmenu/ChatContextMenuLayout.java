package com.november.mcphone.feature.chat.client.contextmenu;

/** 菜单绘制和命中共用几何；图标与文本保持统一列，菜单向手机内容区内避让。 */
final class ChatContextMenuLayout {
    static final int PAD = 3, ICON_SIZE = 8, ICON_GAP = 4;
    record Bounds(int x,int y,int width,int height) {
        boolean contains(double mx,double my) { return mx>=x && mx<x+width && my>=y && my<y+height; }
    }
    record Layout(Bounds bounds,int rowHeight,int rows,int contentInset) {
        int rowAt(double mx,double my) {
            if (!bounds.contains(mx,my) || my<bounds.y()+PAD || my>=bounds.y()+PAD+rows*rowHeight) return -1;
            return (int)((my-bounds.y()-PAD)/rowHeight);
        }
        int iconX() { return bounds.x()+contentInset; }
        int textX() { return iconX()+ICON_SIZE+ICON_GAP; }
    }
    static Layout place(double mx,double my,Bounds viewport,int labelWidth,int textHeight,int rows) {
        int rowHeight = Math.max(textHeight+6,ICON_SIZE+4);
        int contentWidth = labelWidth+ICON_SIZE+ICON_GAP;
        int width = Math.min(Math.max(40,contentWidth+PAD*2+4),viewport.width());
        int height = rows*rowHeight+PAD*2;
        int x = Math.max(viewport.x(),Math.min((int)Math.floor(mx),viewport.x()+viewport.width()-width));
        int y = Math.max(viewport.y(),Math.min((int)Math.floor(my),viewport.y()+viewport.height()-height));
        int inset = Math.max(PAD,(width-contentWidth)/2);
        return new Layout(new Bounds(x,y,width,height),rowHeight,rows,inset);
    }
}
