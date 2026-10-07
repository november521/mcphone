package com.november.mcphone.core.script.sfc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * .vue 单文件编译的全部拒绝理由（施工方案 §9.11）。行号是原始文件的行号（§9.8），列 1-based，0 表示没有列。
 *
 * <p>文案写在枚举上而不是抛出点：同一条判据在两处抛过，文案就会长出两个版本。
 */
public final class SfcError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 以 {@code %s:%d} 开头的模板，头两个参数是文件名与行号，由这个类自己填。 */
    public enum Code {
        E_SFC_BLOCK_FORMAT("%s:%d 顶层块标签必须独占一行并从第 1 列开始"),
        E_SFC_UNKNOWN_BLOCK("%s:%d 不认识的块 <%s>。只有 manifest / template / script / style"),
        E_SFC_DUP_BLOCK("%s:%d <%s> 出现了两次，每块只能有一个"),
        E_SFC_NESTED_BLOCK("%s:%d <%s> 还没闭合就开了 <%s>，顶层块不能嵌套"),
        E_SFC_UNEXPECTED_CLOSE("%s:%d 多余的 </%s>"),
        E_SFC_MISMATCH("%s:%d 开的是 <%s>，闭的是 </%s>"),
        E_SFC_UNCLOSED_BLOCK("%s:%d <%s> 没有闭合"),
        E_SFC_STRAY_TEXT("%s:%d 块外面不能有内容"),
        E_SFC_NO_MANIFEST("%s 缺少 <manifest> 块"),
        E_SFC_NO_TEMPLATE("%s 缺少 <template> 块"),
        E_SFC_MANIFEST("%s:%d %s"),

        E_TPL_UNKNOWN_ELEMENT("%s:%d 不认识的元素 <%s>%s。可用的 18 个：%s"),
        E_TPL_MUST_SELF_CLOSE("%s:%d <%s> 不能有子元素，写成 <%s />"),
        E_TPL_UNKNOWN_ATTR("%s:%d <%s> 没有属性 '%s'%s。它接受：%s"),
        E_TPL_LIKELY_MISSING_COLON("%s:%d %s=\"%s\" 是字符串。要用变量写成 :%s=\"%s\""),
        E_TPL_DANGLING_ELSE("%s:%d v-else 前面必须紧跟 v-if 或 v-else-if"),
        E_TPL_SHADOW("%s:%d v-for 的变量 '%s' 和 state 里的同名，换个名字"),
        E_TPL_NESTED_FOR("%s:%d v-for 嵌套最多 2 层"),
        E_TPL_EVENT_NOT_ALLOWED("%s:%d <%s> 不接受 @click，只有 button / toggle / tab-bar 可以"),
        E_TPL_UNCLOSED_TAG("%s:%d <%s> 没有闭合"),
        E_TPL_SYNTAX("%s:%d 模板语法错误：%s"),
        E_TPL_BAD_VALUE("%s:%d <%s> 的属性 '%s' 值 '%s' 不合法。允许：%s"),

        E_EXPR_SYNTAX("%s:%d 表达式语法错误：%s"),
        E_EXPR_UNKNOWN_IDENT("%s:%d 不认识 '%s'%s。已声明的有：%s"),
        E_EXPR_NO_ASSIGNMENT("%s:%d {{ }} 里不能赋值，赋值只能写在 @click 里"),
        E_EXPR_NO_CALLS("%s:%d P0 不支持调用方法，P1 开放 <script>"),
        E_EXPR_TYPE("%s:%d '%s' 两边类型是 %s 和 %s，这个运算不支持"),
        E_EXPR_TOO_COMPLEX("%s:%d 表达式太复杂（%d 个节点，上限 64），拆成 <script> 里的计算"),
        E_EXPR_TOO_LONG("%s:%d 表达式超过 256 字符"),

        E_SCRIPT_P0_SUBSET("%s:%d P0 的 <script> 只能写 state = { ... }，方法与逻辑在 P1 开放"),
        E_SCRIPT_STATE("%s:%d state 的 '%s' 不合法：%s"),
        E_SCRIPT_SYNTAX("%s:%d 前端脚本错误：%s");

        private final String text;
        private final int argc;

        Code(String text) {
            this.text = text;
            int n = 0;
            for (int i = 0; i + 1 < text.length(); i++) {
                if (text.charAt(i) == '%' && "sd".indexOf(text.charAt(i + 1)) >= 0) n++;
            }
            this.argc = n;
        }

        public String text() {
            return text;
        }

        /** 带行号的码。只有「缺块」两条说的是整个文件，没有行。 */
        public boolean positioned() {
            return text.startsWith("%s:%d");
        }
    }

    private final Code code;
    private final String file;
    private final int line;
    private final int col;
    private final Object[] args;

    private SfcError(Code code, String file, int line, int col, Object[] args) {
        super(render(code, file, line, args));
        this.code = code;
        this.file = file;
        this.line = line;
        this.col = col;
        this.args = args;
    }

    /** 块内或文件内的一处错误。文件名由 {@link SfcCompiler} 最外层补上。 */
    public static SfcError at(Code code, int line, int col, Object... args) {
        return new SfcError(code, null, line, col, args.clone());
    }

    public Code code() {
        return code;
    }

    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

    public int col() {
        return col;
    }

    public String message() {
        return getMessage();
    }

    /** 行号整体下移：块内第 1 行 → 原文件第 startLine 行（§9.8）。列不动，块内容从行首开始。 */
    public SfcError shift(int lines) {
        return code.positioned() ? new SfcError(code, file, line + lines, col, args) : this;
    }

    /** 同一条错误换到另一个位置：实体解码后的串上算出的行列要换回原文上的。 */
    public SfcError movedTo(int newLine, int newCol) {
        return new SfcError(code, file, newLine, newCol, args);
    }

    public SfcError inFile(String name) {
        return new SfcError(code, name, line, col, args);
    }

    /** 实参个数与模板对不上时当场抛：少一个参数的后果是 MissingFormatArgumentException，要等这条分支真被走到才暴露。 */
    private static String render(Code code, String file, int line, Object[] args) {
        List<Object> flat = new ArrayList<>();
        flat.add(file == null ? "app.vue" : file);
        if (code.positioned()) flat.add(line);
        for (Object a : args) flat.add(a);
        if (flat.size() != code.argc) {
            throw new IllegalStateException(code + " 要 " + code.argc + " 个参数，给了 " + flat.size() + " 个");
        }
        return code.name() + "：" + String.format(Locale.ROOT, code.text, flat.toArray());
    }
}
