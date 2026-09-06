package org.dataagent.clean.pipeline.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 埋点的启动自检。埋点失效时接口照常返回、Token 全是 0，不易察觉，因此在启动时
 * 检查三项：AOP 代理是否生效、{@code data_agent_task_stage.depth} 列是否存在、
 * {@code data_agent_stage_benchmark} 表是否存在。三项都不阻断启动，但打出
 * 「什么不可用 / 后果是什么 / 怎么修」。
 *
 * <p>走 {@code ApplicationRunner} 而非 {@code @PostConstruct}：后者会在容器装配期
 * 提前实例化所有 Bean，打乱代理生成时机。
 */
@Component
public class ObservabilitySelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ObservabilitySelfCheck.class);

    private final ApplicationContext applicationContext;
    private final JdbcTemplate jdbcTemplate;

    public ObservabilitySelfCheck(ApplicationContext applicationContext,
                                  JdbcTemplate jdbcTemplate) {
        this.applicationContext = applicationContext;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("──── 可观测自检 ────");
        checkAopProxies();
        checkColumn("data_agent_task_stage", "depth",
            "阶段 trace 将全部写入失败（TraceRecorder 只打 WARN，表会悄悄不再增长）");
        checkTable("data_agent_stage_benchmark",
            "Token 账本全部丢失，评测报告的 tokenTotal 会一直是 null");
        log.info("────────────────────────────────");
    }

    /** 标了 {@code @TraceStage} 的 Bean 必须是 AOP 代理，否则 trace 表是空的。 */
    private void checkAopProxies() {
        List<String> unproxied = new ArrayList<>();
        int annotated = 0;

        for (Map.Entry<String, Object> entry
            : applicationContext.getBeansOfType(Object.class).entrySet()) {
            Object bean = entry.getValue();
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            if (!targetClass.getName().startsWith("org.dataagent.")) {
                continue;
            }
            boolean hasStage = false;
            for (java.lang.reflect.Method method : targetClass.getDeclaredMethods()) {
                if (AnnotationUtils.findAnnotation(method, TraceStage.class) != null) {
                    hasStage = true;
                    break;
                }
            }
            if (!hasStage) {
                continue;
            }
            annotated++;
            if (!AopUtils.isAopProxy(bean)) {
                unproxied.add(targetClass.getSimpleName());
            }
        }

        if (annotated == 0) {
            log.warn("没有找到任何标注 @TraceStage 的 Bean，全链路不会产生 trace");
            log.warn("  修复：确认注解标在 Spring Bean 的 public 方法上");
            return;
        }
        if (!unproxied.isEmpty()) {
            log.warn("以下 Bean 标了 @TraceStage 但没有被 AOP 代理，埋点不会生效: {}",
                String.join(", ", unproxied));
            log.warn("  后果：这些阶段不会出现在 trace 里，Token 会落到父阶段或 UNATTRIBUTED");
            log.warn("  修复：确认 spring-boot-starter-aop 在依赖里，且 TraceStageAspect 被扫描到");
            return;
        }
        log.info("AOP 埋点已生效：{} 个 Bean 带 @TraceStage，全部已代理", annotated);
    }

    private void checkColumn(String table, String column, String consequence) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, table, column);
            if (count == null || count == 0) {
                log.warn("{}.{} 列不存在：{}", table, column, consequence);
                log.warn("  修复：mysql -uroot -p123456 data_agent "
                    + "< sql/schema/mysql/alter_m5_observability.sql");
                return;
            }
            log.info("{}.{} 就位", table, column);
        }
        catch (Exception exception) {
            log.warn("无法检查 {}.{}（{}），自检本身失败也要说出来", table, column, exception.getMessage());
        }
    }

    private void checkTable(String table, String consequence) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                """, Integer.class, table);
            if (count == null || count == 0) {
                log.warn("表 {} 不存在：{}", table, consequence);
                log.warn("  修复：mysql -uroot -p123456 data_agent "
                    + "< sql/schema/mysql/alter_m5_observability.sql");
                return;
            }
            log.info("表 {} 就位", table);
        }
        catch (Exception exception) {
            log.warn("无法检查表 {}（{}），自检本身失败也要说出来",
                table, exception.getMessage());
        }
    }
}
