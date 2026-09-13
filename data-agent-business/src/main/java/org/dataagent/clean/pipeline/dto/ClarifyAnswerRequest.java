package org.dataagent.clean.pipeline.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 医生对澄清项的答复。 */
@Data
public class ClarifyAnswerRequest {

    @NotEmpty(message = "答复不能为空")
    @Valid
    private List<Answer> answers = new ArrayList<>();

    /**
     * 答完是否立刻继续跑。false 只落答复不恢复链路，供医生分几次答完再一起跑。
     */
    private boolean resume = true;

    @Data
    public static class Answer {

        @NotBlank(message = "澄清项编号不能为空")
        private String clarifyCode;

        /**
         * 答复内容。取 {@code optionCodes} 里的机器码最稳，也接受选项原文或 1 起的
         * 序号；三者都对不上时答复照样落库，但不产生执行动作。
         */
        @NotBlank(message = "答复内容不能为空")
        private String answer;
    }
}
