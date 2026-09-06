package org.dataagent.clean.pipeline.eval;

/**
 * 单用例的失败归因分类，失败聚类的维度。枚举按处置优先级排列，一个用例可能同时
 * 命中多条，取第一个命中的，保证同一失败稳定落进同一簇。
 */
public enum FailureCategory {

    /** 链路本身抛异常，其余判据全部无效 */
    PIPELINE_ERROR("链路异常", "任务执行过程中抛出异常，其余判据不成立"),

    /** 规划产出为空，且不是因为等澄清 */
    UNSUPPORTED_ACTION("需求超出动作词表", "规划没有产出任何步骤：需求（如分组聚合、窗口切分）落不进现有 PlanAction 词表"),

    /** 代码被沙箱静态检查拦下 */
    SANDBOX_BLOCKED("沙箱拦截", "生成的代码触发静态检查违规，从未进入执行"),

    /** 自修复用尽仍失败 */
    CODEGEN_FAILED("代码生成失败", "自修复次数用尽后步骤仍失败"),

    /** 该问却没问 */
    CLARIFY_MISSING("该澄清未澄清", "golden 要求澄清，系统直接做了决定"),

    /** 不该问却问了，与 CLARIFY_MISSING 同等计罚 */
    CLARIFY_EXCESS("过度澄清", "golden 判定无歧义，系统却把它抛回给医生"),

    /** 跑通了，但 golden 判据不过 */
    RESULT_MISMATCH("结果不符", "执行成功但目标缺陷在处理后仍被检出"),

    /** L4：用户没问、系统也没报 */
    DISCOVERY_MISS("主动发现缺失", "golden 中 userAsked=false 的缺陷未被报告"),

    /** 澄清类用例：问对了，但「答完之后做没做对」暂时测不了 */
    CLARIFY_ONLY("仅计澄清", "该用例应触发澄清且已正确触发；澄清答复回传接口未实现，结果正确率不计入"),

    OK("通过", "全部判据通过"),
    ;

    private final String label;
    private final String meaning;

    FailureCategory(String label, String meaning) {
        this.label = label;
        this.meaning = meaning;
    }

    public String getLabel() {
        return label;
    }

    public String getMeaning() {
        return meaning;
    }

    /** 是否计入 case_passed */
    public boolean isPass() {
        return this == OK || this == CLARIFY_ONLY;
    }
}
