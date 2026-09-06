package org.dataagent.clean.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.pipeline.data.ClarificationEntity;
import org.dataagent.clean.pipeline.data.ColumnProfileEntity;
import org.dataagent.clean.pipeline.data.DatasetEntity;
import org.dataagent.clean.pipeline.data.ExecutionEntity;
import org.dataagent.clean.pipeline.data.PlanEntity;
import org.dataagent.clean.pipeline.data.PlanStepEntity;
import org.dataagent.clean.pipeline.data.TaskEntity;
import org.dataagent.clean.pipeline.data.ValidationFindingEntity;
import org.dataagent.clean.pipeline.mapper.ClarificationMapper;
import org.dataagent.clean.pipeline.mapper.ColumnProfileMapper;
import org.dataagent.clean.pipeline.mapper.DatasetMapper;
import org.dataagent.clean.pipeline.mapper.ExecutionMapper;
import org.dataagent.clean.pipeline.mapper.PlanMapper;
import org.dataagent.clean.pipeline.mapper.PlanStepMapper;
import org.dataagent.clean.pipeline.mapper.TaskMapper;
import org.dataagent.clean.pipeline.mapper.ValidationFindingMapper;
import org.dataagent.clean.pipeline.model.plan.ClarificationItem;
import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.pipeline.model.plan.StepExecution;
import org.dataagent.clean.pipeline.model.validate.ValidationFinding;
import org.dataagent.clean.pipeline.model.validate.ValidationReport;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 链路各环节的落库，集中在一个类里以统一字段口径。落库失败只打 WARN，不中断链路。
 */
@Component
public class TaskPersistence {

    private static final Logger log = LoggerFactory.getLogger(TaskPersistence.class);

    /** stdout/stderr 入库上限，防止死循环的 print 把表撑爆 */
    private static final int MAX_STREAM_CHARS = 20000;

    private final TaskMapper taskMapper;
    private final DatasetMapper datasetMapper;
    private final ColumnProfileMapper columnProfileMapper;
    private final PlanMapper planMapper;
    private final PlanStepMapper planStepMapper;
    private final ClarificationMapper clarificationMapper;
    private final ExecutionMapper executionMapper;
    private final ValidationFindingMapper validationFindingMapper;
    private final ObjectMapper objectMapper;

    public TaskPersistence(TaskMapper taskMapper,
                           DatasetMapper datasetMapper,
                           ColumnProfileMapper columnProfileMapper,
                           PlanMapper planMapper,
                           PlanStepMapper planStepMapper,
                           ClarificationMapper clarificationMapper,
                           ExecutionMapper executionMapper,
                           ValidationFindingMapper validationFindingMapper,
                           ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.datasetMapper = datasetMapper;
        this.columnProfileMapper = columnProfileMapper;
        this.planMapper = planMapper;
        this.planStepMapper = planStepMapper;
        this.clarificationMapper = clarificationMapper;
        this.executionMapper = executionMapper;
        this.validationFindingMapper = validationFindingMapper;
        this.objectMapper = objectMapper;
    }

    public void createTask(TaskInfo taskInfo) {
        guard("任务创建", () -> {
            TaskEntity entity = new TaskEntity();
            entity.setTaskCode(taskInfo.getTaskCode());
            entity.setTraceId(taskInfo.getTraceId());
            entity.setRequirement(taskInfo.getRequirement());
            entity.setDatasetPath(taskInfo.getDatasetPath());
            entity.setStatus("CREATED");
            entity.setThreadId(taskInfo.getThreadId());
            taskMapper.insert(entity);
        });
    }

