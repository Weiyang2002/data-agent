package org.dataagent.clean.pipeline.knowledge;

import java.util.List;

/**
 * VALIDITY 规则的 payload 解析结果。
 *
 * <p>两种形状共用一个类型：数值列是区间 {@code [min, max]}，文本列是允许取值集合。
 *
 * @param ruleId        知识库规则主键，会跟着校验发现一路带到医生面前
 * @param column        规范列名
 * @param min           数值下界，文本规则为 null
 * @param max           数值上界，文本规则为 null
 * @param allowedValues 文本允许取值，数值规则为 null
 * @param unit          单位，用于生成人类可读的证据
 * @param sourceDoc     来源文档
 * @param sourceLocator 文档内定位
 */
public record ValidityRange(
    Long ruleId,
    String column,
    Double min,
    Double max,
    List<String> allowedValues,
    String unit,
    String sourceDoc,
    String sourceLocator
) {

    public boolean isNumeric() {
        return min != null || max != null;
    }

    /**
     * 参数通道用的键。随 {@code /execute} 的 params 传进沙箱，生成的代码写
     * {@code params["体温_min"]} 而非字面值。
     */
    public String minParamKey() {
        return column + "_min";
    }

    public String maxParamKey() {
        return column + "_max";
    }

    public String allowedValuesParamKey() {
        return column + "_allowed";
    }
}
