package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单用例评测结果，失败归因的原始素材。不只存「过 / 不过」：
 * {@link #detectedJson} / {@link #missedJson} / {@link #falseAlarmJson} 才是归因需要
 * 的东西。{@link #taskCode} 关联 {@code data_agent_task}，可回放完整链路。
 */
@Data
@TableName("data_agent_eval_result")
public class EvalResultEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runCode;

    private String caseId;

    /** 关联 data_agent_task，可回放完整链路 */
    private String taskCode;

    private Integer execSuccess;

    private Integer resultCorrect;

    private Integer clarifyExpected;

    private Integer clarifyActual;

    /** 系统实际报出来的缺陷码 */
    private String detectedJson;

    /** golden 里有、系统没报的 */
    private String missedJson;

    /** 系统报了、golden 里没有的 */
    private String falseAlarmJson;

    private Integer repairAttempts;

    /** Token 采集字段 */
    private Integer tokenUsed;

    private Long costMillis;

    /** 失败归因分类，按它聚类。见 {@code eval/FailureCategory} */
    private String failureCategory;

    private String failureDetail;

    private LocalDateTime createTime;
}
