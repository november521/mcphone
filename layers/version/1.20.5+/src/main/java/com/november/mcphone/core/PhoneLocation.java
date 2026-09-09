package com.november.mcphone.core;

import com.november.mcphone.compat.CuriosCompat;
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
 * 有了饰品槽和快捷键之后这个前提没了——手机可能挂在腰上、躺在背包第
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

        @Override
        public void writeTo(ByteBuf buf) {
            buf.writeByte(hand == InteractionHand.MAIN_HAND ? TYPE_MAIN_HAND : TYPE_OFF_HAND);
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

        @Override
        public void writeTo(ByteBuf buf) {
            buf.writeByte(TYPE_INVENTORY);
            ByteBufCodecs.VAR_INT.encode(buf, slot);
        }
    }

    /** 挂在 Curios 的饰品槽里 */
    record InCurio(String slotId, int index) implements PhoneLocation {

        @Override
        public ItemStack resolve(Player player) {
            return CuriosCompat.getEquipped(player, slotId, index);
        }

        @Override
        public void writeBack(Player player, ItemStack stack) {
            // 这里必须显式写回：饰品栏的同步归 Curios 管，得告诉它东西变了
            CuriosCompat.setEquipped(player, slotId, index, stack);
        }

        @Override
        public void writeTo(ByteBuf buf) {
            buf.writeByte(TYPE_CURIO);
            ByteBufCodecs.stringUtf8(MAX_SLOT_ID_LENGTH).encode(buf, slotId);
            ByteBufCodecs.VAR_INT.encode(buf, index);
        }
    }

    /** 取出这个位置上的物品。位置已失效时返回空堆，调用方自行判断 */
    ItemStack resolve(Player player);

    /** 改完之后把物品写回去。只有饰品栏真的需要这一步 */
    void writeBack(Player player, ItemStack stack);

    /**
     * 把自己写进缓冲区，第一个字节是种类（见下面那四个 TYPE_ 常量），与
     * {@code STREAM_CODEC.decode} 那张表一一对应。
     *
     * <h2>为什么是接口方法而不是一个 switch</h2>
     *
     * 原先这一段写在 {@code STREAM_CODEC.encode} 里，是一个对密封接口做模式匹配的
     * switch。但 switch 里的类型模式是 Java 21 才转正的（JEP 441），1.20.1 那一支
     * 跑在 17 上，那边编不过；而抽象方法是 Java 1.0 就有的东西，两边都成立。
     *
     * <h2>它比 switch 弱在哪儿 —— 空方法体</h2>
     *
     * switch 那种写法漏一种是【编不过】，不可绕。抽象方法只逼你<b>写</b>一个方法体，
     * 不管你<b>写对</b>没有：
     *
     * <pre>{@code public void writeTo(ByteBuf buf) {}}</pre>
     *
     * 这是编得过的，而它产生的正好是这里最怕的那个症状 —— 发出去一个不带种类字节的包，
     * 对面照着上一个字段的位置解，解出来是另一种位置，两边都不报错。
     *
     * 所以这个保证的完整说法是：<b>加第四种位置时必须写它，且不能写成空的。</b>
     * 后半句没有编译器守着，守它的是 {@code docs/PhoneLocationCodecTest} —— 那份测试
     * 把每一种的字节逐个钉死，空方法体会让它当场变红。加位置时请一并加一条断言。
     *
     * （被否掉的第三种写法是 {@code instanceof} 链加末尾 {@code else throw}：它编得过，
     * 但第一次发送新类型时会当场抛、还带类名。就"漏一种"这一件事，它比空方法体响得早，
     * 只是把编译期的事推到了运行期。选抽象方法是因为它把两头的好处各占了一半：
     * 逼你动手，且不必等到运行时。）
     *
     * 顺带也更像这个文件本来的样子 —— resolve 与 writeBack 一直是这么分派的，
     * 那个 switch 才是三兄弟里的例外。
     */
    void writeTo(ByteBuf buf);

    /**
     * 从玩家身上找出一部手机，按顺手程度排序。
     *
     * 手上的优先：玩家正举着的那部显然就是他想用的。其次背包，最后饰品槽
     * ——放进饰品槽是"收起来"的意思，拿在手上的应该盖过它。
     *
     * 没装 Curios 时最后一步直接落空，不会碰到 Curios 的任何类，
     * 所以这个方法在任何情况下都能用。
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

        return CuriosCompat.findEquipped(player, PhoneItem::isPhone)
                .map(ref -> new InCurio(ref.slotId(), ref.index()));
    }

    //  网络

    /** 槽位 id 的长度上限，够任何合理的命名，也堵住超长字符串 */
    int MAX_SLOT_ID_LENGTH = 64;

    byte TYPE_MAIN_HAND = 0;
    byte TYPE_OFF_HAND = 1;
    byte TYPE_INVENTORY = 2;
    byte TYPE_CURIO = 3;

    /**
     * 手写而不是用 composite：这是个和类型有关的多态结构，
     * composite 只会按固定字段序列化。
     */
    StreamCodec<ByteBuf, PhoneLocation> STREAM_CODEC = new StreamCodec<>() {

        /**
         * ⚠ <b>加了第四种位置，除了实现 writeTo，还要回到这里加一个 case。</b>
         *
         * 这两半的守卫强度不一样，是刻意的、也是不对称的：编码那一侧漏了会被抽象方法
         * 拦住（至少逼你写），解码这一侧漏了则一声不响 —— 新种类落进下面那个
         * {@code default}，被解成"主手"。而那正是这个 default 的用处（见下面那段注释），
         * 所以它不能改成抛异常。
         *
         * 也就是说这一侧唯一的守卫是 {@code docs/PhoneLocationCodecTest} 的往返断言：
         * 加位置时一并加一条 roundTrip，漏了就红。
         */
        @Override
        public PhoneLocation decode(ByteBuf buf) {
            byte type = buf.readByte();
            return switch (type) {
                case TYPE_OFF_HAND -> new InHand(InteractionHand.OFF_HAND);
                case TYPE_INVENTORY -> new InInventory(ByteBufCodecs.VAR_INT.decode(buf));
                case TYPE_CURIO -> new InCurio(
                        ByteBufCodecs.stringUtf8(MAX_SLOT_ID_LENGTH).decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf));
                // 主手，以及任何不认识的类型。版本不一致或伪造客户端送来未知值时，
                // 宁可退回一个无害的默认位置，也不要抛异常打断整条连接——
                // 反正服务端还要验一次那儿是不是真有手机。Relation 那边同理
                default -> new InHand(InteractionHand.MAIN_HAND);
            };
        }

        @Override
        public void encode(ByteBuf buf, PhoneLocation value) {
            value.writeTo(buf);
        }
    };
}
