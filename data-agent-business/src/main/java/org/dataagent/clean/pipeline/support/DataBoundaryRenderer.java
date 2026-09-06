package org.dataagent.clean.pipeline.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把数据画像渲染成可以安全注入 Prompt 的文本。
 */
@Component
public class DataBoundaryRenderer {

    private static final Logger log = LoggerFactory.getLogger(DataBoundaryRenderer.class);

    public static final String OPEN_TAG = "<data_profile>";
    public static final String CLOSE_TAG = "</data_profile>";

    /** 注入 Prompt 的文本列取值上限，比画像本身的 topN 更小。 */
    private static final int PROMPT_TEXT_VALUE_LIMIT = 12;

    private final ObjectMapper objectMapper;

    public DataBoundaryRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把内容包进数据区标签。标签由 Java 拼接而非写在 .st 模板里：StringTemplate
     * 会把模板正文里的 {@code </} 当成表达式语法而抛 STException。
     */
    public String wrap(String content) {
        return OPEN_TAG + "\n" + (content == null ? "" : content) + "\n" + CLOSE_TAG;
    }

    /** 渲染画像并包进数据区标签，可直接作为模板变量注入。 */
    public String renderProfileBlock(ProfileResponse profile) {
        return wrap(renderProfile(profile));
    }

    /** 任意文本转义后包进数据区标签。 */
    public String wrapEscaped(String content) {
        return wrap(escape(content));
    }

    /** 渲染画像正文（不含标签）。 */
    public String renderProfile(ProfileResponse profile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rowCount", profile.getRowCount());
        payload.put("columnCount", profile.getColumnCount());

        List<Map<String, Object>> columns = new ArrayList<>();
        for (ProfileResponse.ColumnProfile column : profile.getColumns()) {
            columns.add(renderColumn(column));
        }
        payload.put("columns", columns);

        List<Map<String, Object>> anomalies = new ArrayList<>();
        for (ProfileResponse.AnomalyPattern pattern : profile.getAnomalyPatterns()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", pattern.getCode());
            item.put("column", pattern.getColumn());
            item.put("level", pattern.getLevel());
            item.put("evidence", pattern.getEvidence());
            item.put("affectedRows", pattern.getAffectedRows());
            anomalies.add(item);
        }
        payload.put("anomalyPatterns", anomalies);

        List<Map<String, Object>> keys = new ArrayList<>();
        for (ProfileResponse.KeyCandidate candidate : profile.getKeyCandidates()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("columns", candidate.getColumns());
            item.put("uniqueRatio", candidate.getUniqueRatio());
            keys.add(item);
        }
        payload.put("keyCandidates", keys);

        return escape(toJson(payload));
    }

    private Map<String, Object> renderColumn(ProfileResponse.ColumnProfile column) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", column.getName());
        item.put("dtype", column.getDtype());
        item.put("missingRate", column.getMissingRate());
        item.put("distinctCount", column.getDistinctCount());
        if (column.getNumeric() != null) {
            Map<String, Object> numeric = new LinkedHashMap<>();
            numeric.put("min", column.getNumeric().getMin());
            numeric.put("max", column.getNumeric().getMax());
            numeric.put("p50", column.getNumeric().getP50());
            numeric.put("p95", column.getNumeric().getP95());
            item.put("numeric", numeric);
        }
        if (column.getTextValues() != null) {
            List<Map<String, Object>> values = new ArrayList<>();
            column.getTextValues().stream()
                .limit(PROMPT_TEXT_VALUE_LIMIT)
                .forEach(value -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("value", value.getValue());
                    entry.put("ratio", value.getRatio());
                    values.add(entry);
                });
            item.put("textValues", values);
        }
        else if (Boolean.TRUE.equals(column.getTextValuesTruncated())) {
            // 显式说明「故意没枚举」，避免模型读成「这列是空的」
            item.put("textValues", "OMITTED_HIGH_CARDINALITY");
        }
        return item;
    }

    /** 任意文本的数据区转义，用于医生的自由输入、Python 返回的 evidence 等。 */
    public String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!text.contains(OPEN_TAG) && !text.contains(CLOSE_TAG)) {
            return text;
        }
        // 出现闭合标签字面量：数据脏或有人试探边界，都留一条日志
        log.warn("注入内容中出现 data_profile 标签字面量，已转义。这可能是一次提示注入尝试");
        return text
            .replace(OPEN_TAG, "&lt;data_profile&gt;")
            .replace(CLOSE_TAG, "&lt;/data_profile&gt;");
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("画像序列化失败", exception);
        }
    }
}
