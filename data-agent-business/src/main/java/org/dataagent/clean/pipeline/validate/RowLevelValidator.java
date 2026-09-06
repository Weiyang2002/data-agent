package org.dataagent.clean.pipeline.validate;

import org.dataagent.clean.pipeline.knowledge.KnowledgeRuleService;
import org.dataagent.clean.pipeline.knowledge.ValidityRange;
import org.dataagent.clean.pipeline.model.validate.FindingLevel;
import org.dataagent.clean.pipeline.model.validate.ValidationFinding;
import org.dataagent.clean.pipeline.model.validate.ValidationReport;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.observability.TraceStage;
import org.dataagent.clean.toolsclient.client.DataToolsClient;
import org.dataagent.clean.toolsclient.model.ValidateRowRequest;
import org.dataagent.clean.toolsclient.model.ValidateRowResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 第 1 层：行级校验。阈值全程不进 Prompt：
 * <pre>
 *   Java 查知识库 → 拿到 {min, max} + ruleId + 来源文档
 *        → 作为参数 POST 给 Python，Python 只做浮点比较
 *        → 结果带着 ruleId 回来，落进 validation_finding.source_rule_id
 * </pre>
 * 每条越界发现都能回溯到 ruleId 指向的院内规范。知识库查不到时跳过该列并
 * {@code markSkipped}，绝不退回到默认区间。
 */
@Component
public class RowLevelValidator {

    private static final Logger log = LoggerFactory.getLogger(RowLevelValidator.class);

    private final KnowledgeRuleService knowledgeRuleService;
    private final DataToolsClient dataToolsClient;

    public RowLevelValidator(KnowledgeRuleService knowledgeRuleService,
                             DataToolsClient dataToolsClient) {
        this.knowledgeRuleService = knowledgeRuleService;
        this.dataToolsClient = dataToolsClient;
    }

    /**
     * @param columns 待校验列。取知识库有 VALIDITY 规则的那些，其余跳过
     */
    @TraceStage(TraceStageCode.VALIDATE_ROW)
    public void validate(String traceId, String datasetPath,
                         List<String> columns, ValidationReport report) {
        List<ValidityRange> ranges = knowledgeRuleService.findValidities(columns);

        if (ranges.isEmpty()) {
            // 不发起零规则校验，「查了没问题」和「压根没查」必须能区分
            String reason = "知识库中没有这些列的 VALIDITY 规则（NO_EVIDENCE），"
                + "已跳过行级校验，未使用任何默认阈值。列：" + columns;
            log.info("行级校验跳过：{}", reason);
            report.markSkipped(FindingLevel.ROW, reason);
            return;
        }

        List<String> uncovered = new ArrayList<>(columns);
        ranges.forEach(range -> uncovered.remove(range.column()));
        if (!uncovered.isEmpty()) {
            // 部分覆盖也要说清楚哪些列没查
            log.info("行级校验部分覆盖：以下列在知识库中无 VALIDITY 规则，未校验 {}", uncovered);
        }

        ValidateRowRequest request = new ValidateRowRequest();
        request.setDatasetPath(datasetPath);
        request.setRules(ranges.stream().map(this::toRule).toList());

        ValidateRowResponse response = dataToolsClient.validateRow(traceId, request);
        report.markExecuted(FindingLevel.ROW);

        for (ValidateRowResponse.RowFinding item : response.getFindings()) {
            ValidationFinding finding = ValidationFinding.of(
                FindingLevel.ROW, "OUT_OF_RANGE", item.getColumn(),
                item.getEvidence(), item.getViolationCount());
            finding.setMetric(item.getViolationRatio());
            finding.setSeverity("ERROR");
            // 引用标注：把依据一路带到医生面前
            finding.setSourceRuleId(parseRuleId(item.getRuleId()));
            finding.setSourceDoc(item.getSourceDoc());
            finding.setSourceLocator(item.getSourceLocator());
            // 样例值只入库和进报告，不进任何 Prompt
            finding.setSamples(item.getSampleValues());
            report.add(finding);
        }
        log.info("行级校验完成：校验 {} 条规则，发现 {} 项",
            response.getCheckedRules(), response.getFindings().size());
    }

    private ValidateRowRequest.ValidityRule toRule(ValidityRange range) {
        ValidateRowRequest.ValidityRule rule = new ValidateRowRequest.ValidityRule();
        rule.setRuleId(String.valueOf(range.ruleId()));
        rule.setColumn(range.column());
        rule.setMin(range.min());
        rule.setMax(range.max());
        rule.setAllowedValues(range.allowedValues());
        rule.setUnit(range.unit());
        rule.setSourceDoc(range.sourceDoc());
        rule.setSourceLocator(range.sourceLocator());
        return rule;
    }

    private Long parseRuleId(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }
}
