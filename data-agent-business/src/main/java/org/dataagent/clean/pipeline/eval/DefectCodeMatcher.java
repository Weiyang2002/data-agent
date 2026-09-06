package org.dataagent.clean.pipeline.eval;

import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把系统报出来的信号归一成 golden 的缺陷码，评测层的「翻译器」。
 *
 */
@Component
public class DefectCodeMatcher {

    private static final Logger log = LoggerFactory.getLogger(DefectCodeMatcher.class);

    /** 常识级校验产出的固定 code，内容全在自由文本里 */
    public static final String SENSE_CODE = "COMMON_SENSE_SUSPICION";

    /** 列名通配符：检测器给不出可靠列名时用它 */
    public static final String ANY_COLUMN = "*";

    /**
     * 内部码 → golden 缺陷码。画像报 {@code HIGH_MISSING_RATE}（事实），golden 记
     * {@code MISSING_SEMANTIC}（结论），命名差异是架构边界的体现，映射放在评测层。
     */
    private static final Map<String, String> CODE_ALIAS = Map.of(
        "HIGH_MISSING_RATE", "MISSING_SEMANTIC"
    );

    /**
     * 合成基线自带、与注入无关的信号：码 → 列。都是数据生成器刻意复现的真实数据
     * 特征（升压药高缺失、结局时间高缺失、意识列 TWD 取值），见 {@code columns.py}。
     * 这类信号不进误报集合，但单独计数并在指标表里标注口径。
     */
    private static final Map<String, Set<String>> BASELINE_INHERENT = Map.of(
        "MISSING_SEMANTIC", Set.of("升压药", "结局时间"),
        "OUT_OF_RANGE", Set.of("意识")
    );

    /**
     * 常识级自由文本 → 缺陷码的关键词表。词条只收现象，不收「异常」「疑似」这类
     * 通用词。
     */
    private static final Map<String, List<String>> SENSE_KEYWORDS = new LinkedHashMap<>();

    static {
        SENSE_KEYWORDS.put("COLUMN_MISALIGN", List.of(
            "人均入院", "人均住院", "入院次数", "住院次数", "住院人次",
            "患者数与", "患者数等于", "一人一次", "错位", "一一对应"));
        SENSE_KEYWORDS.put("TIME_INVERSION", List.of(
            "早于入院", "先于入院", "时间倒置", "时间倒挂", "时序倒", "时序错",
            "记录时间早", "结局时间早", "负的时间", "负值时长", "住院时长为负"));
    }

    /**
     * 系统在这次任务里报出来的全部信号，四个来源：画像阶段的确定性检出、评测层
     * 处理前探针、处理后三层校验发现、澄清项（缺失语义类走澄清而非 finding）。
     */
    public DetectionView detect(CleanTaskResultVO result, List<Signal> extraSignals) {
        List<Signal> signals = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();

        result.getProfileAnomalies().forEach(item ->
            add(signals, item.getCode(), item.getColumn()));
        if (extraSignals != null) {
            extraSignals.forEach(signal -> add(signals, signal.code(), signal.column()));
        }

        for (CleanTaskResultVO.FindingView finding : result.getFindings()) {
            if (!SENSE_CODE.equals(finding.getCode())) {
                add(signals, finding.getCode(), finding.getColumn());
                continue;
            }
            String text = (finding.getColumn() == null ? "" : finding.getColumn())
                + " " + (finding.getEvidence() == null ? "" : finding.getEvidence());
            List<String> matched = matchSense(text);
            if (matched.isEmpty()) {
                unmatched.add(text);
            }
            // 常识级 subject 不可靠，注册成通配列
            matched.forEach(code -> add(signals, code, ANY_COLUMN));
        }

        for (CleanTaskResultVO.ClarificationView item : result.getClarifications()) {
            if (item.getTopic() != null && item.getTopic().endsWith("/MISSING_SEMANTICS")) {
                add(signals, "MISSING_SEMANTIC", item.getColumnName());
            }
        }
        return DetectionView.of(signals, unmatched);
    }

    /** 关键词归一：一条疑点可能同时命中多个码，全部保留 */
    public List<String> matchSense(String text) {
        List<String> matched = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return matched;
        }
        SENSE_KEYWORDS.forEach((code, keywords) -> {
            if (keywords.stream().anyMatch(text::contains)) {
                matched.add(code);
            }
        });
        return matched;
    }

    /** 内部码 → golden 缺陷码。null / 空返回 null */
    public String normalize(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        return CODE_ALIAS.getOrDefault(rawCode, rawCode);
    }

    private void add(List<Signal> signals, String rawCode, String column) {
        String code = normalize(rawCode);
        if (code != null) {
            signals.add(new Signal(code, column == null || column.isBlank() ? ANY_COLUMN : column));
        }
    }

    /**
     * 一条被报告出来的信号。
     *
     * @param code   归一后的缺陷码
     * @param column 检测器给出的列名；不可靠或无列语义时是 {@link #ANY_COLUMN}
     */
    public record Signal(String code, String column) {
    }

    /** 系统本次报告的全景。 */
    public record DetectionView(Set<String> codes,
                                Map<String, Set<String>> columnsByCode,
                                Set<String> baselineInherentCodes,
                                List<String> unmatchedSuspicions) {

        static DetectionView of(List<Signal> signals, List<String> unmatched) {
            Map<String, Set<String>> byCode = new LinkedHashMap<>();
            for (Signal signal : signals) {
                byCode.computeIfAbsent(signal.code(), key -> new LinkedHashSet<>())
                    .add(signal.column());
            }
            Set<String> inherent = new LinkedHashSet<>();
            byCode.forEach((code, columns) -> {
                Set<String> allowed = BASELINE_INHERENT.get(code);
                // 全部出现列都是基线自带的才算基线信号，混了别的列不豁免
                if (allowed != null && allowed.containsAll(columns)) {
                    inherent.add(code);
                }
            });
            return new DetectionView(new LinkedHashSet<>(byCode.keySet()), byCode,
                inherent, unmatched);
        }

        /**
         * golden 里的某个缺陷是否被报到了。
         *
         * @param goldenColumns 该缺陷的注入列；为空表示无列语义（如整行重复）
         */
        public boolean hit(String code, List<String> goldenColumns) {
            Set<String> reported = columnsByCode.get(code);
            if (reported == null) {
                return false;
            }
            if (goldenColumns == null || goldenColumns.isEmpty()) {
                return true;
            }
            // 通配列兜底：常识级归一出来的码定位不到具体列
            return reported.contains(ANY_COLUMN)
                || goldenColumns.stream().anyMatch(reported::contains);
        }

        /** 「码@列」形式的键集合，用于结果正确率的残留判定 */
        public Set<String> keys() {
            Set<String> keys = new LinkedHashSet<>();
            columnsByCode.forEach((code, columns) ->
                columns.forEach(column -> keys.add(code + "@" + column)));
            return keys;
        }

        public void logSummary(String caseId) {
            if (!unmatchedSuspicions.isEmpty()) {
                log.info("[{}] 常识级有 {} 条疑点未能归一到缺陷码（口径损失或误报）: {}",
                    caseId, unmatchedSuspicions.size(), unmatchedSuspicions);
            }
            if (!baselineInherentCodes.isEmpty()) {
                log.info("[{}] 以下检出属于合成基线自带、非注入缺陷，不计误报: {}",
                    caseId, baselineInherentCodes);
            }
        }
    }
}
