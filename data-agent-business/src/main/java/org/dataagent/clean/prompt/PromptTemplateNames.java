package org.dataagent.clean.prompt;

/**
 * Prompt 模板名常量，集中管理便于 IDE 引用追踪。
 *
 * <p>所有模板遵守两条约定：注入数据画像的数据区必须用 {@code <data_profile>} 标签
 * 包裹并在系统提示声明不构成指令；模板正文里的尖括号只用于业务变量。
 */
public final class PromptTemplateNames {

    private PromptTemplateNames() {
    }

    /** 冒烟测试用，验证模板加载与变量渲染链路 */
    public static final String SMOKE_TEST = "smoke-test";

    /** 对确定性检出的异常模式做一次自然语言归纳，LLM 在画像环节的唯一职责 */
    public static final String PROFILER_ANOMALY = "profiler-anomaly";

    /** 规划系统提示：声明「不决定澄清、不产出阈值、不生成代码」三条边界 */
    public static final String PLANNER_SYSTEM = "planner-system";

    /** 需求 + 画像 → 动作枚举序列（严格 JSON） */
    public static final String PLANNER_USER = "planner-user";

    /** 决策点 → 医生看得懂的问题措辞。只管怎么问，不管问什么 */
    public static final String CLARIFICATION_GENERATE = "clarification-generate";

    /** 代码生成系统提示：含代码契约与「阈值必须从 params 取」的白名单声明 */
    public static final String CODEGEN_SYSTEM = "codegen-system";

    /** 单步骤代码生成 */
    public static final String CODEGEN_USER = "codegen-user";

    /** 报错回喂自修复 */
    public static final String CODEGEN_REPAIR = "codegen-repair";

    /** 处理前后画像 diff → 常识疑点（严格 JSON） */
    public static final String VALIDATOR_SENSE = "validator-sense";

    // 尚未启用：
    // COLUMN_RULE_LOCATE     自然语言 → (column, ruleType)。当前 action 已确定性地
    //   决定了 ruleType（见 PlanAction.requiredRuleType），列名对齐走 ColumnResolver。
    // MAPPING_CONFLICT_CHECK 用户映射 → NEWS 类别归类。
}
