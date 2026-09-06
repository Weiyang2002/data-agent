package org.dataagent.clean.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 发起一轮评测。 */
@Data
public class EvalRunRequest {

    /** 本轮改了什么，唯一的必填项。少了它指标序列就无法归因。 */
    @NotBlank(message = "changeNote 必填：不记录「这一轮改了什么」，指标变化就无法归因")
    private String changeNote;

    /**
     * 每个用例的数据集行数。默认 20000：缺陷注入按行占比算，2 万行足以让每一类缺陷
     * 产生稳定信号，需要贴近真实规模时显式传大值。
     */
    private Integer rows = 20000;

    private Integer patients = 1200;

    /** 同 seed 必须产出完全相同的数据与 golden，是跨轮次可比的前提 */
    private Integer seed = 42;

    /** 只跑指定用例，空表示全跑，如 ["L1-01","L4-01"] */
    private List<String> caseIds = new ArrayList<>();
}
