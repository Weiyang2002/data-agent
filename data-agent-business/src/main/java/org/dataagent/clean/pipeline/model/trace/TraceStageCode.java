package org.dataagent.clean.pipeline.model.trace;

/**
 * 阶段码。{@code order} 刻意留空位，插入新阶段时不必重排已有值（重排会让历史 trace
 * 的 stage_order 与新代码对不上）。阶段父子关系不写进枚举，由运行时调用栈深度
 * （{@code depth}）决定。
 */
public enum TraceStageCode {

    LOAD("数据载入", 10),
    PROFILE("数据画像", 20),
    KNOWLEDGE("知识库检索", 30),
    PLAN("方案规划", 40),
    CLARIFY("澄清交互", 50),
    CODEGEN("代码生成", 60),
    SANDBOX("沙箱执行", 70),
    REPAIR("失败自修复", 75),
    /** 三层校验的父阶段。 */
    VALIDATE("三层校验", 79),
    VALIDATE_ROW("行级校验", 80),
    VALIDATE_DIST("分布级校验", 82),
    VALIDATE_SENSE("常识级校验", 84),
    FINALIZE("收尾归档", 90),
    /**
     * 兜底桶：在任务里、但不在任何被埋点的阶段内发生的模型调用。不该有值，出现即
     * 说明有一处模型调用没被 {@code @TraceStage} 覆盖到。
     */
    UNATTRIBUTED("未归属阶段", 999),
    ;

    private final String label;
    private final int order;

    TraceStageCode(String label, int order) {
        this.label = label;
        this.order = order;
    }

    public String getLabel() {
        return label;
    }

    public int getOrder() {
        return order;
    }
}
