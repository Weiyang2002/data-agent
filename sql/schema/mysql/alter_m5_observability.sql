-- ════════════════════════════════════════════════════
-- M5：可观测两处 DDL 改动（已建库的机器跑这一份）
--
--   1. data_agent_task_stage 补 `depth` 列
--   2. data_agent_stage_benchmark 建表
--
-- 为什么单独出一份而不是只改 create_table.sql：
--   那个文件用的是 CREATE TABLE IF NOT EXISTS，对<b>已经存在</b>的
--   data_agent_task_stage 完全不生效。M3 的 alter_m3_eval_run_levels.sql
--   已经踩过一次，这里沿用同一套写法。
--
-- ★ depth 这一列为什么是必须的，而不是「顺手加的」：
--   M5 把埋点从「顶层四个阶段」细化到「顶层 + 嵌套子阶段」
--   （CODEGEN/REPAIR 嵌在 SANDBOX 内，VALIDATE_* 嵌在 VALIDATE 内）。
--   端到端延迟的口径是「各阶段 cost_millis 求和」——
--   子阶段的耗时本来就包含在父阶段里，不加区分地求和会让延迟凭空翻倍。
--   更糟的是翻倍后的数字仍然「看起来合理」，于是 M3/M4 的延迟序列
--   会在没人察觉的情况下断掉。加上 depth 并把延迟收紧为 depth = 0，
--   M5 之后的延迟与 R1–R4 严格可比。
--
-- 幂等：重复执行安全。
-- ════════════════════════════════════════════════════

SET @add_depth := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `data_agent_task_stage`
             ADD COLUMN `depth` INT NOT NULL DEFAULT 0
             COMMENT ''嵌套深度，0=顶层；延迟统计只求和 depth=0''
             AFTER `stage_order`',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_agent_task_stage'
      AND COLUMN_NAME = 'depth');
PREPARE stmt FROM @add_depth;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


CREATE TABLE IF NOT EXISTS `data_agent_stage_benchmark`
(
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT,
    `task_code`           VARCHAR(64) NOT NULL COMMENT '关联任务',
    `trace_id`            VARCHAR(64) NOT NULL,
    `stage_code`          VARCHAR(32) NOT NULL COMMENT '阶段码，见 TraceStageCode',
    `stage_order`         INT         NOT NULL,
    `depth`               INT         NOT NULL DEFAULT 0 COMMENT '嵌套深度，0=顶层',
    `enter_count`         INT         NOT NULL DEFAULT 0 COMMENT '本任务内该阶段被进入的次数',
    `model_calls`         INT         NOT NULL DEFAULT 0 COMMENT '★ 模型调用次数，不是沙箱执行次数',
    `prompt_tokens`       BIGINT      NOT NULL DEFAULT 0,
    `completion_tokens`   BIGINT      NOT NULL DEFAULT 0,
    `total_tokens`        BIGINT      NOT NULL DEFAULT 0 COMMENT '本阶段自身消耗，不含子阶段',
    `usage_missing_calls` INT         NOT NULL DEFAULT 0 COMMENT '★ 模型未回 usage 的调用数；>0 说明该阶段 Token 是下界',
    `self_cost_millis`    BIGINT      NOT NULL DEFAULT 0 COMMENT '本阶段自身耗时，已扣除子阶段',
    `total_cost_millis`   BIGINT      NOT NULL DEFAULT 0 COMMENT '含子阶段的墙钟耗时',
    `create_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_stage` (`task_code`, `stage_code`),
    KEY `idx_trace_id` (`trace_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='阶段耗时/Token 账本';