    public void updateStatus(TaskInfo taskInfo, String status, String failReason) {
        guard("任务状态更新", () -> {
            TaskEntity entity = taskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TaskEntity>()
                    .eq("task_code", taskInfo.getTaskCode()));
            if (entity == null) {
                return;
            }
            entity.setStatus(status);
            entity.setFailReason(truncate(failReason, 1000));
            taskMapper.updateById(entity);
        });
    }

    public void saveDataset(TaskInfo taskInfo, ProfileResponse profile) {
        guard("数据集登记", () -> {
            DatasetEntity entity = new DatasetEntity();
            entity.setDatasetCode(taskInfo.getDatasetCode());
            entity.setDatasetPath(taskInfo.getDatasetPath());
            entity.setRowCount(profile.getRowCount());
            entity.setColumnCount(profile.getColumnCount());
            entity.setSource("SYNTHETIC");
            entity.setProfileMillis(profile.getProfileMillis());
            datasetMapper.insert(entity);
        });
    }

    /** 落列画像。{@code phase} 区分 BEFORE/AFTER，是常识级校验的对照基准。 */
    public void saveColumnProfiles(TaskInfo taskInfo, ProfileResponse profile, String phase) {
        guard("列画像落库", () -> {
            for (ProfileResponse.ColumnProfile column : profile.getColumns()) {
                ColumnProfileEntity entity = new ColumnProfileEntity();
                entity.setTaskCode(taskInfo.getTaskCode());
                entity.setDatasetCode(taskInfo.getDatasetCode());
                entity.setPhase(phase);
                entity.setColumnName(column.getName());
                entity.setDtype(column.getDtype());
                entity.setMissingRate(column.getMissingRate());
                entity.setDistinctCount(column.getDistinctCount());
                entity.setNumericJson(toJson(column.getNumeric()));
                // 高基数列的 textValues 本来就是 null
                entity.setTextValuesJson(toJson(column.getTextValues()));
                columnProfileMapper.insert(entity);
            }
        });
    }

    public void savePlan(TaskInfo taskInfo, CleaningPlan plan) {
        guard("方案落库", () -> {
            PlanEntity entity = new PlanEntity();
            entity.setPlanCode(plan.getPlanCode());
            entity.setTaskCode(taskInfo.getTaskCode());
            entity.setSummary(truncate(plan.getSummary(), 1000));
            entity.setStatus(plan.needsClarification() ? "CLARIFYING" : "CONFIRMED");
            entity.setStepCount(plan.getSteps().size());
            entity.setClarifyCount(plan.pendingQuestions().size());
            planMapper.insert(entity);

            for (PlanStep step : plan.getSteps()) {
                PlanStepEntity stepEntity = new PlanStepEntity();
                stepEntity.setPlanCode(plan.getPlanCode());
                stepEntity.setStepNo(step.getStepNo());
                stepEntity.setAction(step.getAction().name());
                stepEntity.setTargetColumns(toJson(step.getTargetColumns()));
                stepEntity.setDescription(truncate(step.getDescription(), 1000));
                stepEntity.setRuleIds(toJson(step.getRuleIds()));
                planStepMapper.insert(stepEntity);
            }

            for (ClarificationItem item : plan.getClarifications()) {
                ClarificationEntity clarifyEntity = new ClarificationEntity();
                clarifyEntity.setClarifyCode(item.getClarifyCode());
                clarifyEntity.setTaskCode(taskInfo.getTaskCode());
                clarifyEntity.setPlanCode(plan.getPlanCode());
                clarifyEntity.setLevel(item.getLevel().name());
                clarifyEntity.setTopic(item.getTopic());
                clarifyEntity.setQuestion(truncate(item.getQuestion(), 1000));
                clarifyEntity.setOptionsJson(toJson(item.getOptions()));
                clarifyEntity.setColumnName(item.getColumnName());
                clarifyEntity.setCoverageRatio(item.getCoverageRatio());
                clarifyEntity.setEvidence(truncate(item.getEvidence(), 1000));
                clarifyEntity.setSourceRuleIds(toJson(item.getSourceRuleIds()));
                clarificationMapper.insert(clarifyEntity);
            }
        });
    }

    /** 落执行记录，每一轮都落（包括失败的），供失败归因使用。 */
    public void saveExecutions(TaskInfo taskInfo, String planCode,
                               List<StepExecution> executions, String firstInputPath) {
        guard("执行记录落库", () -> {
            String inputPath = firstInputPath;
            for (StepExecution execution : executions) {
                for (StepExecution.Attempt attempt : execution.getAttempts()) {
                    ExecutionEntity entity = new ExecutionEntity();
                    entity.setTaskCode(taskInfo.getTaskCode());
                    entity.setPlanCode(planCode);
                    entity.setStepNo(execution.getStepNo());
                    entity.setRetryNo(attempt.getRetryNo());
                    entity.setCode(attempt.getCode());
                    entity.setInputPath(inputPath);
                    entity.setOutputPath(attempt.getResponse().getOutputPath());
                    entity.setSuccess(attempt.getResponse().isSuccessful() ? 1 : 0);
                    entity.setBlockedReason(truncate(attempt.getResponse().getBlockedReason(), 500));
                    entity.setBlockedDetail(toJson(attempt.getResponse().getBlockedDetail()));
                    entity.setExitCode(attempt.getResponse().getExitCode());
                    entity.setStdout(truncate(attempt.getResponse().getStdout(), MAX_STREAM_CHARS));
                    entity.setStderr(truncate(attempt.getResponse().getStderr(), MAX_STREAM_CHARS));
                    entity.setTimedOut(Boolean.TRUE.equals(attempt.getResponse().getTimedOut()) ? 1 : 0);
                    entity.setOutputRowCount(attempt.getResponse().getOutputRowCount());
                    entity.setCostMillis(attempt.getResponse().getDurationMs());
                    executionMapper.insert(entity);
                }
                if (execution.isSuccess()) {
                    inputPath = execution.getOutputPath();
                }
            }
        });
    }

    public void saveFindings(TaskInfo taskInfo, ValidationReport report, String phase) {
        guard("校验发现落库", () -> {
            for (ValidationFinding finding : report.getFindings()) {
                ValidationFindingEntity entity = new ValidationFindingEntity();
                entity.setTaskCode(taskInfo.getTaskCode());
                entity.setPhase(phase);
                entity.setLevel(finding.getLevel().name());
                entity.setCode(finding.getCode());
                entity.setColumnName(finding.getColumnName());
                entity.setSeverity(finding.getSeverity());
                entity.setEvidence(truncate(finding.getEvidence(), 1000));
                entity.setAffectedRows(finding.getAffectedRows());
                entity.setMetric(finding.getMetric());
                entity.setSourceRuleId(finding.getSourceRuleId() == null
                    ? null : String.valueOf(finding.getSourceRuleId()));
                entity.setSourceDoc(finding.getSourceDoc());
                entity.setSourceLocator(finding.getSourceLocator());
                entity.setSampleJson(toJson(finding.getSamples()));
                validationFindingMapper.insert(entity);
            }
        });
    }

    /** 把画像阶段检出的异常也落进 findings 表（phase=BEFORE），问题发现率要用 */
    public void saveProfileAnomalies(TaskInfo taskInfo, ProfileResponse profile) {
        guard("画像异常落库", () -> {
            for (ProfileResponse.AnomalyPattern pattern : profile.getAnomalyPatterns()) {
                ValidationFindingEntity entity = new ValidationFindingEntity();
                entity.setTaskCode(taskInfo.getTaskCode());
                entity.setPhase("BEFORE");
                entity.setLevel(pattern.getLevel());
                entity.setCode(pattern.getCode());
                entity.setColumnName(pattern.getColumn());
                entity.setSeverity("WARN");
                entity.setEvidence(truncate(pattern.getEvidence(), 1000));
                entity.setAffectedRows(pattern.getAffectedRows());
                entity.setMetric(pattern.getMetric());
                validationFindingMapper.insert(entity);
            }
        });
    }

    private void guard(String what, Runnable action) {
        try {
            action.run();
        }
        catch (Exception exception) {
            log.warn("{}失败（不中断链路，但相关指标会少这部分数据）: {}",
                what, exception.getMessage());
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            return null;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
