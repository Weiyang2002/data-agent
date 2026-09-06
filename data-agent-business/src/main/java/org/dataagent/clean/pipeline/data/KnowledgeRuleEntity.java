package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库规则条目
 */
@Data
@TableName("data_agent_knowledge_rule")
public class KnowledgeRuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规范列名，如「体温」 */
    private String columnName;

    /** 别名数组 JSON，如 {@code ["T","TEMP","temperature"]} */
    private String columnAlias;

    /** VALIDITY / SEVERITY_SCORING / TEXT_MAPPING / MISSING_SEMANTICS */
    private String ruleType;

    /** NEWS/MEWS/SEWS/CART/AVPU；非评分规则为空串（不是 null，唯一键要用） */
    private String scoringSystem;

    /** 规则内容 JSON，结构随 ruleType 变化 */
    private String payload;

    /** 来源文档名 —— 会被原样带到每一条校验发现上，医生据此核对 */
    private String sourceDoc;

    /** 文档内定位，如「表1 第1行」 */
    private String sourceLocator;

    /** 规则版本。临床共识会更新，旧版本保留而不是覆盖 */
    private Integer version;

    /** 1 生效 0 失效。规则不物理删除——失效的规则仍是历史结论的依据 */
    private Integer effectiveFlag;

    private LocalDateTime createTime;
}
