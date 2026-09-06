package org.dataagent.clean.toolsclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 评测用例目录。
 *
 * <p>用例定义在 Python 侧的理由：用例要引用缺陷码，
 * 而缺陷码的权威在 {@code defects.py}。定义在 Java 侧会形成两份清单，无法保证一致。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCasesResponse {

    private Integer total;

    /** 各层用例数量：L1_RULE / L2_STRUCTURE / L3_JUDGMENT / L4_DISCOVERY */
    private Map<String, Integer> levelDistribution;

    private List<EvalCase> cases;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvalCase {
        private String caseId;
        private String level;
        /** 医生会怎么说这句话，刻意保留口语化 */
        private String requirement;
        private List<DefectSpec> defects;
        /** 考察点，M4 归因时按它聚类失败 case */
        private String focus;
        /** 是否应当触发澄清。false 的用例用于检验过度澄清 */
        private Boolean expectClarify;
        /** 用户没提但系统应主动报告的缺陷码（L4 专用） */
        private List<String> expectProactiveReport;
    }
}
