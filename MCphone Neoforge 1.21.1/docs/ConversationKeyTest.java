package com.november.mcphone.feature.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * ConversationKey 的断言测试，用 javac 单独编，不需要 Minecraft。
 * 坑：运行时按 UUID.compareTo 归一化，存档键按字符串序（老格式），两者不一致，toStorageKey 必须转调 FriendGraph.pairKey。
 *   javac -d /tmp/ck src/main/java/com/november/mcphone/feature/chat/{ConversationKey,FriendGraph}.java docs/ConversationKeyTest.java
 *   java -cp /tmp/ck com.november.mcphone.feature.chat.ConversationKeyTest
 */
public class ConversationKeyTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    public static void main(String[] args) {
        Random rnd = new Random(20260823L);   // 固定种子，失败可复现

        int orderDiffers = 0;

        for (int i = 0; i < 20000; i++) {
            UUID a = new UUID(rnd.nextLong(), rnd.nextLong());
            UUID b = new UUID(rnd.nextLong(), rnd.nextLong());

            eq(ConversationKey.of(a, b), ConversationKey.of(b, a), "of 两个方向应当相等");

            String legacyAB = FriendGraph.pairKey(a, b);
            String legacyBA = FriendGraph.pairKey(b, a);
            eq(legacyAB, legacyBA, "老格式本身就与参数顺序无关");
            eq(ConversationKey.of(a, b).toStorageKey(), legacyAB, "存档键必须与老格式一致");

            eq(ConversationKey.parse(ConversationKey.of(a, b).toStorageKey()),
               ConversationKey.of(a, b), "存档转一圈应当原样回来");

            boolean uuidOrder = a.compareTo(b) <= 0;
            boolean stringOrder = a.toString().compareTo(b.toString()) <= 0;
            if (uuidOrder != stringOrder) orderDiffers++;
        }

        check(orderDiffers > 0,
                "UUID 序与字符串序应当存在分歧，否则上面那条测试是空的（分歧 "
                        + orderDiffers + " 次）");

        eq(ConversationKey.parse(null), null, "null 应当返回 null");
        eq(ConversationKey.parse(""), null, "空串应当返回 null");
        eq(ConversationKey.parse("没有竖线"), null, "缺分隔符应当返回 null");
        eq(ConversationKey.parse("不是|uuid"), null, "非法 UUID 应当返回 null");
        eq(ConversationKey.parse(UUID.randomUUID() + "|"), null, "右半为空应当返回 null");

        UUID same = UUID.randomUUID();
        eq(ConversationKey.of(same, same), new ConversationKey(same, same), "自己与自己");

        System.out.println("UUID 序与字符串序的分歧次数: " + orderDiffers + " / 20000");
        if (failures.isEmpty()) {
            System.out.println("全部通过，共 " + checks + " 项断言");
        } else {
            System.out.println("失败 " + failures.size() + " 项，共 " + checks + " 项断言：");
            failures.stream().limit(10).forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }
}
