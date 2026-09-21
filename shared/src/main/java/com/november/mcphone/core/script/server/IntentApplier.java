package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptErrorCode;

import java.util.List;
import java.util.UUID;

/**
 * 意图落地端（S18）：在<b>主线程</b>把 worker 产出的 {@link ActionIntent} 变成真实效果。
 *
 * <p>契约：
 * <ul>
 *   <li>落地前由 {@code ScriptPipeline.land} 重查授权与能力，本接口只管"执行"；</li>
 *   <li><b>要么整批执行、要么一个都不动</b>：做不到就先返回失败码，别半途而废；</li>
 *   <li>真的出现"执行到一半失败"，返回 {@link ScriptErrorCode#UNKNOWN}（结果不明），
 *       <b>不许谎报成功</b>（§20.9/§20.4）。</li>
 * </ul>
 *
 * <p>接口只收 UUID 不收 {@code ServerPlayer}：管线这一层不许持有实体引用；取在线玩家由
 * 生产落地端自己做。
 */
public interface IntentApplier {

    /** 落地结果：成功用 {@link #ok()}，失败带码与本地化键。 */
    record Landed(ScriptErrorCode code, String messageKey) {
        public static Landed ok() {
            return new Landed(ScriptErrorCode.OK, "");
        }

        public boolean succeeded() {
            return code == ScriptErrorCode.OK;
        }
    }

    /** 生产端没接上时用它：一律"本服暂时做不了"，不静默吞掉意图。 */
    IntentApplier UNWIRED = (player, intents) ->
            new Landed(ScriptErrorCode.UNAVAILABLE, "mcphone.script.intent_unavailable");

    /** 主线程调用。 */
    Landed apply(UUID player, List<ActionIntent> intents);
}
