package org.dataagent.clean.toolsclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/** 评测数据集生成响应，同时也是 golden.json 的结构，评测体系的判据来源。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatasetGenerateResponse {

    private String datasetPath;

    private String goldenPath;

    private Integer rowCount;

    private Integer seed;

    private DatasetBaseline baseline;

    private List<InjectedDefect> injected;

    /** 该问的澄清点 */
    private List<String> expectClarifications;

    /** 不该问的点，澄清恰当率的反向计分依据（否则最优策略退化成「什么都问」）。 */
    private List<String> expectNoClarifications;

    /** 注入前的真实统计量，常识级校验的对照基准 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DatasetBaseline {
        private Integer patientCount;
        private Integer admissionCount;
        /** 真实数据约 1.36，错位缺陷会让它塌成 1.00 */
        private Double admissionsPerPatient;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InjectedDefect {
        private String code;
        /** ROW / DISTRIBUTION / COMMON_SENSE / STRUCTURE / CLARIFY */
        private String expectLevel;
        private Integer affectedRows;
        private List<Integer> rowIds;
        private Boolean rowIdsTruncated;
        private List<String> columns;
        /** 人类可读的应检出信号，M4 归因时用 */
        private String expectSignal;
        /**
         * 用户是否会在需求里主动提到。
         * {@code false} 表示这是 L4 发现类——用户没问，但系统应主动报告。
         */
        private Boolean userAsked;
    }
}
