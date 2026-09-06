package org.dataagent.clean.pipeline.eval;

import org.dataagent.clean.pipeline.data.EvalRunEntity;
import org.dataagent.clean.pipeline.mapper.EvalMetricMapper;
import org.dataagent.clean.pipeline.vo.EvalRunReportVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把单用例评分汇总成一张指标表。三条口径纪律：
 * <ol>
 *   <li>分母只放该进的样本（如执行成功率的分母是真的跑过代码的用例）；</li>
 *   <li>算不出来返回 null 而非 0，区分「没有样本」和「得了 0 分」；</li>
 *   <li>分层指标不合并，按层列出。</li>
 * </ol>
 */
@Component
public class MetricsAggregator {

    private static final Logger log = LoggerFactory.getLogger(MetricsAggregator.class);

    /** 分层检出率的展示顺序：确定性覆盖的在前，需要 LLM 的在后 */
    private static final List<String> LEVEL_ORDER =
        List.of("ROW", "DISTRIBUTION", "STRUCTURE", "COMMON_SENSE", "CLARIFY");

    private static final Map<String, String> LEVEL_NOTE = Map.of(
        "ROW", "确定性代码 + 知识库阈值",
        "DISTRIBUTION", "确定性代码，阈值是统计工程判断",
        "STRUCTURE", "确定性统计推断（连续空白段）",
        "COMMON_SENSE", "LLM 判断；检出与否经关键词归一，是下界",
        "CLARIFY", "缺失语义类，检出 = 触发了对应澄清"
    );

    private final EvalMetricMapper metricMapper;

    public MetricsAggregator(EvalMetricMapper metricMapper) {
        this.metricMapper = metricMapper;
    }

