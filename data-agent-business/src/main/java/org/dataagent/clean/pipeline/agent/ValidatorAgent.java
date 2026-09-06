package org.dataagent.clean.pipeline.agent;

import org.dataagent.clean.pipeline.config.CleanAgentProperties;
import org.dataagent.clean.pipeline.model.profile.ProfileDiff;
import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.pipeline.model.validate.FindingLevel;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.model.validate.ValidationReport;
import org.dataagent.clean.pipeline.observability.TraceStage;
import org.dataagent.clean.pipeline.validate.CommonSenseValidator;
import org.dataagent.clean.pipeline.validate.DistributionValidator;
import org.dataagent.clean.pipeline.validate.ProfileDiffBuilder;
import org.dataagent.clean.pipeline.validate.RowLevelValidator;
import org.dataagent.clean.toolsclient.client.DataToolsClient;
import org.dataagent.clean.toolsclient.model.ProfileRequest;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 4：三层校验的编排。
 *
 * <p>四类校验及其实现位置：
 * <ul>
 *   <li>行级：Python 确定性，阈值来自知识库 VALIDITY，单行浮点比较</li>
 *   <li>分布级 / 结构级：Python 确定性，阈值为 Python 侧统计常量</li>
 *   <li>常识级：Java + LLM，输入是处理前后画像 diff，无原始数据</li>
 * </ul>
 *
 * <p>分层检出率按层报告，逐层下降是预期结果。没有任何步骤成功产出数据时，
 * 三层全部标记为「未执行」并写明原因，不返回空报告（空报告与「校验通过」
 * 结构上无法区分）。
 */
@Component
public class ValidatorAgent implements DataAgent<ValidationInput, ValidationReport> {

    private static final Logger log = LoggerFactory.getLogger(ValidatorAgent.class);

    private final RowLevelValidator rowLevelValidator;
    private final DistributionValidator distributionValidator;
    private final CommonSenseValidator commonSenseValidator;
    private final ProfileDiffBuilder profileDiffBuilder;
    private final DataToolsClient dataToolsClient;
    private final CleanAgentProperties properties;

    public ValidatorAgent(RowLevelValidator rowLevelValidator,
                          DistributionValidator distributionValidator,
                          CommonSenseValidator commonSenseValidator,
                          ProfileDiffBuilder profileDiffBuilder,
                          DataToolsClient dataToolsClient,
                          CleanAgentProperties properties) {
        this.rowLevelValidator = rowLevelValidator;
        this.distributionValidator = distributionValidator;
        this.commonSenseValidator = commonSenseValidator;
        this.profileDiffBuilder = profileDiffBuilder;
        this.dataToolsClient = dataToolsClient;
        this.properties = properties;
    }

    @Override
    public AgentCode code() {
        return AgentCode.VALIDATOR;
    }

    @Override
    @TraceStage(TraceStageCode.VALIDATE)
    public ValidationReport run(AgentContext context, ValidationInput input) {
        ValidationReport report = new ValidationReport();

        if (input.outputPath() == null || input.outputPath().isBlank()) {
            String reason = "没有任何步骤成功产出数据，三层校验均未执行";
            log.warn("{}", reason);
            for (FindingLevel level : FindingLevel.values()) {
                report.markSkipped(level, reason);
            }
            return report;
        }

        // 行级：阈值从知识库来，参数传给 Python
        rowLevelValidator.validate(context.getTraceId(), input.outputPath(),
            columnsToCheck(input), report);

        // 分布级 + 结构级：Python 确定性检测器
        distributionValidator.validate(context.getTraceId(), input.outputPath(), report);

        // 常识级：输入是处理前后的画像 diff，没有原始数据
        ProfileResponse afterProfile = dataToolsClient.profile(
            context.getTraceId(),
            ProfileRequest.of(input.outputPath(), properties.getTextValueTopN()));
        ProfileDiff diff = profileDiffBuilder.build(input.beforeProfile(), afterProfile);
        commonSenseValidator.validate(diff, report);

        log.info("三层校验完成 taskCode={} 共 {} 项发现（行级 {} / 分布级 {} / 结构级 {} / 常识级 {}）",
            context.getTaskCode(), report.getFindings().size(),
            report.byLevel(FindingLevel.ROW).size(),
            report.byLevel(FindingLevel.DISTRIBUTION).size(),
            report.byLevel(FindingLevel.STRUCTURE).size(),
            report.byLevel(FindingLevel.COMMON_SENSE).size());
        return report;
    }

    /**
     * 该做行级校验的列：方案涉及的列 + 数据集里所有数值列的并集。后者覆盖医生
     * 没提但系统应主动检查的列。
     */
    private List<String> columnsToCheck(ValidationInput input) {
        Set<String> columns = new LinkedHashSet<>();
        input.plan().getSteps().stream()
            .map(PlanStep::getTargetColumns)
            .forEach(columns::addAll);
        input.beforeProfile().getColumns().stream()
            .filter(column -> column.getNumeric() != null)
            .forEach(column -> columns.add(column.getName()));
        return List.copyOf(columns);
    }
}
