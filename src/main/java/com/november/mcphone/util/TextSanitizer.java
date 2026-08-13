package com.november.mcphone.util;

/**
 * 玩家输入文本的清洗 —— 设备名、聊天消息等一切来自客户端的文本共用。
 *
 * ============================================================
 * 为什么必须收发两端都洗
 * ============================================================
 *
 * 发送端要洗：超长字符串在编码阶段就会抛 EncoderException。
 * 接收端也要洗：客户端可以是伪造的，绕过界面直接发包，服务端一律不信。
 *
 * ============================================================
 * 为什么抽成共用工具
 * ============================================================
 *
 * 设备名与聊天消息的清洗规则完全一致，只是长度上限不同。各写一份的话，
 * 日后补一条规则（比如再屏蔽某类字符）必然漏掉另一处——这类安全相关的
 * 代码最不该有两份。
 */
public final class TextSanitizer {

    private TextSanitizer() {}

    /**
     * 清洗一段玩家输入，做三件事：
     *
     *   - 去掉 § 格式符，否则玩家能把文本染色、做成乱码，
     *     甚至用 §k 乱码字符干扰别人的界面
     *   - 去掉控制字符（含换行与 DEL），这些文本都是单行的
     *   - 截断到 maxLength，且不切开代理对——否则表情符号会被劈成
     *     半个，渲染出来是个方框，严重时能让字体渲染器出错
     *
     * @param raw       原始输入，允许为 null
     * @param maxLength 长度上限（字符数，非字节数）
     * @return 清洗后的文本，绝不为 null
     */
    public static String sanitize(String raw, int maxLength) {
        if (raw == null) return "";

        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') continue;              // § 格式符
            if (c < ' ' || c == 0x7F) continue;   // 控制字符（含换行与 DEL）
            sb.append(c);
        }

        String out = sb.toString().trim();
        if (out.length() > maxLength) {
            out = out.substring(0, maxLength);
            // 截断点正好落在代理对中间时把落单的高位代理去掉
            if (Character.isHighSurrogate(out.charAt(out.length() - 1))) {
                out = out.substring(0, out.length() - 1);
            }
            out = out.trim();
        }
        return out;
    }
}
