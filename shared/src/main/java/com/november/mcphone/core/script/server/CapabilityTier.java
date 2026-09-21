package com.november.mcphone.core.script.server;

/**
 * 能力的档位（施工方案 §18.8）。<b>档位由宿主查表得出，App 在 manifest 里自称无效。</b>
 *
 * <ul>
 *   <li>{@link #PLAIN} —— 只作用于发起者自己、不凭空创造价值：免审批，但服主仍可用
 *       {@code [capabilities] disabled} 关掉。</li>
 *   <li>{@link #GRANTED} —— 影响他人 / 读他人数据 / 凭空创造销毁：OP 逐条批准。</li>
 *   <li>{@link #RESTRICTED} —— 命令模板、世界写入、广播、读附近实体：批准 + 默认关闭，
 *       <b>首版一档全不开放</b>。</li>
 * </ul>
 */
public enum CapabilityTier {
    PLAIN,
    GRANTED,
    RESTRICTED
}
