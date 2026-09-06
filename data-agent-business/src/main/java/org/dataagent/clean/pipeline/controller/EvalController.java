package org.dataagent.clean.pipeline.controller;

import org.dataagent.clean.toolsclient.client.DataToolsClient;
import org.dataagent.clean.toolsclient.model.DatasetGenerateRequest;
import org.dataagent.clean.toolsclient.model.DatasetGenerateResponse;
import org.dataagent.clean.toolsclient.model.DefectSpec;
import org.dataagent.clean.toolsclient.model.EvalCasesResponse;
import org.dataagent.common.result.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 评测数据接口，主要用于人工触发与检查（评测 Runner 直接调 {@link DataToolsClient}）。 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final DataToolsClient dataToolsClient;

    public EvalController(DataToolsClient dataToolsClient) {
        this.dataToolsClient = dataToolsClient;
    }

    /** 评测用例目录 */
    @GetMapping("/cases")
    public ApiResponse<EvalCasesResponse> cases() {
        return ApiResponse.success(dataToolsClient.listEvalCases());
    }

    /** 自定义配置生成数据集 */
    @PostMapping("/dataset")
    public ApiResponse<DatasetGenerateResponse> dataset(@RequestBody DatasetGenerateRequest request) {
        return ApiResponse.success(dataToolsClient.generateDataset(request));
    }

    /** 生成「全缺陷」数据集，比例取自真实数据的实测数字。 */
    @PostMapping("/dataset/full")
    public ApiResponse<DatasetGenerateResponse> fullDataset(
            @RequestParam(defaultValue = "50000") int rows,
            @RequestParam(defaultValue = "3000") int patients,
            @RequestParam(defaultValue = "42") int seed) {

        DatasetGenerateRequest request = new DatasetGenerateRequest();
        request.setRows(rows);
        request.setPatients(patients);
        request.setSeed(seed);
        request.setDatasetName("full_seed" + seed);
        request.setDefects(List.of(
            DefectSpec.of("ROW_DUPLICATE", 0.02),
            DefectSpec.of("OUT_OF_RANGE", 0.005),
            DefectSpec.of("DATE_FORMAT_MIXED", 0.15),
            DefectSpec.of("TIMESTAMP_ALL_ZERO", 0.80),
            DefectSpec.of("FULLWIDTH_MIXED", 0.10),
            DefectSpec.of("DUP_CONCAT", 0.03),
            DefectSpec.of("NUMERIC_IN_TEXT", 0.02),
            DefectSpec.of("COLUMN_MISALIGN", 1.00),
            DefectSpec.of("TIME_INVERSION", 0.01),
            DefectSpec.of("WINDOW_ALL_NULL", 0.02),
            DefectSpec.of("MISSING_SEMANTIC", 0.9916)
        ));
        return ApiResponse.success(dataToolsClient.generateDataset(request));
    }
}
