package com.november.mcphone.core.script;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/**
 * 严格 JSON 形状扫描（S18 对抗 E4）：Gson 的 {@code JsonParser} 是宽容的 —— 无引号键、
 * 单引号、注释、{@code NaN} 全收，而重复键在 {@code JsonObject} 里是<b>后者静默覆盖前者</b>。
 * 清单与安全开关（能力配置）都不能有两种读法：这里统一把
 * "不是严格 JSON、有重复键、嵌套过深"筛出来。
 *
 * <p>调用方（{@code Manifest} / {@code CapabilityConfig}）把 {@link Problem} 翻译成自己的
 * 错误码或保留策略。扫描只看形状，不做取值校验。
 */
public final class JsonScan {

    /** 逐段默认上限（配置类）。清单另用更严的 {@link #check(String, int)}。 */
    public static final int MAX_DEPTH = 32;

    public enum Kind {
        /** 同一对象里同一个键出现两次（后者会静默覆盖前者）。 */
        DUP_KEY,
        /** 严格 JSON 语法错（末梢有多余内容、宽容写法等）。 */
        SYNTAX,
        /** 嵌套过深。 */
        DEPTH
    }

    /** 扫描发现的问题；{@link #detail()} 是给人看的一句。 */
    public record Problem(Kind kind, String detail) {
    }

    private JsonScan() {
    }

    /** 没问题返回 {@code null}（深度用 {@link #MAX_DEPTH}）。 */
    public static Problem check(String json) {
        return check(json, MAX_DEPTH);
    }

    /** 没问题返回 {@code null}。{@code maxDepth} 由调用方按自己的格式定。 */
    public static Problem check(String json, int maxDepth) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            scan(reader, 0, maxDepth);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return new Problem(Kind.SYNTAX, "末尾还有多余的内容");
            }
            return null;
        } catch (Dup e) {
            return new Problem(Kind.DUP_KEY, e.getMessage());
        } catch (Deep e) {
            return new Problem(Kind.DEPTH, "嵌套深度超过 " + maxDepth);
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            return new Problem(Kind.SYNTAX, String.valueOf(e.getMessage()));
        }
    }

    private static void scan(JsonReader reader, int depth, int maxDepth) throws IOException {
        if (depth > maxDepth) throw new Deep();
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> keys = new HashSet<>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!keys.add(key)) throw new Dup(key);
                    scan(reader, depth + 1, maxDepth);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) scan(reader, depth + 1, maxDepth);
                reader.endArray();
            }
            // 数字按字符串吃掉：这里只管形状，值的范围留给调用方按自己的类型规则判
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IOException("不该出现的记号 " + reader.peek());
        }
    }

    /** 重复键：单拎出来，调用方要按"两种读法"单独报码。 */
    private static final class Dup extends IOException {
        Dup(String key) {
            super("重复键：" + key);
        }
    }

    /** 嵌套过深。 */
    private static final class Deep extends IOException {
    }
}
