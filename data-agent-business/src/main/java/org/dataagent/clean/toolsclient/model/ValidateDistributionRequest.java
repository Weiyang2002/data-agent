package org.dataagent.clean.toolsclient.model;

import lombok.Data;

import java.util.List;

/**
 * 分布级校验请求。与行级不同，分布级阈值允许有 Python 侧默认值：它是统计工程判断
 * （「00:00:00 占一半以上就该告警」），没有可引用的院内规范。
 */
@Data
public class ValidateDistributionRequest {

    private String datasetPath;

    /** 限定检查列；为空则检查全部列 */
    private List<String> columns;

    private Thresholds thresholds = new Thresholds();

    private Integer textValueTopN = 50;

    /** 各项为 null 表示沿用 Python 侧 data-tools.yaml 的默认值 */
    @Data
    public static class Thresholds {
        private Double timestampAllZeroRatio;
        private Double highMissingRate;
        private Integer nullRunFactor;
    }

    public static ValidateDistributionRequest of(String datasetPath) {
        ValidateDistributionRequest request = new ValidateDistributionRequest();
        request.setDatasetPath(datasetPath);
        return request;
    }
}
