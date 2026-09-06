package org.dataagent.clean.toolsclient.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测数据集生成请求。
 *
 * <p>与 Python 侧 {@code schemas/dataset.py::DatasetGenerateRequest} 字段一一对应。
 */
@Data
public class DatasetGenerateRequest {

    private Integer rows = 50000;

    private Integer patients = 3000;

    /**
     * 随机种子。同一 seed 必须产出完全相同的数据与 golden，是跨轮次归因的前提：
     * 不可复现则无法区分指标变化来自改动还是数据抖动。
     */
    private Integer seed = 42;

    private List<DefectSpec> defects = new ArrayList<>();

    /** 输出文件名，缺省按 seed 生成 */
    private String datasetName;
}
