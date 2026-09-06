package org.dataagent.clean.pipeline.model.profile;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 处理前后的画像对比，常识级校验的唯一输入。{@link #derived} 里的每个比值都由
 * Java 算好，给 LLM 的是「人均入院次数从 1.36 变成 1.00，临床上合理吗」这样的问题。
 * 不含任何原始数据行。
 */
@Data
public class ProfileDiff {

    private long rowCountBefore;
    private long rowCountAfter;

    /** 列名 → 处理前后的关键统计量 */
    private Map<String, ColumnDelta> columns = new LinkedHashMap<>();

    /** 代码算出来的派生指标，LLM 只判断这些数字合不合常理 */
    private Map<String, Object> derived = new LinkedHashMap<>();

    @Data
    public static class ColumnDelta {
        private String name;
        private Double missingRateBefore;
        private Double missingRateAfter;
        private Long distinctBefore;
        private Long distinctAfter;
        private Double minBefore;
        private Double minAfter;
        private Double maxBefore;
        private Double maxAfter;
    }

    public void putDerived(String key, Object value) {
        derived.put(key, value);
    }
}
