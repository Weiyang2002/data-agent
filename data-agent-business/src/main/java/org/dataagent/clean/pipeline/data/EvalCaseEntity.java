package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评测用例目录快照。
 *
 * <p>用例的权威定义在 Python 的 {@code eval_cases.py}（用例引用缺陷码，缺陷码的权威
 * 在 {@code defects.py}）。用例目录会随收敛演进，每轮开始前同步一份快照到本表，使
 * 历史轮次的评分判据本身可回溯——指标变化不会与用例改动混淆。见
 * {@code doc/设计决策记录.md}。
 */
@Data
@TableName("data_agent_eval_case")
public class EvalCaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 如 L4-01 */
    private String caseId;

    /** L1_RULE / L2_STRUCTURE / L3_JUDGMENT / L4_DISCOVERY */
    private String level;

    /** 医生的自然语言需求原文，刻意保留口语化 */
    private String requirement;

    /** 考察点。M4 归因时按它聚类失败 case */
    private String focus;

    private String defectsJson;

    /** 是否应触发澄清。0 的用例检验的是「会不会过度澄清」，和 1 的用例同等重要 */
    private Integer expectClarify;

    /** L4：用户没问但系统应主动报告的缺陷码 */
    private String expectProactiveReport;

    private LocalDateTime syncTime;
}
