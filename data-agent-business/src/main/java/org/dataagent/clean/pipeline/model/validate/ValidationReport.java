package org.dataagent.clean.pipeline.model.validate;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三层校验的汇总结果。 */
@Data
public class ValidationReport {

    private List<ValidationFinding> findings = new ArrayList<>();

    /**
     * 各层是否真的跑过。「跑了没发现问题」和「压根没跑」必须能区分（行级校验在知识库
     * 无依据时会被跳过，此时空的 findings 列表不代表通过）。
     */
    private Map<FindingLevel, Boolean> levelExecuted = new LinkedHashMap<>();

    /** 各层被跳过的原因，与 levelExecuted 配套 */
    private Map<FindingLevel, String> levelSkipReason = new LinkedHashMap<>();

    public void markExecuted(FindingLevel level) {
        levelExecuted.put(level, true);
        levelSkipReason.remove(level);
    }

    public void markSkipped(FindingLevel level, String reason) {
        levelExecuted.put(level, false);
        levelSkipReason.put(level, reason);
    }

    public void add(ValidationFinding finding) {
        findings.add(finding);
    }

    public void addAll(List<ValidationFinding> items) {
        findings.addAll(items);
    }

    public List<ValidationFinding> byLevel(FindingLevel level) {
        return findings.stream().filter(item -> item.getLevel() == level).toList();
    }

    /** 有依据的发现占比，规范遵从率的单任务视图 */
    public double evidenceRatio() {
        if (findings.isEmpty()) {
            return 1.0;
        }
        long withEvidence = findings.stream()
            .filter(item -> item.getSourceRuleId() != null).count();
        return (double) withEvidence / findings.size();
    }
}
