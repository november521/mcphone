package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.engine.ClientScript;
import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.layout.PageParameters;
import com.november.mcphone.core.script.server.store.LocalStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Function;

/** State retained within one open App; lifecycle hooks belong to activation, never rendering or ticking. */
public final class PageStates {
    private final Map<String, UiState> states = new HashMap<>();

    public record Opened(UiState state, List<String> warnings) {}

    public Opened open(String name, CompiledTemplate template, String file, Supplier<LocalStore> storage) {
        return open(name, template, file, storage, null);
    }

    public Opened open(String name, CompiledTemplate template, String file, Supplier<LocalStore> storage,
                       Function<UiState, HttpRequests> network) {
        return open(name, template, file, storage, network, PageParameters.EMPTY);
    }

    /** Keep only the latest parameter variant for each named page; back entries retain their own state. */
    public Opened open(String name, CompiledTemplate template, String file, Supplier<LocalStore> storage,
                       Function<UiState, HttpRequests> network, PageParameters parameters) {
        UiState existing = states.get(name);
        if (existing != null && existing.parameters().equals(parameters)) {
            return activate(existing, template, "onResume", file, storage, network);
        }

        UiState state = UiState.page(template.initialState(), parameters);
        // Failed initialization counts as attempted too; retained pages never retry onInit or lose edits.
        states.put(name, state);
        return activate(state, template, "onInit", file, storage, network);
    }

    private static Opened activate(UiState state, CompiledTemplate template, String hook, String file,
                                   Supplier<LocalStore> storage, Function<UiState, HttpRequests> network) {
        ClientScript script = template.script();
        if (script == null || !script.declares(hook)) return new Opened(state, List.of());

        try (LocalStore.Transaction transaction = new LocalStore.Transaction(storage);
             HttpRequests requests = network == null ? null : network.apply(state)) {
            Map<String, Object> next = script.invoke(hook, List.of(), state.values(), state, transaction, requests);
            if (!transaction.commit()) return failed(state, file, hook, "本地存储保存失败，原文件已保留");
            for (var entry : next.entrySet()) state.set(entry.getKey(), entry.getValue());
            if (requests != null) requests.commit();
            return new Opened(state, List.of());
        } catch (ClientScript.Failure failure) {
            return failed(state, file, hook, failure.getMessage());
        }
    }

    /** Restore the exact originating state, even after a different variant of that page was opened. */
    public Opened restore(String name, UiState retained) {
        states.put(name, retained);
        return new Opened(retained, List.of());
    }

    /** Restore a back entry and refresh it once, without reinitializing its retained state or parameters. */
    public Opened restore(String name, UiState retained, CompiledTemplate template, String file,
                          Supplier<LocalStore> storage, Function<UiState, HttpRequests> network) {
        states.put(name, retained);
        return activate(retained, template, "onResume", file, storage, network);
    }

    private static Opened failed(UiState state, String file, String hook, String reason) {
        String kept = "onInit".equals(hook) ? "，初始化未完成，页面保留默认值" : "，恢复刷新未完成，页面保留原状态";
        return new Opened(state, List.of(file + " " + hook + "(): " + reason + kept));
    }

    /** Deliver into the originating retained page, with the same state/store/request commit rules as a click. */
    public static List<String> respond(ClientScript script, HttpRequests.Request request, HttpRequests.Result response,
                                       UiState state, String file, Supplier<LocalStore> storage, HttpRequests requests) {
        try (HttpRequests batch = requests; LocalStore.Transaction transaction = new LocalStore.Transaction(storage)) {
            Map<String, Object> next = script.respond(request, response, state, transaction, batch);
            if (!transaction.commit()) return List.of(file + " HTTP 回调保存失败，页面和原文件已保留");
            for (var entry : next.entrySet()) state.set(entry.getKey(), entry.getValue());
            if (batch != null) batch.commit();
            return List.of();
        } catch (ClientScript.Failure failure) {
            return List.of(file + " HTTP 回调: " + failure.getMessage() + "，本次回调未完成");
        }
    }

    /** Called when the App closes; reopening creates fresh states and runs their hooks again. */
    public void clear() { states.clear(); }
}
