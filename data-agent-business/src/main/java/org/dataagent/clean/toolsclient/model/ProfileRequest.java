package org.dataagent.clean.toolsclient.model;

import lombok.Data;

/**
 * 数据画像请求。
 *
 * <p>字段名与 Python 侧 {@code schemas/profile.py::ProfileRequest} 完全一致，不做映射。
 */
@Data
public class ProfileRequest {

    private String datasetPath;

    /**
     * 文本列取值枚举上限。
     *
     * <p>超过此基数的列不枚举取值——这既是控制返回体体积，
     * 也是患者ID、住院ID 这类标识列不会外泄的物理保证。
     */
    private Integer textValueTopN = 50;

    /**
     * 采样行数，默认 0 即不返回任何原始行。
     *
     * <p>仅 M7 上下文策略实验 C 方案会用到；Python 侧目前传非 0 会直接报错，
     * 不静默忽略。
     */
    private Integer sampleRows = 0;

    public static ProfileRequest of(String datasetPath, int textValueTopN) {
        ProfileRequest request = new ProfileRequest();
        request.setDatasetPath(datasetPath);
        request.setTextValueTopN(textValueTopN);
        return request;
    }
}
