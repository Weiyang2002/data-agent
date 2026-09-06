-- ════════════════════════════════════════════════════
-- M3：给 data_agent_eval_run 补两列检出率
--
-- 为什么原表少了这两列：建表时按 v1 §6.3 的三层写了
-- row / dist / sense，但缺陷分类学实际是五层——
-- STRUCTURE（连续空白段）和 CLARIFY（缺失语义）各自独立，
-- 前者是确定性统计推断，后者根本不该由系统下结论。
--
-- 把它们塞进 dist 或 sense 都会污染那一层的口径：
-- M3 验收判据要求四层分开列，合并出来的数字读不出能力边界在哪。
--
-- 为什么不直接改 create_table.sql 了事：那个文件用的是
-- CREATE TABLE IF NOT EXISTS，对已经存在的表不生效。
-- 已建库的机器只跑它，这两列永远不会出现。
--
-- 幂等：重复执行安全（先查 information_schema 再决定是否 ALTER）。
-- ════════════════════════════════════════════════════

SET @add_structure := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `data_agent_eval_run`
             ADD COLUMN `detect_rate_structure` DECIMAL(6,4) DEFAULT NULL
             COMMENT ''结构级检出率：连续空白段这类确定性统计推断''
             AFTER `detect_rate_dist`',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_agent_eval_run'
      AND COLUMN_NAME = 'detect_rate_structure');
PREPARE stmt FROM @add_structure;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_clarify := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `data_agent_eval_run`
             ADD COLUMN `detect_rate_clarify` DECIMAL(6,4) DEFAULT NULL
             COMMENT ''待澄清级检出率：检出=触发了对应澄清，而非给出结论''
             AFTER `detect_rate_sense`',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_agent_eval_run'
      AND COLUMN_NAME = 'detect_rate_clarify');
PREPARE stmt FROM @add_clarify;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
