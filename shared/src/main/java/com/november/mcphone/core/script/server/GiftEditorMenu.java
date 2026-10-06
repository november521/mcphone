package com.november.mcphone.core.script.server;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.*;

/** 原版箱子外观的复制编辑器。完全覆盖点击处理，模板不能取走，背包不会被扣物品。 */
public final class GiftEditorMenu extends ChestMenu {
    private final SimpleContainer templates;
    private final BooleanSupplier permission;
    private final Consumer<List<ItemStack>> save;
    private int selected;
    private boolean removed;
    public GiftEditorMenu(int id,Inventory inventory,List<ItemStack> initial,BooleanSupplier permission,Consumer<List<ItemStack>> save) {
        this(id,inventory,new SimpleContainer(27),initial,permission,save);
    }
    private GiftEditorMenu(int id,Inventory inventory,SimpleContainer templates,List<ItemStack> initial,BooleanSupplier permission,Consumer<List<ItemStack>> save) {
        super(MenuType.GENERIC_9x3,id,inventory,templates,3); this.templates=templates; this.permission=permission; this.save=save;
        for(int i=0;i<Math.min(initial.size(),27);i++) templates.setItem(i,initial.get(i).copy());
    }
    @Override public boolean stillValid(Player player) { return permission.getAsBoolean(); }
    @Override public ItemStack quickMoveStack(Player player,int index) { return ItemStack.EMPTY; }
    @Override public void clicked(int index,int button,ClickType type,Player player) {
        if(!permission.getAsBoolean() || removed) return;
        // 上面点一下选择目标格，右键清空。下面点背包物品，复制到目标格并移至下一格。
        // 不调用父类，禁止 shift、拖拽、数字键交换、扔出、双击收集的所有实物搬运。
        if(type!=ClickType.PICKUP || index<0 || index>=slots.size()) { broadcastChanges(); return; }
        if(index<27) { selected=index; if(button==1) templates.setItem(index,ItemStack.EMPTY); }
        else if(button==0) { ItemStack source=slots.get(index).getItem(); if(!source.isEmpty()) { templates.setItem(selected,source.copy()); selected=(selected+1)%27; } }
        setCarried(ItemStack.EMPTY); broadcastChanges();
    }
    @Override public void removed(Player player) {
        if(removed) return; removed=true;
        try {
            if(permission.getAsBoolean()) {
                List<ItemStack> out=new ArrayList<>(); for(int i=0;i<27;i++) if(!templates.getItem(i).isEmpty()) out.add(templates.getItem(i).copy()); save.accept(out);
            }
        } finally { templates.clearContent(); setCarried(ItemStack.EMPTY); }
        // 父类 removed 会返还 carried。这个菜单的 carried 从不接收玩家实物。
    }
}
