package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ChatMessage 存档格式的断言测试 —— 守的是"升级不丢聊天记录、降级也不丢文本消息"。
 *
 * 为什么这一条必须有测试
 *
 * 整份聊天记录是一个 Codec 解出来的（ChatData 里那个 unboundedMap）。中间只要有一条
 * 读不懂，解出来的就不是"少一条消息"，而是一个 error —— 全服的聊天记录一起消失，
 * 而且是在服主升级或回退版本的那一刻，谁也没看着。
 *
 * 这个测试要 Minecraft 的类（UUIDUtil、NbtOps），所以不能像 ConversationKeyTest 那样
 * 光用 javac 编。跑法（先 ./gradlew compileJava 生成 build/moddev 的类路径文件）：
 *
 *   CP="build/classes/java/main:build/moddev/artifacts/neoforge-21.1.248-merged.jar:$(tr '\n' ':' < build/moddev/serverLegacyClasspath.txt)"
 *   javac -cp "$CP" -d /tmp/cmc docs/ChatMessageCodecTest.java
 *   java -cp "/tmp/cmc:$CP" com.november.mcphone.feature.chat.ChatMessageCodecTest
 *
 * 换 NeoForge 版本时上面那个 jar 名要跟着改，它写在 gradle.properties 的 neo_version 里。
 */
public class ChatMessageCodecTest {

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
        UUID sender = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID image = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

        //  老存档读得出来

        CompoundTag legacy = new CompoundTag();
        legacy.putIntArray("sender", toIntArray(sender));
        legacy.putString("text", "老版本写下的一条");
        legacy.putLong("time", 1_700_000_000_000L);

        ChatMessage fromLegacy = decode(legacy, "老格式");
        check(fromLegacy != null, "老格式必须读得出来");
        if (fromLegacy != null) {
            eq(fromLegacy.sender(), sender, "老格式的发件人");
            eq(fromLegacy.time(), 1_700_000_000_000L, "老格式的时间");
            eq(fromLegacy.body(), new TextBody("老版本写下的一条"), "老格式的正文");
        }

        //  文本消息仍按老格式写（服主降级回去也读得懂）

        ChatMessage text = ChatMessage.text(sender, "新版本写下的一条", 1_700_000_001_000L);
        Tag encodedText = encode(text, "文本消息");
        check(encodedText instanceof CompoundTag, "文本消息应当编成一个复合标签");
        if (encodedText instanceof CompoundTag tag) {
            check(tag.contains("text"), "文本消息必须仍有 text 字段，否则老版本读不出来");
            check(!tag.contains("body"), "文本消息不该出现 body 字段");
            check(!tag.contains("kind"), "文本消息不该出现 kind 字段");
        }
        eq(decode(encodedText, "文本消息"), text, "文本消息转一圈应当原样回来");

        //  图片消息按新格式写，并且转一圈原样回来

        ChatMessage photo = new ChatMessage(sender, 1_700_000_002_000L, new ImageBody(image, 256, 144));
        Tag encodedPhoto = encode(photo, "图片消息");
        check(encodedPhoto instanceof CompoundTag, "图片消息应当编成一个复合标签");
        if (encodedPhoto instanceof CompoundTag tag) {
            check(!tag.contains("text"), "图片消息不该混进 text 字段");
            check(tag.contains("body"), "图片消息必须有 body 字段");
            Tag body = tag.get("body");
            check(body instanceof CompoundTag bt
                            && StringTag.valueOf("image").equals(bt.get("kind")),
                    "图片消息的 body 里必须有 kind=image");
        }
        eq(decode(encodedPhoto, "图片消息"), photo, "图片消息转一圈应当原样回来");

        //  混着老新两种的一份记录，整份都要读得出来

        Codec<Map<String, List<ChatMessage>>> conversations =
                Codec.unboundedMap(Codec.STRING, ChatMessage.CODEC.listOf());

        Map<String, List<ChatMessage>> original = Map.of("a|b", List.of(fromLegacy, text, photo));
        DataResult<Tag> encodedAll = conversations.encodeStart(NbtOps.INSTANCE, original);
        check(encodedAll.result().isPresent(), "混合记录必须写得出来: " + encodedAll.error().orElse(null));

        if (encodedAll.result().isPresent()) {
            DataResult<Map<String, List<ChatMessage>>> back =
                    conversations.parse(NbtOps.INSTANCE, encodedAll.result().get());
            check(back.result().isPresent(), "混合记录必须读得回来: " + back.error().orElse(null));
            back.result().ifPresent(map -> eq(map, original, "混合记录转一圈应当原样回来"));
        }

        //  越界的宽高被夹住，而不是抛出去

        ImageBody clamped = new ImageBody(image, 999_999, -3);
        eq(clamped.width(), ChatImage.MAX_SIDE, "越界的宽应当夹到上限");
        eq(clamped.height(), 1, "越界的高应当夹到下限");

        //  网络编解码转一圈

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChatMessage.STREAM_CODEC.encode(buf, photo);
        eq(ChatMessage.STREAM_CODEC.decode(buf), photo, "图片消息过网络应当原样回来");
        eq(buf.readableBytes(), 0, "解完应当正好读空，多一个字节都是格式对不上");

        FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
        ChatMessage.STREAM_CODEC.encode(buf2, text);
        eq(ChatMessage.STREAM_CODEC.decode(buf2), text, "文本消息过网络应当原样回来");

        //  结果

        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 项：");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }

    private static Tag encode(ChatMessage message, String what) {
        DataResult<Tag> result = ChatMessage.CODEC.encodeStart(NbtOps.INSTANCE, message);
        checks++;
        if (result.result().isEmpty()) {
            failures.add(what + " 写不出来: " + result.error().orElse(null));
            return null;
        }
        return result.result().get();
    }

    private static ChatMessage decode(Tag tag, String what) {
        DataResult<ChatMessage> result = ChatMessage.CODEC.parse(NbtOps.INSTANCE, tag);
        checks++;
        if (result.result().isEmpty()) {
            failures.add(what + " 读不出来: " + result.error().orElse(null));
            return null;
        }
        return result.result().get();
    }

    /** UUIDUtil.CODEC 存的是四个 int，与原版一致 */
    private static int[] toIntArray(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new int[] {
                (int) (most >> 32), (int) most,
                (int) (least >> 32), (int) least
        };
    }
}
