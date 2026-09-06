package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 沙箱执行记录。每一次尝试落一行，包括失败的。
 *
 * <p>保留全部轮次而非只留最后一轮：失败归因看的是自修复过程（首版错误、每轮是否
 * 收敛、错误是否重复），只留最终结果无法区分。
 *
 * <p>{@code retryNo > 0 且 success = 1} 即一次成功的自修复，自修复成功率由这两个
 * 字段直接统计。
 */
@Data
@TableName("data_agent_execution")
public class ExecutionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskCode;

    private String planCode;

    private Integer stepNo;

    /** 0 首次生成，>0 自修复 */
    private Integer retryNo;

    private String code;

    private String inputPath;

    private String outputPath;

    private Integer success;

    /** 非空表示被静态检查拦截，代码根本没有被执行 */
    private String blockedReason;

    private String blockedDetail;

    private Integer exitCode;

    private String stdout;

    private String stderr;

    private Integer timedOut;

    private Long outputRowCount;

    private Long costMillis;

    private LocalDateTime createTime;
}
