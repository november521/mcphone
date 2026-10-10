package com.november.mcphone.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 业务存档的 NBT 读取语义。只使用各目标共有的 Tag 与 DynamicOps，
 * 隔离新版 getter 返回 Optional 的变化；字段名、缺省值和原有数值转换保持一致。
 * 严格类型判定单独提供，资金等数据仍先验证类型，再读取，不能用缺省值掩盖损坏。
 */
public final class PhoneNbt {
    private PhoneNbt() {}
    /** 旧 getter 的数字通配类型，不依赖新版已删除的 Tag 常量。 */
    public static final int ANY_NUMERIC = 99;

    public static boolean contains(CompoundTag tag, String key, int type) {
        Tag value = tag.get(key);
        if (value == null) return false;
        int actual = value.getId();
        return type == ANY_NUMERIC ? actual >= Tag.TAG_BYTE && actual <= Tag.TAG_DOUBLE : actual == type;
    }
    private static Number number(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value == null ? 0 : NbtOps.INSTANCE.getNumberValue(value).result().orElse(0);
    }
    public static long getLong(CompoundTag tag, String key) {
        Number value = number(tag, key);
        // 旧 DoubleTag 向下取整，FloatTag 则直接转 long；不能统一用 Number.longValue()。
        return value instanceof Double d ? (long) Math.floor(d) : value.longValue();
    }
    public static int getInt(CompoundTag tag, String key) {
        Number value = number(tag, key);
        if (value instanceof Double d) return Mth.floor(d);
        if (value instanceof Float f) return Mth.floor(f);
        return value.intValue();
    }
    public static boolean getBoolean(CompoundTag tag, String key) {
        Number value = number(tag, key);
        return (value instanceof Double || value instanceof Float ? (byte) getInt(tag, key) : value.byteValue()) != 0;
    }

    public static String getString(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value == null ? "" : NbtOps.INSTANCE.getStringValue(value).result().orElse("");
    }
    public static String getString(ListTag list, int index) {
        if (index < 0 || index >= list.size()) return "";
        Tag value = list.get(index);
        return NbtOps.INSTANCE.getStringValue(value).result().orElseGet(value::toString);
    }
    public static CompoundTag getCompound(CompoundTag tag, String key) {
        return tag.get(key) instanceof CompoundTag compound ? compound : new CompoundTag();
    }
    public static CompoundTag getCompound(ListTag list, int index) {
        if (index < 0 || index >= list.size()) return new CompoundTag();
        return list.get(index) instanceof CompoundTag compound ? compound : new CompoundTag();
    }
    public static ListTag getList(CompoundTag tag, String key, int elementType) {
        if (!(tag.get(key) instanceof ListTag list)) return new ListTag();
        // 26.1 允许混合类型列表；旧存档业务要求同类列表，不接受混入错误类型的元素。
        for (Tag value : list) if (value.getId() != elementType) return new ListTag();
        return list;
    }
    public static Set<String> getAllKeys(CompoundTag tag) {
        return NbtOps.INSTANCE.getMapValues(tag).result().orElseThrow()
                .map(entry -> NbtOps.INSTANCE.getStringValue(entry.getFirst()).result().orElseThrow())
                .collect(Collectors.toSet());
    }
    private static int[] uuidInts(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        if (value == null || value.getId() != Tag.TAG_INT_ARRAY) return new int[0];
        return NbtOps.INSTANCE.getIntStream(value).result().orElseThrow().toArray();
    }
    public static boolean hasUUID(CompoundTag tag, String key) { return uuidInts(tag, key).length == 4; }
    public static UUID getUUID(CompoundTag tag, String key) {
        int[] bits = uuidInts(tag, key);
        if (bits.length != 4) throw new IllegalArgumentException("UUID 必须是四项 int 数组：" + key);
        return new UUID((long) bits[0] << 32 | bits[1] & 0xffffffffL,
                (long) bits[2] << 32 | bits[3] & 0xffffffffL);
    }
    public static void putUUID(CompoundTag tag, String key, UUID id) {
        long most = id.getMostSignificantBits(), least = id.getLeastSignificantBits();
        tag.putIntArray(key, new int[]{(int) (most >>> 32), (int) most, (int) (least >>> 32), (int) least});
    }
}
