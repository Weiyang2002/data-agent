package org.dataagent.clean.pipeline.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 澄清措辞环节的模型输出。只含 {@code clarifyCode / question / options}，模型
 * 无法改主题、覆盖率或增删条目。回填按 clarifyCode 对应，对不上的条目忽略并
 * 保留占位措辞。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WordedQuestions {

    private List<Worded> items = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Worded {
        private String clarifyCode;
        private String question;
        private List<String> options = new ArrayList<>();
    }
}
