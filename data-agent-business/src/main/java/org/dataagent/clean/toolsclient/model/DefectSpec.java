package org.dataagent.clean.toolsclient.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个缺陷的注入配置。
 *
 * <p>字段名与 Python 侧 {@code schemas/dataset.py::DefectSpec} 完全一致，不做映射。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DefectSpec {

    /** 缺陷类型码，可用值见 GET /dataset/defects */
    private String code;

    /** 注入比例，按行占比 */
    private Double ratio;

    /** 作用列；为空则用该缺陷的默认列 */
    private List<String> columns;

    public static DefectSpec of(String code, double ratio) {
        return new DefectSpec(code, ratio, null);
    }

    public static DefectSpec of(String code, double ratio, List<String> columns) {
        return new DefectSpec(code, ratio, columns);
    }
}
