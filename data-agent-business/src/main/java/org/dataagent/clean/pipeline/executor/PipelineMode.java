package org.dataagent.clean.pipeline.executor;

/** 执行模式，配合 {@link PipelineExecutorRegistry} 按模式分发。 */
public enum PipelineMode {

    /** 完整链路：画像 → 规划 → 执行 → 校验 */
    FULL,

    /** 澄清中断后续跑：从 checkpoint 恢复，不重跑画像与规划 */
    CLARIFY_RESUME,
}
