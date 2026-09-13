package org.dataagent.clean.pipeline.clarify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.pipeline.data.ClarificationEntity;
import org.dataagent.clean.pipeline.mapper.ClarificationMapper;
import org.dataagent.clean.pipeline.model.plan.ClarificationItem;
import org.dataagent.clean.pipeline.model.plan.ClarificationLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 澄清项的读回与答复落库。
 *
 * <p>答复以库里的澄清项为准而不是以请求体为准：{@code optionCodes} 是提问时定下
 * 的，如果由调用方随请求带上来，答复的合法性就由调用方说了算了。
 */
@Component
public class ClarificationStore {

    private static final Logger log = LoggerFactory.getLogger(ClarificationStore.class);

    private final ClarificationMapper clarificationMapper;
    private final ObjectMapper objectMapper;

    public ClarificationStore(ClarificationMapper clarificationMapper,
                              ObjectMapper objectMapper) {
        this.clarificationMapper = clarificationMapper;
        this.objectMapper = objectMapper;
    }

    /** 按覆盖率降序读回一个任务的全部澄清项。 */
    public List<ClarificationItem> loadByTask(String taskCode) {
        return clarificationMapper.findByTaskCode(taskCode).stream().map(this::toItem).toList();
    }

    public Optional<ClarificationItem> loadByCode(String clarifyCode) {
        return Optional.ofNullable(clarificationMapper.findByClarifyCode(clarifyCode))
            .map(this::toItem);
    }

    /**
     * 落答复。归一失败的答复同样落库，{@code answerAction} 留空 —— 「医生答了但系统
     * 用不上」必须在库里看得出来，否则复盘时会误以为医生没答。
     */
    public void saveAnswer(String clarifyCode, String answer, String answerAction) {
        ClarificationEntity entity = clarificationMapper.findByClarifyCode(clarifyCode);
        if (entity == null) {
            log.warn("澄清项 {} 不存在，答复未落库", clarifyCode);
            return;
        }
        entity.setAnswer(truncate(answer, 1000));
        entity.setAnswerAction(answerAction);
        entity.setAnsweredAt(LocalDateTime.now());
        clarificationMapper.updateById(entity);
    }

    private ClarificationItem toItem(ClarificationEntity entity) {
        ClarificationItem item = new ClarificationItem();
        item.setClarifyCode(entity.getClarifyCode());
        item.setLevel(parseLevel(entity.getLevel()));
        item.setTopic(entity.getTopic());
        item.setQuestion(entity.getQuestion());
        item.setOptions(readStringList(entity.getOptionsJson()));
        item.setOptionCodes(readStringList(entity.getOptionCodesJson()));
        item.setColumnName(entity.getColumnName());
        item.setCoverageRatio(entity.getCoverageRatio() == null ? 0.0 : entity.getCoverageRatio());
        item.setEvidence(entity.getEvidence());
        item.setSourceRuleIds(readLongList(entity.getSourceRuleIds()));
        item.setAnswer(entity.getAnswer());
        item.setAnswerAction(entity.getAnswerAction());
        return item;
    }

    private ClarificationLevel parseLevel(String value) {
        try {
            return ClarificationLevel.valueOf(value);
        }
        catch (Exception exception) {
            // 级别读不出来按「必须追问」处理，宁可多问不可漏问
            log.warn("澄清项级别 {} 无法识别，按 LOW 处理", value);
            return ClarificationLevel.LOW;
        }
    }

    private List<String> readStringList(String json) {
        return read(json, new TypeReference<List<String>>() { });
    }

    private List<Long> readLongList(String json) {
        return read(json, new TypeReference<List<Long>>() { });
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, type);
        }
        catch (Exception exception) {
            log.warn("澄清项字段反序列化失败，按空处理: {}", exception.getMessage());
            return new ArrayList<>();
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
