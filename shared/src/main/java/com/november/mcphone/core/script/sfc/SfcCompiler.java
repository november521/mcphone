package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.layout.MssError;
import com.november.mcphone.core.script.layout.MssParser;
import com.november.mcphone.core.script.layout.Stylesheet;
import com.november.mcphone.core.script.pkg.Manifest;
import com.november.mcphone.core.script.pkg.PackageError;
import com.november.mcphone.core.script.sfc.SfcError.Code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * .vue 单文件 → 清单、样式表、编译好的模板（施工方案 §9.9）。装包时编一次，产物常驻。
 *
 * <p>所有错误报原始文件的行号（§9.8）：下游按块内行号报，这里统一加 {@code startLine - 1}。
 * 列不偏移，块内容从行首开始。
 */
public final class SfcCompiler {

    /** 编译产物。页面打开时用 {@code UiState.of(template.initialState())} 建状态，用 {@link TemplateInstance} 出树。 */
    public record App(Manifest manifest, Stylesheet stylesheet, CompiledTemplate template) {
    }

    /** 一页的编译产物，没有清单。zip 形态（§11.2 形态二）的 app.vue 与 pages/*.vue 走这条。 */
    public record Page(Stylesheet stylesheet, CompiledTemplate template) {
    }

    private static final Pattern GSON_POSITION = Pattern.compile("line (\\d+) column (\\d+)");

    private SfcCompiler() {
    }

    /** file 只用于报错文案。只抛 {@link SfcError} 与 {@link MssError}。 */
    public static App compile(String file, String src) {
        Objects.requireNonNull(file, "file");
        try {
            Map<String, Block> blocks = SfcSplitter.split(src, true);
            Manifest manifest = manifest(blocks.get("manifest"));
            return new App(manifest, style(blocks), template(blocks));
        } catch (SfcError e) {
            throw e.inFile(file);
        }
    }

    /**
     * 编一页，不要 {@code <manifest>} 块（§11.2 形态二：清单是包里独立的 manifest.json）。
     * 有 {@code <manifest>} 块照样收下但不读它 —— 拒掉的话，作者把单文件 App 塞进 zip 时会看到一条他改不明白的错。
     */
    public static Page compilePage(String file, String src) {
        Objects.requireNonNull(file, "file");
        try {
            Map<String, Block> blocks = SfcSplitter.split(src, false);
            return new Page(style(blocks), template(blocks));
        } catch (SfcError e) {
            throw e.inFile(file);
        }
    }

    private static Stylesheet style(Map<String, Block> blocks) {
        Block styleBlock = blocks.get("style");
        try {
            return MssParser.parse(styleBlock == null ? "" : styleBlock.content());
        } catch (MssError e) {
            throw e.shift(styleBlock.startLine() - 1);
        }
    }

    private static CompiledTemplate template(Map<String, Block> blocks) {
        Block scriptBlock = blocks.get("script");
        Map<String, Object> state;
        try {
            state = scriptBlock == null ? Map.of() : ScriptParser.parse(scriptBlock.content());
        } catch (SfcError e) {
            throw e.shift(scriptBlock.startLine() - 1);
        }

        Block templateBlock = blocks.get("template");
        try {
            return TemplateCompiler.compile(templateBlock.content(), state, templateBlock.startLine() - 1);
        } catch (SfcError e) {
            throw e.shift(templateBlock.startLine() - 1);
        }
    }

    private static Manifest manifest(Block block) {
        try {
            return Manifest.parseInline(block.content());
        } catch (PackageError e) {
            int line = manifestLine(block.content(), e);
            Matcher at = GSON_POSITION.matcher(e.getMessage());
            int col = e.code() == PackageError.Code.E_PKG_MANIFEST_SYNTAX && at.find() ? Integer.parseInt(at.group(2)) : 0;
            throw SfcError.at(Code.E_SFC_MANIFEST, line, col, forAuthor(e.getMessage())).shift(block.startLine() - 1);
        }
    }

    /** 去掉 Gson 文案里的块内行列（前面已经换算成原文件行号，留着会自相矛盾）和写给开发者的提示。 */
    static String forAuthor(String message) {
        String out = message.replaceAll("\\s*at line \\d+ column \\d+ path \\S*", "")
                .replaceAll("\\s*Use JsonReader\\.set\\w+\\([^)]*\\) to accept malformed JSON", "")
                .replaceAll("\\s*See https?://\\S+", "")
                .strip();
        // Gson 的原因有时整句都是上面那几段，删完只剩冒号
        return out.endsWith("：") ? out + "JSON 写法不对，检查这一行的引号、逗号、冒号与括号" : out;
    }

    /**
     * 清单错误落在块内第几行。语法错取 Gson 报的行；字段错取那个键所在的行（重复键取第二次出现）；
     * 缺字段这类没有具体位置的，指到对象开头。
     */
    static int manifestLine(String json, PackageError e) {
        int open = firstNonBlankLine(json);
        List<Object> args = e.args();
        String field = switch (e.code()) {
            case E_PKG_BAD_FORMAT -> "format";
            case E_PKG_BAD_ID, E_PKG_ID_TOO_LONG, E_PKG_RESERVED_NAMESPACE -> "id";
            case E_PKG_BAD_VERSION -> "version";
            case E_PKG_BAD_ICON -> "icon";
            case E_PKG_BAD_TYPE, E_PKG_TEXT_TOO_LONG, E_PKG_TEXT_CONTROL_CHAR, E_PKG_MANIFEST_DUP_KEY ->
                    args.isEmpty() ? null : String.valueOf(args.get(0));
            default -> null;
        };
        if (e.code() == PackageError.Code.E_PKG_MANIFEST_SYNTAX) {
            Matcher m = GSON_POSITION.matcher(e.getMessage());
            if (m.find()) return Integer.parseInt(m.group(1));
            return lastNonBlankLine(json);
        }
        if (field == null) return open;
        List<Integer> lines = keyLines(json).get(field);
        if (lines == null) return open;
        return e.code() == PackageError.Code.E_PKG_MANIFEST_DUP_KEY && lines.size() > 1 ? lines.get(1) : lines.get(0);
    }

    /** 顶层对象每个键出现在哪几行。只认字符串与括号层数，JSON 合不合法不归它管。 */
    static Map<String, List<Integer>> keyLines(String json) {
        Map<String, List<Integer>> out = new HashMap<>();
        int depth = 0;
        int line = 1;
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\n') {
                line++;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (c == '"') {
                int startLine = line;
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\' && i + 1 < json.length()) i++;
                    if (json.charAt(i) == '\n') line++;
                    sb.append(json.charAt(i));
                    i++;
                }
                int j = i + 1;
                while (j < json.length() && Character.isWhitespace(json.charAt(j)) && json.charAt(j) != '\n') j++;
                if (depth == 1 && j < json.length() && json.charAt(j) == ':') {
                    out.computeIfAbsent(sb.toString(), k -> new ArrayList<>()).add(startLine);
                }
            }
            i++;
        }
        return out;
    }

    private static int firstNonBlankLine(String s) {
        String[] lines = s.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) return i + 1;
        }
        return 1;
    }

    private static int lastNonBlankLine(String s) {
        String[] lines = s.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return i + 1;
        }
        return 1;
    }
}
