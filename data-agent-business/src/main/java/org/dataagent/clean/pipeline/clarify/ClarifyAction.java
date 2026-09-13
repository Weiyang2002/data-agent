package org.dataagent.clean.pipeline.clarify;

import java.util.Optional;

/**
 * 缺失语义决策点的候选动作，医生的答复最终必须归一到其中之一。
 *
 * <p>枚举值是答复的机器码，措辞（{@link #getLabel()}）可以被措辞环节改写，机器码不
 * 可以。选项一律由代码给出，医生做选择题：自由文本无法确定性地映射成执行参数。
 */
public enum ClarifyAction {

    /** 缺失代表该事件没有发生，按阴性填常量；填什么由列的类型定 */
    FILL_NEGATIVE("视为未发生，填入阴性取值"),

    /** 缺失代表该时点没测，保留缺失并另加标记列 */
    KEEP_AND_FLAG("视为未测量，保留缺失并加标记列"),

    /** 缺失代表该时点没测，按同一患者的时序插值补齐 */
    INTERPOLATE("视为未测量，按患者时序插值"),

    /** 该列本次不参与分析 */
    EXCLUDE_COLUMN("该列不参与本次分析"),
    ;

    private final String label;

    ClarifyAction(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 认不出来返回 empty，不猜。 */
    public static Optional<ClarifyAction> parse(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase();
        for (ClarifyAction action : values()) {
            if (action.name().equals(normalized)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }

    /**
     * 把知识库规则里 {@code A_OR_B} 形式的动作词对齐到本枚举。规则用的是规范作者的
     * 词（{@code INTERPOLATE_OR_FLAG}），执行侧用的是本枚举，两套词表要有一处显式
     * 对账，对不上就返回 empty 走「无法映射」而不是挑一个相近的。
     */
    public static Optional<ClarifyAction> fromRuleWord(String word) {
        if (word == null || word.isBlank()) {
            return Optional.empty();
        }
        return switch (word.trim().toUpperCase()) {
            case "INTERPOLATE", "IMPUTE" -> Optional.of(INTERPOLATE);
            case "FLAG", "KEEP", "KEEP_AND_FLAG", "MARK" -> Optional.of(KEEP_AND_FLAG);
            case "FILL", "FILL_ZERO", "FILL_NEGATIVE", "ZERO" -> Optional.of(FILL_NEGATIVE);
            case "DROP", "EXCLUDE", "EXCLUDE_COLUMN" -> Optional.of(EXCLUDE_COLUMN);
            default -> Optional.empty();
        };
    }
}
