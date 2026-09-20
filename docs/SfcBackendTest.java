package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.UiState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S17 Stage 2 的断言：宿主只读上下文 {@code backend}（§13.8）与 {@code @click} 的
 * {@code call('动作')} 语句（§15.1 的客户端那一截）。
 *
 * <p>覆盖：只读名字在编译期被承认但不能赋值/进 state/当 v-for 变量；实例化时按 host 表求值；
 * call 语句产出动作 id、与 nav/赋值并存；非法 call 被拒。
 */
public class SfcBackendTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final String MANIFEST =
            "{\"format\":1,\"id\":\"t:app\",\"version\":\"1.0.0\",\"name\":\"t\",\"author\":\"y\"}";

    static SfcCompiler.App compile(String template, String stateSrc) {
        String src = "<manifest>\n" + MANIFEST + "\n</manifest>\n"
                + "<template>\n" + template + "\n</template>\n"
                + "<script>\nstate = { " + stateSrc + " }\n</script>\n";
        return SfcCompiler.compile("t.vue", src);
    }

    record Built(UiState state, TemplateInstance.Tree tree) {
    }

    static Built build(String template, String stateSrc, Map<String, Object> host) {
        SfcCompiler.App app = compile(template, stateSrc);
        UiState state = UiState.of(app.template().initialState());
        return new Built(state, new TemplateInstance(app.template(), "t.vue").instantiate(state, host));
    }

    static SfcError errorOf(String template, String stateSrc) {
        try {
            compile(template, stateSrc);
            return null;
        } catch (SfcError e) {
            return e;
        }
    }

    /** 宿主注入表的形状与 {@code ClientHandshake.backendContext} 一致：外层键是 backend。 */
    static Map<String, Object> host(boolean available, String serverName, List<String> actions) {
        return Map.of("backend", Map.of(
                "available", available, "serverName", serverName, "actions", actions));
    }

    /** backend.available 让作者把"本服没部署"的分支画出来（§13.7 的作者那一半）。 */
    static void backendDrivesAuthorBranch() {
        Map<String, Object> up = host(true, "测试服", List.of("claim"));
        Map<String, Object> down = host(false, "", List.of());

        Built on = build("<column><text v-if=\"backend.available\">可用</text><text v-else>不可用</text></column>",
                "count: 0", up);
        eq(on.tree().root().children().size(), 1, "available=true 只留作者分支");
        eq(on.tree().root().children().get(0).str("text", null), "可用", "走的是 if 分支");

        Built off = build("<column><text v-if=\"backend.available\">可用</text><text v-else>不可用</text></column>",
                "count: 0", down);
        eq(off.tree().root().children().get(0).str("text", null), "不可用", "available=false 走 else");

        Built name = build("<text>{{ backend.serverName }}</text>", "count: 0", up);
        eq(name.tree().root().str("text", null), "测试服", "backend.serverName 可读");

        Built actions = build("<text>{{ backend.actions.length }}</text>", "count: 0", up);
        eq(actions.tree().root().str("text", null), "1", "backend.actions 可读");

        // 没有 host 时（普通实例化）backend 也有形状：available=false、actions 空
        Built none = build("<text>{{ backend.actions.length }}</text>", "count: 0", Map.of());
        eq(none.tree().root().str("text", null), "0", "缺省 host 下 actions 是空表");
    }

    /** 只读名字：不许进 state、不许赋值、不许当 v-for 变量。 */
    static void backendIsReadOnly() {
        SfcError inState = errorOf("<text>hi</text>", "backend: 1");
        check(inState != null && inState.code() == SfcError.Code.E_SCRIPT_STATE, "state 里写 backend 被拒");

        SfcError assign = errorOf("<button @click=\"backend = 1\">x</button>", "count: 0");
        check(assign != null && assign.code() == SfcError.Code.E_EXPR_SYNTAX, "@click 里给 backend 赋值被拒");

        SfcError loop = errorOf("<column><text v-for=\"backend in items\" :text=\"backend\"/></column>",
                "items: [1]");
        check(loop != null && loop.code() == SfcError.Code.E_TPL_SYNTAX, "v-for 变量叫 backend 被拒");

        SfcError unknown = errorOf("<text>{{ backend.available }}</text>", "count: 0");
        check(unknown == null, "backend 在模板里是已声明名字，不当拼错处理");
    }

    /** call('动作')：产出动作 id，交给宿主发（ScriptPage 负责），语句本身不改 state。 */
    static void callStatementProducesAction() {
        Built built = build("<button @click=\"call('claim_daily')\">领</button>", "count: 0", Map.of());
        Node button = built.tree().root();
        Statements.Outcome outcome = built.tree().click(button, built.state(), 0);
        check(outcome != null && outcome.applied(), "call 语句是合法 @click");
        eq(outcome.calls(), List.of("claim_daily"), "动作 id 原样带出来");

        Built mixed = build("<button @click=\"count++; call('a'); count++\">领</button>", "count: 0", Map.of());
        Statements.Outcome out = mixed.tree().click(mixed.tree().root(), mixed.state(), 0);
        eq(out.calls(), List.of("a"), "与赋值并存");
        eq(mixed.state().getInt("count"), 2, "赋值照常执行");

        Built nav = build("<button @click=\"call('a'); nav('detail')\">走</button>", "count: 0", Map.of());
        Statements.Outcome both = nav.tree().click(nav.tree().root(), nav.state(), 0);
        eq(both.calls(), List.of("a"), "call 与 nav 同时写");
        eq(both.nav(), "detail", "nav 也在");
    }

    /** 非法 call（没参数、非字符串、名字不合法）被拒；未知函数仍是 E_EXPR_NO_CALLS。 */
    static void badCallIsRejected() {
        SfcError noArg = errorOf("<button @click=\"call()\">x</button>", "count: 0");
        check(noArg != null, "call() 缺参数被拒");

        SfcError notString = errorOf("<button @click=\"call(1)\">x</button>", "count: 0");
        check(notString != null, "call(1) 被拒");

        SfcError empty = errorOf("<button @click=\"call('')\">x</button>", "count: 0");
        check(empty != null, "空动作名被拒");

        SfcError unknown = errorOf("<button @click=\"foo()\">x</button>", "count: 0");
        check(unknown != null && unknown.code() == SfcError.Code.E_EXPR_NO_CALLS,
                "别的函数名还是「不支持调用」");
    }

    public static void main(String[] args) {
        backendDrivesAuthorBranch();
        backendIsReadOnly();
        callStatementProducesAction();
        badCallIsRejected();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
