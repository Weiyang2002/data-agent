package org.dataagent.clean.toolsclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分布级校验响应。
 *
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValidateDistributionResponse {

    private String datasetPath;

    private Long totalRows;

    private List<ProfileResponse.AnomalyPattern> findings = new ArrayList<>();

    /** 被检查列的统计摘要，供 Java 侧构造处理前后 diff */
    private List<ColumnStat> columnStats = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnStat {
        private String name;
        private String dtype;
        private Double missingRate;
        private Long distinctCount;
        private ProfileResponse.NumericProfile numeric;
    }
}