    /** 汇总，同时写满 {@code report}（给人看，带口径）与 {@code run}（入库供序列比较）。 */
    public void aggregate(String runCode, List<CaseScore> scores,
                          EvalRunReportVO report, EvalRunEntity run) {

        // ── 执行成功率 ──
        long execAttempted = scores.stream().filter(CaseScore::isExecAttempted).count();
        long execOk = scores.stream().filter(CaseScore::isExecSuccess).count();
        long noExec = scores.size() - execAttempted;
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "execSuccessRate", "执行成功率", ratio(execOk, execAttempted), execOk, execAttempted,
            "分母是真的发起过沙箱执行的用例；另有 " + noExec
                + " 个用例一步都没执行（全部待澄清或需求落不进动作词表），不计入"));

        // ── 结果正确率 ──
        long scorable = scores.stream().filter(CaseScore::isResultScorable).count();
        long correct = scores.stream().filter(CaseScore::isResultCorrect).count();
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "resultCorrectRate", "结果正确率", ratio(correct, scorable), correct, scorable,
            "判据 = 有输出 且 已执行步骤全成功 且 需求指向的缺陷在处理后不再被检出；"
                + "expectClarify=true 的 " + (scores.size() - scorable)
                + " 个用例不计入（澄清答复回传接口未实现）"));

        // ── 分层检出率 ──
        Map<String, long[]> byLevel = new LinkedHashMap<>();
        for (CaseScore score : scores) {
            for (CaseScore.DefectSample sample : score.getSamples()) {
                long[] counter = byLevel.computeIfAbsent(sample.expectLevel(), key -> new long[2]);
                counter[1]++;
                if (sample.hit()) {
                    counter[0]++;
                }
            }
        }
        for (String level : LEVEL_ORDER) {
            long[] counter = byLevel.getOrDefault(level, new long[2]);
            report.getDetectionByLevel().add(new EvalRunReportVO.LevelStat(
                level, counter[0], counter[1], ratio(counter[0], counter[1]),
                LEVEL_NOTE.getOrDefault(level, "")));
        }
        byLevel.keySet().stream().filter(level -> !LEVEL_ORDER.contains(level))
            .forEach(level -> log.warn("出现了未登记的缺陷层级 {}，指标表里没有它的位置", level));

        run.setDetectRateRow(levelRate(byLevel, "ROW"));
        run.setDetectRateDist(levelRate(byLevel, "DISTRIBUTION"));
        run.setDetectRateStructure(levelRate(byLevel, "STRUCTURE"));
        run.setDetectRateSense(levelRate(byLevel, "COMMON_SENSE"));
        run.setDetectRateClarify(levelRate(byLevel, "CLARIFY"));

        // ── 规范遵从率（按层，来自 validation_finding 表）──
        Double rowCompliance = null;
        for (Map<String, Object> row : metricMapper.selectComplianceByLevel(runCode)) {
            String level = String.valueOf(row.get("level"));
            long total = asLong(row.get("findingCount"));
            long withRule = asLong(row.get("withRuleCount"));
            String note = "ROW".equals(level)
                ? "阈值来自知识库，每条发现都能回答「凭什么这么判」"
                : "该层没有院内规范可引，source_rule_id 为空是正确行为，不是缺陷";
            report.getComplianceByLevel().add(new EvalRunReportVO.LevelStat(
                level, withRule, total, ratio(withRule, total), note));
            if ("ROW".equals(level)) {
                rowCompliance = ratio(withRule, total);
            }
        }
        run.setSpecComplianceRate(rowCompliance);
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "specComplianceRate", "规范遵从率（行级）", rowCompliance,
            report.getComplianceByLevel().stream()
                .filter(stat -> "ROW".equals(stat.level())).mapToLong(EvalRunReportVO.LevelStat::hit).sum(),
            report.getComplianceByLevel().stream()
                .filter(stat -> "ROW".equals(stat.level())).mapToLong(EvalRunReportVO.LevelStat::total).sum(),
            "★ 只在行级有意义。全层合并算出来的数字是误导——"
                + "分布/结构/常识层本来就没有规范可引"));

        // ── 澄清恰当率（双向）──
        long clarifyRight = scores.stream()
            .filter(score -> score.isClarifyExpected() == score.isClarifyActual()).count();
        long shouldAsk = scores.stream().filter(CaseScore::isClarifyExpected).count();
        long shouldAskHit = scores.stream()
            .filter(score -> score.isClarifyExpected() && score.isClarifyActual()).count();
        long shouldNotAsk = scores.size() - shouldAsk;
        long shouldNotAskHit = scores.stream()
            .filter(score -> !score.isClarifyExpected() && !score.isClarifyActual()).count();
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "clarifyPrecision", "澄清恰当率（双向）", ratio(clarifyRight, scores.size()),
            clarifyRight, scores.size(),
            "该问的问了 %d/%d，不该问的没问 %d/%d。★ 过度澄清与澄清不足同等计罚"
                .formatted(shouldAskHit, shouldAsk, shouldNotAskHit, shouldNotAsk)));
        run.setClarifyPrecision(ratio(clarifyRight, scores.size()));

        // ── 问题发现率 ──
        long discoveryTotal = 0;
        long discoveryHit = 0;
        for (CaseScore score : scores) {
            for (CaseScore.DefectSample sample : score.getSamples()) {
                if (!sample.userAsked()) {
                    discoveryTotal++;
                    if (sample.hit()) {
                        discoveryHit++;
                    }
                }
            }
        }
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "discoveryRate", "问题发现率", ratio(discoveryHit, discoveryTotal),
            discoveryHit, discoveryTotal,
            "golden 中 userAsked=false 的缺陷实例被报告的比例 —— 用户没问，系统该不该说"));
        run.setDiscoveryRate(ratio(discoveryHit, discoveryTotal));

        // L4 用例级的主动报告命中，比实例级更严格（要求一个用例里全部报到）
        long proactiveTotal = scores.stream()
            .mapToLong(score -> score.getProactiveExpected().size()).sum();
        long proactiveHit = scores.stream()
            .mapToLong(score -> score.getProactiveHit().size()).sum();
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "l4ProactiveRate", "L4 主动报告命中率", ratio(proactiveHit, proactiveTotal),
            proactiveHit, proactiveTotal,
            "只统计 L4_DISCOVERY 用例显式声明的 expectProactiveReport"));

        // ── 自修复成功率（来自 execution 表）──
        Map<String, Object> repair = metricMapper.selectRepairStats(runCode);
        long repairedSteps = asLong(repair == null ? null : repair.get("repairedSteps"));
        long repairedOk = asLong(repair == null ? null : repair.get("repairedOk"));
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "selfRepairRate", "自修复成功率", ratio(repairedOk, repairedSteps),
            repairedOk, repairedSteps,
            "分母是发生过自修复的步骤数（按 task_code+step_no 去重，"
                + "不按执行行数——一个步骤重试 3 次只算一个样本）"));
        run.setSelfRepairRate(ratio(repairedOk, repairedSteps));

        // ── 自修复的「量」：总轮次 + 短路次数 ──
        // 成功率是比值，无法区分「三轮同一段代码」和「三轮各试一种解法」，故一并报出
        long repairTotal = scores.stream().mapToLong(CaseScore::getRepairAttempts).sum();
        long shortCircuited = scores.stream().mapToLong(CaseScore::getRepairShortCircuited).sum();
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "repairAttemptTotal", "自修复总轮次", (double) repairTotal, repairTotal, scores.size(),
            "全轮累计的自修复「执行」轮次。"
                + "★ 口径注意：被短路掉的那一版代码，模型调用已经发生过，"
                + "只是没有进沙箱——所以这个数字是「沙箱执行次数」的量度，"
                + "不是「模型调用次数」的量度。真正的 Token 账要等 M5 埋点。"
                + "它比通过数抗噪：一个不收敛的步骤改动前必然烧满上限，改动后必然停在第一次"));
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "repairShortCircuit", "不收敛短路次数", (double) shortCircuited,
            shortCircuited, scores.size(),
            "★ M4 机制：修复版与本步骤已执行过的版本逐字节相同即判不收敛、立即停。"
                + "每命中一次就省掉剩余的全部重试轮次。基线上该值恒为 0。"
                + "它命中得越多，说明「模型在这个错误面前无话可说」的情形越多——"
                + "该去改的是报错信息或契约，不是加大重试上限"));

        // ── 延迟（来自 task_stage 表）──
        List<Long> latencies = new ArrayList<>();
        for (Map<String, Object> row : metricMapper.selectTaskLatencies(runCode)) {
            latencies.add(asLong(row.get("totalMillis")));
        }
        latencies.sort(Long::compareTo);
        Long p50 = percentile(latencies, 0.50);
        Long p95 = percentile(latencies, 0.95);
        run.setLatencyP50Millis(p50);
        run.setLatencyP95Millis(p95);
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "latencyP50Millis", "端到端延迟 P50(ms)",
            p50 == null ? null : p50.doubleValue(), p50 == null ? 0 : p50, latencies.size(),
            "各阶段 cost_millis 求和；不用 end_time-start_time 是因为 DATETIME 只到秒"));
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "latencyP95Millis", "端到端延迟 P95(ms)",
            p95 == null ? null : p95.doubleValue(), p95 == null ? 0 : p95, latencies.size(),
            "同上"));

        // ── Token（M5 起采集）──
        aggregateTokens(runCode, scores.size(), report, run);

        // ── 归因直方图 ──
        Map<String, Integer> histogram = new LinkedHashMap<>();
        for (FailureCategory category : FailureCategory.values()) {
            int count = (int) scores.stream()
                .filter(score -> score.getFailureCategory() == category).count();
            if (count > 0) {
                histogram.put(category.name() + "（" + category.getLabel() + "）", count);
            }
        }
        report.setFailureHistogram(histogram);

        run.setExecSuccessRate(ratio(execOk, execAttempted));
        run.setResultCorrectRate(ratio(correct, scorable));
        run.setCaseTotal(scores.size());
        run.setCasePassed((int) scores.stream()
            .filter(score -> score.getFailureCategory().isPass()).count());
        report.setCaseTotal(run.getCaseTotal());
        report.setCasePassed(run.getCasePassed());
    }

    /**
     * Token 指标。CODEGEN 与 REPAIR 是两个独立的阶段码，{@code tokenByStage} 里
     * REPAIR 那一行即自修复烧掉的 Token。三条口径：
     * <ol>
     *   <li>没有账本返回 null 而非 0；</li>
     *   <li>模型未返回 usage 的调用数带进口径说明，此时总量是下界；</li>
     *   <li>UNATTRIBUTED 单列，有值说明某个调用点漏标了 @TraceStage。</li>
     * </ol>
     */
    private void aggregateTokens(String runCode, int caseCount,
                                 EvalRunReportVO report, EvalRunEntity run) {
        Map<String, Object> stats = metricMapper.selectTokenStats(runCode);
        long taskCount = asLong(stats == null ? null : stats.get("taskCount"));

        if (taskCount == 0) {
            run.setTokenTotal(null);
            report.getMetrics().add(new EvalRunReportVO.MetricEntry(
                "tokenTotal", "Token 消耗", null, 0, 0,
                "★ 本轮没有任何 Token 账本。要么 data_agent_stage_benchmark 没建表"
                    + "（跑 sql/schema/mysql/alter_m5_observability.sql），"
                    + "要么埋点没生效。这里留 null 而不是 0 —— "
                    + "0 会被读成「这轮真的没花 Token」"));
            report.getCaveats().add("★ Token 未采集：本轮 stage_benchmark 无数据，"
                + "关于成本的任何结论都不成立");
            return;
        }

        long totalTokens = asLong(stats.get("totalTokens"));
        long promptTokens = asLong(stats.get("promptTokens"));
        long completionTokens = asLong(stats.get("completionTokens"));
        long modelCalls = asLong(stats.get("modelCalls"));
        long usageMissing = asLong(stats.get("usageMissingCalls"));

        run.setTokenTotal(totalTokens);

        String missingNote = usageMissing == 0 ? ""
            : "。★ 其中 %d 次调用模型未返回 usage，本轮 Token 是<b>下界</b>不是真值"
                .formatted(usageMissing);
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "tokenTotal", "Token 消耗（全轮）", (double) totalTokens,
            totalTokens, taskCount,
            "分母是产生过账本的任务数。入 %d / 出 %d，共 %d 次模型调用%s"
                .formatted(promptTokens, completionTokens, modelCalls, missingNote)));
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "tokenPerCase", "单用例平均 Token", ratioValue(totalTokens, taskCount),
            totalTokens, taskCount,
            "跨轮比较用这一个，不用总量 —— 总量会跟着用例数一起变"));
        report.getMetrics().add(new EvalRunReportVO.MetricEntry(
            "modelCallTotal", "模型调用次数", (double) modelCalls, modelCalls, caseCount,
            "★ 与 repairAttemptTotal 是两个口径：那个量的是<b>沙箱执行</b>次数，"
                + "这个量的是<b>模型调用</b>次数。M4 的短路机制省掉的是后者，"
                + "而 M4 全程只能报前者"));

        // ── 按阶段的 Token 分布 ──
        for (Map<String, Object> row : metricMapper.selectTokenByStage(runCode)) {
            String stageCode = String.valueOf(row.get("stageCode"));
            long stageTokens = asLong(row.get("totalTokens"));
            long stageCalls = asLong(row.get("modelCalls"));
            long stageMissing = asLong(row.get("usageMissingCalls"));
            String note = "进入 %d 次，模型调用 %d 次，自身耗时 %d ms".formatted(
                asLong(row.get("enterCount")), stageCalls, asLong(row.get("selfCostMillis")));
            if (stageMissing > 0) {
                note += "｜★ %d 次未回 usage，该行是下界".formatted(stageMissing);
            }
            if ("UNATTRIBUTED".equals(stageCode)) {
                note = "★ 这一行不该存在：有模型调用没被 @TraceStage 覆盖到。" + note;
                report.getCaveats().add("★ 有 %d 次模型调用（%d Token）未归属到任何阶段，"
                    .formatted(stageCalls, stageTokens)
                    + "按阶段拆出来的 Token 分布在这部分是缺的");
            }
            report.getTokenByStage().add(new EvalRunReportVO.LevelStat(
                stageCode, stageTokens, totalTokens,
                ratio(stageTokens, totalTokens), note));
        }

        if (usageMissing > 0) {
            report.getCaveats().add(("★ 本轮有 %d 次模型调用没有返回 usage 元数据，"
                + "Token 总量 %d 是下界。OpenAI 兼容协议里 usage 是可选字段")
                .formatted(usageMissing, totalTokens));
        }
    }

    /** 平均值：分母为 0 时返回 null */
    private Double ratioValue(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return Math.round((double) numerator / denominator * 100) / 100.0;
    }

    private Double levelRate(Map<String, long[]> byLevel, String level) {
        long[] counter = byLevel.get(level);
        return counter == null ? null : ratio(counter[0], counter[1]);
    }

    /** 分母为 0 返回 null 而非 0：区分「没有样本」和「得了 0 分」 */
    private Double ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return Math.round((double) numerator / denominator * 10000) / 10000.0;
    }

    private Long percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
