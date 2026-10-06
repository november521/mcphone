package com.november.mcphone.core.script.layout;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 18 个节点类型与它们的能力（施工方案 §4.7，能力表见 §5.4）。
 *
 * <p>{@code BUTTON} 既是容器又可交互：不让它有 children，「图标 + 文字」的按钮就得套一层 stack。
 */
public enum NodeType {
    //       json 名     容器   交互   接受 children  专有字段
    BOX     ("box",      true,  false, true),
    COLUMN  ("column",   true,  false, true),
    ROW     ("row",      true,  false, true),
    STACK   ("stack",    true,  false, true),
    SCROLL  ("scroll",   true,  false, true),
    GRID    ("grid",     true,  false, true,  "cols"),
    LIST    ("list",     true,  false, true,  "item-height"),
    SPACER  ("spacer",   false, false, false, "size"),
    DIVIDER ("divider",  false, false, false, "vertical"),
    TEXT    ("text",     false, false, false, "text", "i18n", "args"),
    IMAGE   ("image",    false, false, false, "src", "w", "h"),
    ICON    ("icon",     false, false, false, "name", "size"),
    ITEM    ("item",     false, false, false, "item", "count", "size"),
    BADGE   ("badge",    false, false, false, "count"),
    PROGRESS("progress", false, false, false, "value", "height"),
    BUTTON  ("button",   true,  true,  true,  "text", "i18n", "args", "enabled", "enabledIf", "onClick"),
    TOGGLE  ("toggle",   false, true,  false, "bind", "label", "i18n", "enabled"),
    TAB_BAR ("tab-bar",  false, true,  false, "bind", "tabs"),
    TEXT_INPUT("text-input", false, true, false, "bind", "placeholder", "max-length", "enabled");

    public final String json;
    public final boolean container;
    public final boolean interactive;
    public final boolean acceptsChildren;

    private final Set<String> props;

    NodeType(String json, boolean container, boolean interactive, boolean acceptsChildren,
             String... props) {
        this.json = json;
        this.container = container;
        this.interactive = interactive;
        this.acceptsChildren = acceptsChildren;
        this.props = Set.of(props);
    }

    /** 这个类型自己的字段（§5.4 的「专有字段」那一列）。 */
    public Set<String> props() {
        return props;
    }

    /**
     * 这个类型「认识」的全部字段。
     *
     * <p>{@code children} 每个类型都认识：§4.3 把它列为通用字段，非容器写了它是
     * {@code E_CHILDREN_NOT_ALLOWED}，不是「不认识的字段」。{@code onClick} 相反 ——
     * 没有对应的专用错误码，不可交互的类型写了它就按不认识处理。
     */
    public Set<String> acceptedFields() {
        Set<String> out = new LinkedHashSet<>();
        out.add("type");
        out.add("id");
        out.add("class");
        out.add("showIf");
        out.add("children");
        if (interactive) out.add("onClick");
        out.addAll(props);
        return out;
    }

    /** 按 json 名查，查不到返回 null。 */
    public static NodeType of(String json) {
        for (NodeType t : values()) {
            if (t.json.equals(json)) return t;
        }
        return null;
    }

    /** 全部 json 名，按枚举顺序，用于报错时列出允许的取值。 */
    public static String allNames() {
        return String.join(" ", Arrays.stream(values()).map(t -> t.json).toList());
    }

    /** 接受 children 的那些类型，报错时列给作者看。 */
    public static String childrenAcceptors() {
        return String.join(" ", Arrays.stream(values()).filter(t -> t.acceptsChildren)
                .map(t -> t.json).toList());
    }

    @Override
    public String toString() {
        return json;
    }
}
