package org.dataagent.clean.toolsclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据画像响应。
 *
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileResponse {

    private String datasetPath;

    private Long rowCount;

    private Integer columnCount;

    private List<ColumnProfile> columns = new ArrayList<>();

    /**
     * 确定性规则检出的异常模式。
     *
     * <p>code 与缺陷注入器共用命名空间，M3 评测可直接与 golden 比对。
     *
     * <p>不含 {@code OUT_OF_RANGE}（需知识库阈值，走 /validate/row），也不含
     * {@code COLUMN_MISALIGN} / {@code TIME_INVERSION}（常识级，统计上正常）。缺失率
     * 极高的列报 {@code HIGH_MISSING_RATE}（事实）而非 {@code MISSING_SEMANTIC}（结论）：
     * 缺失语义是临床判断，由 Java 侧决定。
     */
    private List<AnomalyPattern> anomalyPatterns = new ArrayList<>();

    private List<KeyCandidate> keyCandidates = new ArrayList<>();

    private Long profileMillis;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnProfile {
        private String name;
        private String dtype;
        private Double missingRate;
        private Long distinctCount;
        private NumericProfile numeric;
        /** 仅低基数列有值；高基数列为 null */
        private List<TextValue> textValues;
        /**
         * true 表示该列基数超限、取值未枚举。
         *
         * <p>与「该列没有取值」区分：前者是基数超限未枚举，后者是列本身为空。混同会
         * 让 Planner 对标识列做出错误判断。
         */
        private Boolean textValuesTruncated;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NumericProfile {
        private Double min;
        private Double max;
        private Double mean;
        private Double p50;
        private Double p95;
        private Double zeroRatio;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TextValue {
        private String value;
        private Long count;
        /** 该取值占总行数的比例 —— 澄清项 coverageRatio 的直接来源 */
        private Double ratio;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnomalyPattern {
        private String code;
        private String column;
        /** ROW / DISTRIBUTION / STRUCTURE / CLARIFY */
        private String level;
        private String evidence;
        private Long affectedRows;
        private Double metric;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeyCandidate {
        private List<String> columns;
        /**
         * 唯一性比例。
         *
         * <p>不是 1.0 也返回：真实临床数据几乎没有完美主键，该比例本身即有用信息
         * （如 0.988 表示按该列组合去重会丢 1.2% 的记录）。
         */
        private Double uniqueRatio;
    }
}
