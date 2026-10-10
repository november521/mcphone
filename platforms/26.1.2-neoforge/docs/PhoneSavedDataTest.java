package com.november.mcphone.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

/** 26.1.2 类型缓存、真实 NBT Codec 与损坏数据保留的回归；不碰玩家存档。 */
public final class PhoneSavedDataTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Data extends PhoneSavedData {
        private final CompoundTag content;
        Data() { this(new CompoundTag()); }
        Data(CompoundTag content) { this.content = content.copy(); }
        @Override protected CompoundTag write(CompoundTag tag) { return tag.merge(content.copy()); }
    }
    public static void main(String[] args) {
        var first = PhoneSavedData.type("mcphone_probe", Data::new, Data::new);
        var second = PhoneSavedData.type("mcphone_probe", Data::new, Data::new);
        check(first == second, "重复获取必须复用同一个类型，不能产生独立存档缓存");
        check(first.codec() == second.codec(), "Codec 身份稳定");
        check(first != PhoneSavedData.type("mcphone_other", Data::new, Data::new), "不同文件不共用类型");
        check(first.id().toString().equals("mcphone:mcphone_probe"), "使用模组命名空间并保留文件名");
        var original = new CompoundTag();
        original.putLong("balance", Long.MAX_VALUE);
        original.putString("hidden", "保留损坏的删除标记原文");
        var nested = new CompoundTag(); nested.putString("sender", "中文玩家"); original.put("message", nested);
        Data data = new Data(original);
        data.setDirty();
        var encoded = first.codec().encodeStart(NbtOps.INSTANCE, data).result().orElseThrow();
        check(encoded.equals(original), "Codec 保留现有 NBT 结构及未知字段");
        check(data.isDirty(), "编码不能清掉待保存状态");
        var restored = first.codec().parse(NbtOps.INSTANCE, encoded).result().orElseThrow();
        check(restored != data, "读取构造独立业务实例");
        check(restored.content.equals(original), "重载内容完整，包括损坏原文与 long 边界");
        restored.content.putLong("balance", 1);
        check(original.getLongOr("balance", 0) == Long.MAX_VALUE, "读取不共享输入对象");
        check(first.codec().encodeStart(NbtOps.INSTANCE, restored).result().orElseThrow() instanceof CompoundTag,
                "保存保持 CompoundTag 外形");
        check(first.codec().parse(NbtOps.INSTANCE, net.minecraft.nbt.StringTag.valueOf("损坏根节点")).error().isPresent(),
                "非 CompoundTag 根节点必须报错");
        check(first.constructor().get() != first.constructor().get(), "新建函数不跨世界共用业务实例");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
