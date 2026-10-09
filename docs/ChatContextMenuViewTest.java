package com.november.mcphone.feature.chat.client.contextmenu;

import java.util.ArrayList;
import java.util.List;

/** 从消息模块迁出的共用几何回归；覆盖图文列、边界、不可变条目和禁用命中。 */
public final class ChatContextMenuViewTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
    public static void main(String[] args) {
        var viewport = new ChatContextMenuLayout.Bounds(10, 20, 112, 160);
        for (double x : new double[]{-100, 10, 60.75, 122, 1000}) {
            for (double y : new double[]{-100, 20, 90.5, 180, 1000}) {
                for (int rows : new int[]{1, 2}) {
                    var layout = ChatContextMenuLayout.place(x, y, viewport, 60, 7, rows);
                    var b = layout.bounds();
                    check(b.x() >= viewport.x() && b.x() + b.width() <= viewport.x() + viewport.width(), "靠左右边界菜单留在手机内");
                    check(b.y() >= viewport.y() && b.y() + b.height() <= viewport.y() + viewport.height(), "靠上下边界菜单留在手机内");
                    check(layout.rowAt(b.x() + 5, b.y() + 2) == -1, "上留白不执行操作");
                    check(layout.rowAt(b.x() - .01, b.y() + 5) == -1, "菜单外不命中");
                    check(layout.rowAt(b.x() + b.width(), b.y() + 5) == -1, "右边界不命中");
                    check(layout.rowAt(b.x() + 5, b.y() + b.height() - 1) == -1, "下留白不执行操作");
                    for (int i = 0; i < rows; i++) check(layout.rowAt(b.x() + 5, b.y() + 3 + i * layout.rowHeight()) == i, "绘制与命中共用行边界");
                }
            }
        }
        var layout=ChatContextMenuLayout.place(20,30,viewport,14,7,2);
        check(layout.textX()-layout.iconX()==12,"图标与文字保留 4 逻辑像素间隔");
        check(layout.iconX()>=layout.bounds().x()+3,"图标左侧保留内边距");
        check(layout.textX()+14<=layout.bounds().x()+layout.bounds().width()-3,"文字右侧保留内边距");
        var view=new ChatContextMenuView();
        var entries=new ArrayList<>(List.of(new ChatContextMenuView.Entry("copy",ChatContextMenuView.Icon.COPY,true),
                new ChatContextMenuView.Entry("delete",ChatContextMenuView.Icon.DELETE,false)));
        view.open(10,20);view.arrange(viewport,14,7,entries);entries.clear();
        check(view.hitIndex(15,24)==0,"可用整行均能命中，包括图标区域");
        check(view.hitIndex(15,37)==-1,"禁用项不会产生操作意图");
        check(view.hitIndex(-100,-100)==-1,"菜单外不会触发操作");
        check(view.dismiss()&&view.hitIndex(15,24)==-1,"关闭后旧布局不能命中");
        view.open(60,60);check(view.hitIndex(15,24)==-1,"重新打开不会使用上次的布局");
        System.out.println("全部通过："+checks+" 条断言");
    }
}
