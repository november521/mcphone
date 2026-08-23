package com.november.mcphone.feature.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * ConversationKey 的断言测试 —— 用 javac 单独编，不需要 Minecraft。
 *
 * 为什么值得单独钉一遍：1.4.17 把会话键从 "a|b" 字符串换成了记录，而这个
 * 键决定【一整个服务器的聊天记录能不能被找回来】。转换写错一个方向，
 * 表现不是崩溃，是"升级之后有些人的聊天记录不见了"——而且存档一存，
 * 原来的数据就真没了，不可逆。
 *
 * 最容易错的一处：运行时按 UUID.compareTo 归一化（不分配），写出去按
 * 字符串序（老格式的规矩）。两种顺序【不一致】，所以 toStorageKey 必须
 * 转调 FriendGraph.pairKey。下面 sortOrdersActuallyDiffer 就是专门证明
 * "这两种顺序确实会给出不同结果"的——否则这条测试等于没测。
 *
 *   javac -d /tmp/ck src/main/java/com/november/mcphone/feature/chat/ConversationKey.java \
 *                    src/main/java/com/november/mcphone/feature/chat/FriendGraph.java \
 *                    docs/ConversationKeyTest.java
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

            // 1. 归一化：谁在前都是同一个键
            eq(ConversationKey.of(a, b), ConversationKey.of(b, a), "of 两个方向应当相等");

            // 2. 存档键与老代码写出来的那一个逐字节相同。
            //    老代码是 FriendGraph.pairKey(a, b)，参数顺序随调用方
            String legacyAB = FriendGraph.pairKey(a, b);
            String legacyBA = FriendGraph.pairKey(b, a);
            eq(legacyAB, legacyBA, "老格式本身就与参数顺序无关");
            eq(ConversationKey.of(a, b).toStorageKey(), legacyAB, "存档键必须与老格式一致");

            // 3. 存档转一圈回来还是同一个键
            eq(ConversationKey.parse(ConversationKey.of(a, b).toStorageKey()),
               ConversationKey.of(a, b), "存档转一圈应当原样回来");

            // 4. 两种排序顺序确实会分道扬镳 —— 证明第 2 条不是巧合
            boolean uuidOrder = a.compareTo(b) <= 0;
            boolean stringOrder = a.toString().compareTo(b.toString()) <= 0;
            if (uuidOrder != stringOrder) orderDiffers++;
        }

        check(orderDiffers > 0,
                "UUID 序与字符串序应当存在分歧，否则上面那条测试是空的（分歧 "
                        + orderDiffers + " 次）");

        // 5. 脏数据不抛异常，只返回 null
        eq(ConversationKey.parse(null), null, "null 应当返回 null");
        eq(ConversationKey.parse(""), null, "空串应当返回 null");
        eq(ConversationKey.parse("没有竖线"), null, "缺分隔符应当返回 null");
        eq(ConversationKey.parse("不是|uuid"), null, "非法 UUID 应当返回 null");
        eq(ConversationKey.parse(UUID.randomUUID() + "|"), null, "右半为空应当返回 null");

        // 6. 自己和自己的会话也得能表达（存档被手改时会出现）
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
