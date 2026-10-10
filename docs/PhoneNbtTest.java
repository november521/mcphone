package com.november.mcphone.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import java.util.Set;
import java.util.UUID;

/** 在每个平台的真实 NBT 类路径验证边界、严格类型与旧 UUID 格式。 */
public final class PhoneNbtTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        CompoundTag tag = new CompoundTag();
        check(PhoneNbt.getString(tag, "missing").isEmpty(), "缺字符串返回空");
        check(PhoneNbt.getLong(tag, "missing") == 0 && PhoneNbt.getInt(tag, "missing") == 0, "缺数字返回零");
        check(!PhoneNbt.getBoolean(tag, "missing"), "缺布尔返回 false");
        check(!PhoneNbt.contains(tag, "missing", Tag.TAG_END), "缺字段不等于 END");
        tag.putString("text", "中文、emoji 🐱\n多行");
        check(PhoneNbt.getString(tag, "text").equals("中文、emoji 🐱\n多行"), "文本完整");
        check(PhoneNbt.getLong(tag, "text") == 0, "错误数字类型保持缺省值");
        for (long value : new long[]{0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 40}) {
            tag.putLong("number", value);
            check(PhoneNbt.getLong(tag, "number") == value, "long 边界");
            check(PhoneNbt.getInt(tag, "number") == (int) value, "旧 getter 的数值窄化语义");
            check(PhoneNbt.contains(tag, "number", Tag.TAG_LONG), "严格类型正确");
            check(!PhoneNbt.contains(tag, "number", Tag.TAG_INT), "不能把 long 当成 int");
            check(PhoneNbt.contains(tag, "number", PhoneNbt.ANY_NUMERIC), "数字通配正确");
        }
        tag.putDouble("number", -0.5);
        check(PhoneNbt.getLong(tag, "number") == -1, "旧 double 转 long 向下取整");
        check(PhoneNbt.getInt(tag, "number") == -1, "旧 double 转 int 向下取整");
        check(PhoneNbt.getBoolean(tag, "number"), "旧 double 转 byte 后判断布尔");
        tag.putFloat("number", -0.5f);
        check(PhoneNbt.getLong(tag, "number") == 0, "旧 float 转 long 向零截断");
        check(PhoneNbt.getInt(tag, "number") == -1, "旧 float 转 int 向下取整");
        check(PhoneNbt.getBoolean(tag, "number"), "旧 float 转 byte 后判断布尔");
        tag.putDouble("number", 256.5);
        check(!PhoneNbt.getBoolean(tag, "number"), "旧 byte 窄化保留低八位");
        tag.putByte("flag", (byte) -1);
        check(PhoneNbt.getBoolean(tag, "flag"), "非零 byte 为 true");
        tag.putBoolean("flag", false);
        check(!PhoneNbt.getBoolean(tag, "flag"), "布尔 false");
        var nested = new CompoundTag(); nested.putString("name", "原文"); tag.put("nested", nested);
        check(PhoneNbt.getCompound(tag, "nested") == nested, "正确子对象保留引用语义");
        check(PhoneNbt.getCompound(tag, "text").isEmpty(), "错误子对象为空");
        var list = new ListTag(); list.add(StringTag.valueOf("一")); list.add(StringTag.valueOf("二")); tag.put("list", list);
        check(PhoneNbt.getList(tag, "list", Tag.TAG_STRING) == list, "正确列表保留引用语义");
        check(PhoneNbt.getList(tag, "list", Tag.TAG_COMPOUND).isEmpty(), "错误元素类型不混入业务");
        check(PhoneNbt.getString(list, 1).equals("二"), "列表文本");
        check(PhoneNbt.getString(list, -1).isEmpty() && PhoneNbt.getString(list, 2).isEmpty(), "列表字符串越界缺省值");
        check(PhoneNbt.getCompound(list, -1).isEmpty() && PhoneNbt.getCompound(list, 2).isEmpty(), "列表对象越界缺省值");
        check(PhoneNbt.getList(tag, "missing", Tag.TAG_STRING).isEmpty(), "缺列表为空");
        var compounds = new ListTag(); compounds.add(nested);
        check(PhoneNbt.getCompound(compounds, 0) == nested, "列表子对象");
        for (UUID id : new UUID[]{new UUID(0, 0), new UUID(-1, -1), new UUID(Long.MIN_VALUE, Long.MAX_VALUE), UUID.randomUUID()}) {
            PhoneNbt.putUUID(tag, "uuid", id);
            check(PhoneNbt.hasUUID(tag, "uuid"), "旧 int 数组 UUID 可识别");
            check(PhoneNbt.getUUID(tag, "uuid").equals(id), "UUID 符号位与边界往返");
            check(PhoneNbt.contains(tag, "uuid", Tag.TAG_INT_ARRAY), "UUID 写入原有 NBT 类型");
        }
        tag.putIntArray("uuid", new int[]{1, 2, 3});
        check(!PhoneNbt.hasUUID(tag, "uuid"), "损坏 UUID 不被识别");
        try { PhoneNbt.getUUID(tag, "uuid"); throw new AssertionError("损坏 UUID 必须报错"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(PhoneNbt.getAllKeys(tag).equals(Set.of("text", "number", "flag", "nested", "list", "uuid")), "字段集完整");
        check(PhoneNbt.getAllKeys(new CompoundTag()).isEmpty(), "空字段集");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
