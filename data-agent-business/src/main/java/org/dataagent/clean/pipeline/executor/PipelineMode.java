package org.dataagent.clean.pipeline.executor;

/** 执行模式，配合 {@link PipelineExecutorRegistry} 按模式分发。 */
public enum PipelineMode {

    /** 完整链路：画像 → 规划 → 执行 → 校验 */
    FULL,

    /** 澄清中断后续跑，尚未实现，新增一个 Executor 实现即可，编排层不改。 */
    CLARIFY_RESUME,
}
