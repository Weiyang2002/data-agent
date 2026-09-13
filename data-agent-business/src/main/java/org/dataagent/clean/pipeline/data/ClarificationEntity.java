package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 澄清项。提问按 {@code coverageRatio} 降序排，医生中途停止也已覆盖绝大部分数据。 */
@Data
@TableName("data_agent_clarification")
public class ClarificationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String clarifyCode;

    private String taskCode;

    private String planCode;

    /** HIGH 自动决定 / MEDIUM 合并提问 / LOW 必须逐个追问 */
    private String level;

    /** 稳定可枚举，与 golden 的 expectClarifications 比对时用 */
    private String topic;

    private String question;

    private String optionsJson;

    /** 候选答案的机器码，与 optionsJson 逐位对应；措辞可被模型改写，机器码不可 */
    private String optionCodesJson;

    private String columnName;

    private Double coverageRatio;

    private String evidence;

    /** 歧义澄清时冲突的规则 ID */
    private String sourceRuleIds;

    private String answer;

    /** 答复归一后的机器码；为空表示答复无法映射为确定性动作 */
    private String answerAction;

    private LocalDateTime answeredAt;

    private LocalDateTime createTime;
}
