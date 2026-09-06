package com.november.mcphone.core;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 手机在玩家身上的什么地方。
 *
 * 为什么不能继续用"哪只手"
 *
 * 界面原先只能靠右键手机打开，所以一个 InteractionHand 就够了：手机
 * 必然在某只手上，设备名也就写回那只手上的物品堆。
 *
 * 有了快捷键之后这个前提没了——手机可能躺在背包第
 * 十七格，玩家两手空空照样能开机。设备名总得知道该写回【哪一部】手机，
 * 尤其玩家身上不止一部时。
 *
 * 为什么位置要过网络
 *
 * 改设备名这件事只能由服务端落笔（客户端说了不算），而服务端必须知道
 * 改的是哪一部。让服务端自己去找的话，玩家背包里两部手机就会改错那一部。
 *
 * 位置由客户端给出、服务端解析后【再验一次拿到的确实是手机】：客户端
 * 报一个不存在的位置或者别的物品，最坏也只是什么都没改。
 *
 * 原 NeoForge 版还有一个 InCurio 位置（手机挂 Curios 饰品槽）；Curios 在
 * 1.21.1 的 Fabric 上没有构建，故本版只有手上与背包两种。
 */
public sealed interface PhoneLocation {

    /** 拿在手上 */
    record InHand(InteractionHand hand) implements PhoneLocation {

        @Override
        public ItemStack resolve(Player player) {
            return player.getItemInHand(hand);
        }

        @Override
        public void writeBack(Player player, ItemStack stack) {
            // 手上那只是物品栏里的同一个对象，改完原版自会同步，无需额外动作
        }
    }

    /** 躺在背包里（含盔甲栏与副手，序号按原版 Inventory 的编号） */
    record InInventory(int slot) implements PhoneLocation {

        @Override
        public ItemStack resolve(Player player) {
            Inventory inventory = player.getInventory();
            // 序号可能已经失效：玩家丢掉手机、换了容器都会让它对不上号
            if (slot < 0 || slot >= inventory.getContainerSize()) return ItemStack.EMPTY;
            return inventory.getItem(slot);
        }

        @Override
        public void writeBack(Player player, ItemStack stack) {
            // 同上，背包里的物品堆改完原版自会同步
        }
    }

    /** 取出这个位置上的物品。位置已失效时返回空堆，调用方自行判断 */
    ItemStack resolve(Player player);

    /** 改完之后把物品写回去。目前没有任何位置需要这一步，但保留这个口子 */
    void writeBack(Player player, ItemStack stack);

    /**
     * 从玩家身上找出一部手机，按顺手程度排序。
     *
     * 手上的优先：玩家正举着的那部显然就是他想用的。其次背包。
     */
    static Optional<PhoneLocation> find(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (PhoneItem.isPhone(player.getItemInHand(hand))) {
                return Optional.of(new InHand(hand));
            }
        }

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (PhoneItem.isPhone(inventory.getItem(slot))) {
                return Optional.of(new InInventory(slot));
            }
        }

        return Optional.empty();
    }

    //  网络

    byte TYPE_MAIN_HAND = 0;
    byte TYPE_OFF_HAND = 1;
    byte TYPE_INVENTORY = 2;

    /**
     * 手写而不是用 composite：这是个和类型有关的多态结构，
     * composite 只会按固定字段序列化。
     */
    StreamCodec<ByteBuf, PhoneLocation> STREAM_CODEC = new StreamCodec<>() {

        @Override
        public PhoneLocation decode(ByteBuf buf) {
            byte type = buf.readByte();
            return switch (type) {
                case TYPE_OFF_HAND -> new InHand(InteractionHand.OFF_HAND);
                case TYPE_INVENTORY -> new InInventory(ByteBufCodecs.VAR_INT.decode(buf));
                // 主手，以及任何不认识的类型。版本不一致或伪造客户端送来未知值时，
                // 宁可退回一个无害的默认位置，也不要抛异常打断整条连接——
                // 反正服务端还要验一次那儿是不是真有手机。Relation 那边同理
                default -> new InHand(InteractionHand.MAIN_HAND);
            };
        }

        @Override
        public void encode(ByteBuf buf, PhoneLocation value) {
            switch (value) {
                case InHand(InteractionHand hand) ->
                        buf.writeByte(hand == InteractionHand.MAIN_HAND
                                ? TYPE_MAIN_HAND : TYPE_OFF_HAND);
                case InInventory(int slot) -> {
                    buf.writeByte(TYPE_INVENTORY);
                    ByteBufCodecs.VAR_INT.encode(buf, slot);
                }
            }
        }
    };
}
