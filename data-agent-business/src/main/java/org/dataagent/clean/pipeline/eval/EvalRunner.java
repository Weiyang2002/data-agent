package org.dataagent.clean.pipeline.eval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.pipeline.config.CleanAgentProperties;
import org.dataagent.clean.pipeline.data.EvalCaseEntity;
import org.dataagent.clean.pipeline.data.EvalResultEntity;
import org.dataagent.clean.pipeline.data.EvalRunEntity;
import org.dataagent.clean.pipeline.dto.CleanTaskRequest;
import org.dataagent.clean.pipeline.dto.EvalRunRequest;
import org.dataagent.clean.pipeline.knowledge.KnowledgeRuleService;
import org.dataagent.clean.pipeline.mapper.EvalCaseMapper;
import org.dataagent.clean.pipeline.mapper.EvalResultMapper;
import org.dataagent.clean.pipeline.mapper.EvalRunMapper;
import org.dataagent.clean.pipeline.model.validate.ValidationReport;
import org.dataagent.clean.pipeline.service.CleanTaskService;
import org.dataagent.clean.pipeline.validate.RowLevelValidator;
import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;
import org.dataagent.clean.pipeline.vo.EvalRunReportVO;
import org.dataagent.clean.toolsclient.client.DataToolsClient;
import org.dataagent.clean.toolsclient.model.DatasetGenerateRequest;
import org.dataagent.clean.toolsclient.model.DatasetGenerateResponse;
import org.dataagent.clean.toolsclient.model.EvalCasesResponse;
import org.dataagent.clean.toolsclient.model.ProfileRequest;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 评测 Runner。一轮评测：
 * <pre>
 *   同步用例目录（Python 是权威，Java 存快照）
 *   for 每个用例:
 *      1. 按用例自带的缺陷配置 + 固定 seed 生成数据集与 golden
 *      2. 处理前探针：在原始数据上跑一遍行级校验
 *      3. 跑完整链路 /api/task/run
 *      4. 处理后探针：对输出重新画像，看目标缺陷还在不在
 *      5. 打分 → 落 data_agent_eval_result
 *   汇总 → 落 data_agent_eval_run
 * </pre>
 *
 * <p>两个探针都用生产组件（同一个 RowLevelValidator、同一个 /profile），补上链路
 * 本身校验时机的缺口。同步跑而非异步任务化，每个用例跑完立刻落库，中途中断已跑
 * 完部分不丢。
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private static final DateTimeFormatter RUN_TIME =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final DataToolsClient dataToolsClient;
    private final CleanTaskService cleanTaskService;
    private final RowLevelValidator rowLevelValidator;
    private final KnowledgeRuleService knowledgeRuleService;
    private final CaseScorer caseScorer;
    private final DefectCodeMatcher matcher;
    private final MetricsAggregator aggregator;
    private final CleanAgentProperties properties;
    private final EvalCaseMapper evalCaseMapper;
    private final EvalRunMapper evalRunMapper;
    private final EvalResultMapper evalResultMapper;
    private final ObjectMapper objectMapper;

    /** 本轮用的模型，换模型也是一种改动，需记录 */
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String modelName;

    public EvalRunner(DataToolsClient dataToolsClient,
                      CleanTaskService cleanTaskService,
                      RowLevelValidator rowLevelValidator,
                      KnowledgeRuleService knowledgeRuleService,
                      CaseScorer caseScorer,
                      DefectCodeMatcher matcher,
                      MetricsAggregator aggregator,
                      CleanAgentProperties properties,
                      EvalCaseMapper evalCaseMapper,
                      EvalRunMapper evalRunMapper,
                      EvalResultMapper evalResultMapper,
                      ObjectMapper objectMapper) {
        this.dataToolsClient = dataToolsClient;
        this.cleanTaskService = cleanTaskService;
        this.rowLevelValidator = rowLevelValidator;
        this.knowledgeRuleService = knowledgeRuleService;
        this.caseScorer = caseScorer;
        this.matcher = matcher;
        this.aggregator = aggregator;
        this.properties = properties;
        this.evalCaseMapper = evalCaseMapper;
        this.evalRunMapper = evalRunMapper;
        this.evalResultMapper = evalResultMapper;
        this.objectMapper = objectMapper;
    }

    public EvalRunReportVO run(EvalRunRequest request) {
        EvalCasesResponse catalog = dataToolsClient.listEvalCases();
        syncCatalog(catalog);

        List<EvalCasesResponse.EvalCase> cases = catalog.getCases().stream()
            .filter(item -> request.getCaseIds().isEmpty()
                || request.getCaseIds().contains(item.getCaseId()))
            .toList();
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(
                "没有匹配的用例。可用 caseId 见 GET /api/eval/cases");
        }

        String runCode = "R" + LocalDateTime.now().format(RUN_TIME);
        EvalRunEntity run = newRun(runCode, request);
        evalRunMapper.insert(run);

        EvalRunReportVO report = new EvalRunReportVO();
        report.setRunCode(runCode);
        report.setChangeNote(request.getChangeNote());
        report.setModelName(modelName);
        report.setDatasetSeed(request.getSeed());
        report.setDatasetRows(request.getRows());
        report.setDatasetPatients(request.getPatients());
        report.setStartTime(run.getStartTime());

        log.info("评测开始 runCode={} 用例={} 模型={} 改动={}",
            runCode, cases.size(), modelName, request.getChangeNote());

        long wallStart = System.nanoTime();
        List<CaseScore> scores = new ArrayList<>();
        // 量具自身的故障，随报告一起返回
        List<String> probeFailures = new ArrayList<>();
        int index = 0;
        for (EvalCasesResponse.EvalCase evalCase : cases) {
            index++;
            log.info("[{}/{}] {} {} 「{}」",
                index, cases.size(), evalCase.getCaseId(), evalCase.getLevel(),
                evalCase.getRequirement());
            CaseScore score = runOne(evalCase, request, probeFailures);
            scores.add(score);
            persistResult(runCode, score);
            log.info("[{}/{}] {} → {} 耗时 {}ms 漏检={} 误报={}",
                index, cases.size(), evalCase.getCaseId(),
                score.getFailureCategory().name(), score.getCostMillis(),
                score.getMissed(), score.getFalseAlarm());
        }

        aggregator.aggregate(runCode, scores, report, run);
        run.setEndTime(LocalDateTime.now());
        evalRunMapper.updateById(run);

        report.setEndTime(run.getEndTime());
        report.setWallClockMillis((System.nanoTime() - wallStart) / 1_000_000L);
        scores.forEach(score -> report.getCases().add(toRow(score)));
        fillCaveats(report, probeFailures);

        log.info("评测结束 runCode={} 通过 {}/{} 耗时 {}ms",
            runCode, report.getCasePassed(), report.getCaseTotal(),
            report.getWallClockMillis());
        return report;
    }

    // ── 单个用例 ──

    private CaseScore runOne(EvalCasesResponse.EvalCase evalCase,
                             EvalRunRequest request,
                             List<String> probeFailures) {
        DatasetGenerateResponse golden;
        try {
            golden = generate(evalCase, request);
        }
        catch (RuntimeException exception) {
            // 数据集生成失败：记成 PIPELINE_ERROR 而不是跳过（跳过会让分母变小）
            log.error("用例 {} 的数据集生成失败", evalCase.getCaseId(), exception);
            DatasetGenerateResponse empty = new DatasetGenerateResponse();
            empty.setInjected(List.of());
            return caseScorer.score(evalCase, empty, null, List.of(), Set.of(),
                "数据集生成失败：" + exception.getMessage(), 0L);
        }

        String probeTrace = "probe-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ProbeResult beforeRowProbe = probeRowLevel(probeTrace, golden.getDatasetPath());
        if (!beforeRowProbe.ok()) {
            // 探针失败会让越界类缺陷被记成漏检，计数并写进报告 caveats
            probeFailures.add(evalCase.getCaseId() + ": " + beforeRowProbe.failReason());
            log.warn("[{}] 处理前行级探针失败，越界类缺陷本轮会被记成漏检：{}",
                evalCase.getCaseId(), beforeRowProbe.failReason());
        }

        CleanTaskRequest taskRequest = new CleanTaskRequest();
        taskRequest.setRequirement(evalCase.getRequirement());
        taskRequest.setDatasetPath(golden.getDatasetPath());

        long started = System.nanoTime();
        CleanTaskResultVO result;
        String error = null;
        try {
            result = cleanTaskService.run(taskRequest);
        }
        catch (RuntimeException exception) {
            // 单个用例异常不带走整轮评测，链路异常本身是一个失败模式（PIPELINE_ERROR）
            log.error("用例 {} 的链路执行异常", evalCase.getCaseId(), exception);
            result = null;
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        long costMillis = (System.nanoTime() - started) / 1_000_000L;

        Set<String> afterResidual = result == null ? Set.of() : residualCodes(probeTrace, result);
        return caseScorer.score(evalCase, golden, result,
            beforeRowProbe.signals(), afterResidual, error, costMillis);
    }

    private DatasetGenerateResponse generate(EvalCasesResponse.EvalCase evalCase,
                                             EvalRunRequest request) {
        DatasetGenerateRequest generateRequest = new DatasetGenerateRequest();
        generateRequest.setRows(request.getRows());
        generateRequest.setPatients(request.getPatients());
        generateRequest.setSeed(request.getSeed());
        generateRequest.setDefects(evalCase.getDefects() == null
            ? List.of() : evalCase.getDefects());
        // 文件名只由 (caseId, seed) 决定，同 seed 重跑覆盖同一份文件
        generateRequest.setDatasetName(
            "eval_" + evalCase.getCaseId().replace('-', '_') + "_s" + request.getSeed());
        return dataToolsClient.generateDataset(generateRequest);
    }

    /**
     * 处理前的行级探针，用生产链路同一个 {@link RowLevelValidator} 和知识库规则。
     * 待查列先与数据集实际的列求交集：Python 侧对指向不存在列的规则会直接报错。
     */
    private ProbeResult probeRowLevel(String traceId, String datasetPath) {
        List<String> covered = knowledgeRuleService.validityColumns();
        if (covered.isEmpty()) {
            return ProbeResult.failed("知识库里没有任何 VALIDITY 规则 —— 种子数据可能没导入");
        }

        List<String> present;
        try {
            ProfileResponse profile = dataToolsClient.profile(traceId,
                ProfileRequest.of(datasetPath, properties.getTextValueTopN()));
            List<String> names = profile.getColumns().stream()
                .map(ProfileResponse.ColumnProfile::getName).toList();
            present = covered.stream().filter(names::contains).toList();
        }
        catch (RuntimeException exception) {
            return ProbeResult.failed("取数据集列名失败：" + exception.getMessage());
        }
        if (present.isEmpty()) {
            return ProbeResult.failed("知识库覆盖的 " + covered.size() + " 列在本数据集里一列都没有");
        }
        if (present.size() < covered.size()) {
            log.debug("知识库 VALIDITY 覆盖 {} 列，本数据集里存在 {} 列，未覆盖到的：{}",
                covered.size(), present.size(),
                covered.stream().filter(name -> !present.contains(name)).toList());
        }

        ValidationReport probe = new ValidationReport();
        try {
            rowLevelValidator.validate(traceId, datasetPath, present, probe);
        }
        catch (RuntimeException exception) {
            return ProbeResult.failed("行级校验调用失败：" + exception.getMessage());
        }
        return new ProbeResult(probe.getFindings().stream()
            .map(finding -> new DefectCodeMatcher.Signal(
                matcher.normalize(finding.getCode()), finding.getColumnName()))
            .filter(signal -> signal.code() != null)
            .toList(), null);
    }

    /**
     * 探针结果。失败必须是一个返回值而非只是一条日志，以便随报告 caveats 暴露给
     * 使用者。
     */
    private record ProbeResult(List<DefectCodeMatcher.Signal> signals, String failReason) {

        static ProbeResult failed(String reason) {
            return new ProbeResult(List.of(), reason);
        }

        boolean ok() {
            return failReason == null;
        }
    }

    /**
     * 处理后仍被检出的「码@列」键，结果正确率的判据。两个来源：链路三层校验发现，
     * 加对输出重新画像。带列，避免基线自带越界导致误判。
     */
    private Set<String> residualCodes(String traceId, CleanTaskResultVO result) {
        Set<String> keys = new LinkedHashSet<>();
        result.getFindings().stream()
            .filter(finding -> !DefectCodeMatcher.SENSE_CODE.equals(finding.getCode()))
            .forEach(finding -> addKey(keys, finding.getCode(), finding.getColumn()));

        if (result.getOutputPath() != null && !result.getOutputPath().isBlank()) {
            try {
                ProfileResponse after = dataToolsClient.profile(traceId,
                    ProfileRequest.of(result.getOutputPath(), properties.getTextValueTopN()));
                after.getAnomalyPatterns()
                    .forEach(pattern -> addKey(keys, pattern.getCode(), pattern.getColumn()));
            }
            catch (RuntimeException exception) {
                log.warn("处理后重新画像失败，结果正确率只能依据链路自身的校验发现: {}",
                    exception.getMessage());
            }
        }
        return keys;
    }

    private void addKey(Set<String> keys, String rawCode, String column) {
        String code = matcher.normalize(rawCode);
        if (code == null) {
            return;
        }
        keys.add(code + "@" + (column == null || column.isBlank()
            ? DefectCodeMatcher.ANY_COLUMN : column));
    }

    // ── 落库 ──

    /** 同步用例目录快照。先删后插而非 upsert，被删掉的用例不会残留在快照里。 */
    private void syncCatalog(EvalCasesResponse catalog) {
        for (EvalCasesResponse.EvalCase item : catalog.getCases()) {
            evalCaseMapper.delete(new QueryWrapper<EvalCaseEntity>()
                .eq("case_id", item.getCaseId()));
            EvalCaseEntity entity = new EvalCaseEntity();
            entity.setCaseId(item.getCaseId());
            entity.setLevel(item.getLevel());
            entity.setRequirement(item.getRequirement());
            entity.setFocus(item.getFocus());
            entity.setDefectsJson(toJson(item.getDefects()));
            entity.setExpectClarify(Boolean.TRUE.equals(item.getExpectClarify()) ? 1 : 0);
            entity.setExpectProactiveReport(toJson(item.getExpectProactiveReport()));
            evalCaseMapper.insert(entity);
        }
        log.info("评测用例目录已同步 {} 条", catalog.getCases().size());
    }

    private EvalRunEntity newRun(String runCode, EvalRunRequest request) {
        EvalRunEntity run = new EvalRunEntity();
        run.setRunCode(runCode);
        run.setDatasetSeed(request.getSeed());
        // run 级只记生成规则，具体路径在各 case 的 task 里
        run.setDatasetPath(".eval/eval_{caseId}_s" + request.getSeed() + ".parquet");
        run.setGoldenPath(".eval/eval_{caseId}_s" + request.getSeed() + ".golden.json");
        run.setModelName(modelName);
        run.setChangeNote(request.getChangeNote());
        run.setCaseTotal(0);
        run.setCasePassed(0);
        run.setStartTime(LocalDateTime.now());
        return run;
    }

    private void persistResult(String runCode, CaseScore score) {
        try {
            EvalResultEntity entity = new EvalResultEntity();
            entity.setRunCode(runCode);
            entity.setCaseId(score.getCaseId());
            entity.setTaskCode(score.getTaskCode());
            entity.setExecSuccess(score.isExecSuccess() ? 1 : 0);
            entity.setResultCorrect(score.isResultCorrect() ? 1 : 0);
            entity.setClarifyExpected(score.isClarifyExpected() ? 1 : 0);
            entity.setClarifyActual(score.isClarifyActual() ? 1 : 0);
            entity.setDetectedJson(toJson(score.getDetected()));
            entity.setMissedJson(toJson(score.getMissed()));
            entity.setFalseAlarmJson(toJson(score.getFalseAlarm()));
            entity.setRepairAttempts(score.getRepairAttempts());
            entity.setCostMillis(score.getCostMillis());
            entity.setFailureCategory(score.getFailureCategory().name());
            entity.setFailureDetail(truncate(score.getFailureDetail(), 2000));
            evalResultMapper.insert(entity);
        }
        catch (Exception exception) {
            // 落库失败不带走这一轮，但必须记录：少一行明细则 JOIN 类指标少一个样本
            log.warn("用例 {} 的结果落库失败，本轮 JOIN 类指标会少这一条: {}",
                score.getCaseId(), exception.getMessage());
        }
    }

    private EvalRunReportVO.CaseRow toRow(CaseScore score) {
        EvalRunReportVO.CaseRow row = new EvalRunReportVO.CaseRow();
        row.setCaseId(score.getCaseId());
        row.setCaseLevel(score.getCaseLevel());
        row.setTaskCode(score.getTaskCode());
        row.setExecAttempted(score.isExecAttempted());
        row.setExecSuccess(score.isExecSuccess());
        row.setResultScorable(score.isResultScorable());
        row.setResultCorrect(score.isResultCorrect());
        row.setClarifyExpected(score.isClarifyExpected());
        row.setClarifyActual(score.isClarifyActual());
        row.setDetected(new ArrayList<>(score.getDetected()));
        row.setMissed(new ArrayList<>(score.getMissed()));
        row.setFalseAlarm(new ArrayList<>(score.getFalseAlarm()));
        row.setBaselineInherent(new ArrayList<>(score.getBaselineInherent()));
        row.setRepairAttempts(score.getRepairAttempts());
        row.setRepairShortCircuited(score.getRepairShortCircuited());
        row.setCostMillis(score.getCostMillis());
        row.setFailureCategory(score.getFailureCategory().name());
        row.setFailureDetail(score.getFailureDetail());
        row.setUnmatchedSuspicions(score.getUnmatchedSuspicions().size());
        return row;
    }

    /** 本轮测不了的东西，放进返回体，避免把「没测」读成「测了且通过」。 */
    private void fillCaveats(EvalRunReportVO report, List<String> probeFailures) {
        if (!probeFailures.isEmpty()) {
            // 排在第一条：量具坏了的时候，先说量具，再说数字
            report.getCaveats().add(
                "★ 处理前行级探针在 " + probeFailures.size() + "/" + report.getCaseTotal()
                    + " 个用例上失败，这些用例的越界类缺陷被记成漏检，行级检出率是低估值。"
                    + "首例原因：" + probeFailures.get(0));
        }
        // Token 相关的 caveat 由 MetricsAggregator 按实测情况添加
        // （没采到 / 模型没回 usage / 有未归属阶段），不在这里写死。
        report.getCaveats().add(
            "澄清类用例只测到「问没问对」，测不到「答完之后做没做对」——"
                + "澄清答复回传接口（PipelineMode.CLARIFY_RESUME）尚未实现，"
                + "这些用例不计入结果正确率");
        report.getCaveats().add(
            "常识级检出经关键词归一，测出来的是下界：模型用词表之外的说法描述同一个问题时会被记成漏检。"
                + "每个用例的 unmatchedSuspicions 给出未能归一的疑点条数，"
                + "它同时是口径损失和对冲式误报的量度");
        report.getCaveats().add(
            "COMPUTE_SCORE 的 payload 装配已在 M4 第 2 轮实现，但只覆盖 SEVERITY_SCORING；"
                + "TEXT_MAPPING / MISSING_SEMANTICS 的 params 装配仍未做，"
                + "这两类动作查到规则也只记 ruleId、不注参数");
        report.getCaveats().add(
            "行级越界的检出依赖评测层的「处理前探针」——链路本身在画像阶段不做越界判定。"
                + "这是链路的缺口，M4 未处置（预算给了更大的失败簇），仍然成立："
                + "行级检出率测的是「校验器能不能查出来」，不是「链路会不会去查」");
        report.getCaveats().add(
            "★ 常识级检出率的分母只有 5 个样本，逐轮之间的变化（如 1/5 与 0/5）"
                + "是一个用例的差别，在已知 ±10pp 输出波动的前提下不足以支撑任何结论。"
                + "要让它可读，先得把 L4 用例的样本量提上去");
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
