package org.dataagent.clean.pipeline.validate;

import org.dataagent.clean.pipeline.model.validate.FindingLevel;
import org.dataagent.clean.pipeline.model.validate.ValidationFinding;
import org.dataagent.clean.pipeline.model.validate.ValidationReport;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.observability.TraceStage;
import org.dataagent.clean.toolsclient.client.DataToolsClient;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.dataagent.clean.toolsclient.model.ValidateDistributionRequest;
import org.dataagent.clean.toolsclient.model.ValidateDistributionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 第 2 层：分布级校验，回答「单行都正常，整列统计却不对」这类问题（如时间戳全零）。
 * 阈值是统计工程判断而非临床规范，留在 Python 侧作为默认常量，因此这一层的
 * {@code sourceRuleId} 恒为 null（正确，非遗漏）。
 */
@Component
public class DistributionValidator {

    private static final Logger log = LoggerFactory.getLogger(DistributionValidator.class);

    private final DataToolsClient dataToolsClient;

    public DistributionValidator(DataToolsClient dataToolsClient) {
        this.dataToolsClient = dataToolsClient;
    }

    /**
     * @return 处理后数据的分布级检测结果，同时作为「修好了没有」的对照
     */
    @TraceStage(TraceStageCode.VALIDATE_DIST)
    public ValidateDistributionResponse validate(String traceId, String datasetPath,
                                                 ValidationReport report) {
        ValidateDistributionResponse response = dataToolsClient.validateDistribution(
            traceId, ValidateDistributionRequest.of(datasetPath));
        report.markExecuted(FindingLevel.DISTRIBUTION);

        for (ProfileResponse.AnomalyPattern pattern : response.getFindings()) {
            FindingLevel level = FindingLevel.parse(pattern.getLevel());
            ValidationFinding finding = ValidationFinding.of(
                level, pattern.getCode(), pattern.getColumn(),
                pattern.getEvidence(),
                pattern.getAffectedRows() == null ? 0L : pattern.getAffectedRows());
            finding.setMetric(pattern.getMetric());
            // 分布级/结构级发现没有规范依据是正常的，见类注释
            finding.setSeverity(level == FindingLevel.CLARIFY ? "INFO" : "WARN");
            report.add(finding);
        }
        // 结构级（连续空白段）由同一次调用一并跑完，一起标记为已执行
        report.markExecuted(FindingLevel.STRUCTURE);
        log.info("分布级校验完成：{} 项发现", response.getFindings().size());
        return response;
    }
}
