-- ════════════════════════════════════════════════════
-- 澄清闭环：两处 DDL 改动（已建库的机器跑这一份）
--
--   1. data_agent_clarification 补 option_codes_json / answer_action / resolved_at
--   2. data_agent_task_checkpoint 建表
--
-- create_table.sql 用的是 CREATE TABLE IF NOT EXISTS，对已存在的
-- data_agent_clarification 不生效，沿用 M3/M5 的增量脚本写法。
--
-- 幂等：重复执行安全。
-- ════════════════════════════════════════════════════

SET @add_option_codes := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `data_agent_clarification`
             ADD COLUMN `option_codes_json` JSON DEFAULT NULL
             COMMENT ''候选答案的机器码，与 options_json 逐位对应；措辞可被模型改写，机器码不可''
             AFTER `options_json`',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_agent_clarification'
      AND COLUMN_NAME = 'option_codes_json');
PREPARE stmt FROM @add_option_codes;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


SET @add_answer_action := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `data_agent_clarification`
             ADD COLUMN `answer_action` VARCHAR(64) DEFAULT NULL
             COMMENT ''答复归一后的机器码；为空表示答复无法映射为确定性动作''
             AFTER `answer`',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_agent_clarification'
      AND COLUMN_NAME = 'answer_action');
PREPARE stmt FROM @add_answer_action;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- 澄清中断时的业务 checkpoint。
--
-- 不用 SAA Graph 的 GRAPH_CHECKPOINT：主链路是顺序 Java 调用不是图，
-- 把 CleaningPlan 塞进图状态再读回来，等于让业务恢复依赖框架的检查点格式，
-- 与「框架隔离」这条不变量相反。理由见 doc/设计决策记录.md。
CREATE TABLE IF NOT EXISTS `data_agent_task_checkpoint`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `task_code`    VARCHAR(64) NOT NULL,
    `trace_id`     VARCHAR(64) NOT NULL,
    `stage`        VARCHAR(32) NOT NULL COMMENT '在哪个阶段中断，当前只有 CLARIFYING',
    `payload_json` LONGTEXT    NOT NULL COMMENT '恢复所需的全部状态：画像 + 方案 + 已执行到哪一步',
    `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_code` (`task_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='澄清中断的业务检查点';
