-- 临床研究数据处理 Agent —— 建表脚本
--
-- 全脚本幂等（CREATE TABLE IF NOT EXISTS），可重复执行。
-- 按里程碑分段追加，已建段落不再改动：
--   M0 → task / task_stage / knowledge_rule
--   M1 → eval_case / eval_run / eval_result
--   M2 → dataset / column_profile / plan / plan_step / clarification / execution / validation_finding
--   M5 → stage_benchmark + task_stage.depth
--   Phase 1 → memory                 （未建）
--
-- 已建库的机器补 M5 两处改动：alter_m5_observability.sql
-- （本文件是 CREATE TABLE IF NOT EXISTS，对已存在的 task_stage 不生效）
--
-- 知识库首批规则数据在 sql/data/knowledge_rule_seed.sql，需在 M2 链路跑通前执行。
--
-- GRAPH_THREAD 与 GRAPH_CHECKPOINT 两张表由 SAA Graph 的 MysqlSaver
-- 以 CreateOption.CREATE_IF_NOT_EXISTS 自动创建，这里不手写。

USE `data_agent`;

-- ────────────────────────────────────────────────────────────
-- 处理任务主表
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `data_agent_task`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_code`    VARCHAR(64)  NOT NULL COMMENT '业务任务号，对外暴露用，不暴露自增 ID',
    `trace_id`     VARCHAR(64)  NOT NULL COMMENT '链路追踪 ID，贯穿 Java 与 Python',
    `requirement`  TEXT         NOT NULL COMMENT '医生输入的自然语言需求原文',
    `dataset_path` VARCHAR(512)          DEFAULT NULL COMMENT '输入数据集路径',
    `status`       VARCHAR(32)  NOT NULL COMMENT 'CREATED/PROFILING/CLARIFYING/EXECUTING/VALIDATING/DONE/FAILED',
    `thread_id`    VARCHAR(64)           DEFAULT NULL COMMENT 'SAA Graph 线程 ID，关联 GRAPH_CHECKPOINT',
    `fail_reason`  VARCHAR(1024)         DEFAULT NULL COMMENT '失败原因',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_code` (`task_code`),
    KEY `idx_trace_id` (`trace_id`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='数据处理任务主表';


-- ────────────────────────────────────────────────────────────
-- 阶段链路 trace
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `data_agent_task_stage`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `task_code`   VARCHAR(64) NOT NULL COMMENT '关联任务',
    `trace_id`    VARCHAR(64) NOT NULL,
    `stage_code`  VARCHAR(32) NOT NULL COMMENT 'PROFILE/KNOWLEDGE/PLAN/CLARIFY/CODEGEN/SANDBOX/...',
    `stage_order` INT         NOT NULL COMMENT '阶段序号，用于按链路顺序展示',
    -- ★ M5：嵌套深度。0 = 顶层阶段，1 = 嵌在某个顶层阶段里的子阶段
    --    （CODEGEN/REPAIR 嵌在 SANDBOX 里，VALIDATE_* 嵌在 VALIDATE 里）。
    --    没有这一列，端到端延迟 SQL 会把子阶段的耗时和父阶段的重复相加，
    --    算出来的延迟凭空翻倍——而它恰好还是一个「看起来合理」的数字。
    --    延迟口径因此收紧为 depth = 0，与 M3/M4 各轮完全可比。
    `depth`       INT         NOT NULL DEFAULT 0 COMMENT '嵌套深度，0=顶层',
    `state`       VARCHAR(16) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED',
    `summary`     VARCHAR(512)         DEFAULT NULL COMMENT '阶段结果摘要',
    `detail_json` JSON                 DEFAULT NULL COMMENT '阶段明细，结构随 stage_code 变化',
    `error_msg`   VARCHAR(1024)        DEFAULT NULL,
    `cost_millis` BIGINT               DEFAULT NULL COMMENT '阶段耗时',
    `start_time`  DATETIME(3) NOT NULL,
    `end_time`    DATETIME(3)          DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_task_order` (`task_code`, `stage_order`),
    KEY `idx_trace_id` (`trace_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='任务阶段链路追踪';


-- ────────────────────────────────────────────────────────────
-- 阶段 Token / 耗时账本（v2 §8.2，M5）
--
-- 为什么不把 Token 直接加两列塞进 task_stage：
--   task_stage 是「链路发生了什么」的流水，一个阶段进入几次就有几行；
--   而回答「这个任务在代码生成上花了多少 Token」要的是<b>按阶段收口的账</b>。
--   把账挂在流水上，每次统计都得先 GROUP BY，而分组口径写在各处 SQL 里
--   迟早会漂——尤其是 CODEGEN 这种一个任务里会进入很多次的阶段。
--
-- ★ 关键口径：total_tokens 记的是<b>本阶段自身</b>的消耗，不含子阶段。
--   所以 SUM(total_tokens) 就是任务总量，不会重复计算。
--   （SANDBOX 自身为 0，Token 全落在它的子阶段 CODEGEN / REPAIR 上。）
--
-- ★ usage_missing_calls：模型没回 usage 元数据的调用数。
--   缺了这一列，一次「模型没给 usage」和一次「真的没调模型」
--   在报表上长得一模一样，都是 0 —— 那就是静默降级（不变量 7）。
-- ────────────────────────────────────────────────────────────
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


-- ────────────────────────────────────────────────────────────
-- 知识库规则表 —— 本项目 RAG 的全部存储
--
-- 唯一键 (column_name, rule_type, scoring_system, version) 保证
-- 「同一列 + 同一规则类型」最多命中一条；命中多条即类型歧义，触发澄清。
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `data_agent_knowledge_rule`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `column_name`    VARCHAR(64)  NOT NULL COMMENT '规范列名，如 体温',
    `column_alias`   JSON                  DEFAULT NULL COMMENT '别名数组 ["T","temperature","TEMP"]',
    `rule_type`      VARCHAR(32)  NOT NULL COMMENT 'VALIDITY / SEVERITY_SCORING / TEXT_MAPPING',
    `scoring_system` VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'NEWS/MEWS/SEWS/CEWS/CART/AVPU，非评分规则填空串',
    `payload`        JSON         NOT NULL COMMENT '规则内容，结构随 rule_type 变化',
    `source_doc`     VARCHAR(128) NOT NULL COMMENT '来源文档名，引用标注用',
    `source_locator` VARCHAR(128) NOT NULL COMMENT '文档内定位，如 表2第3行',
    `version`        INT          NOT NULL DEFAULT 1 COMMENT '规则版本，临床共识会更新',
    `effective_flag` TINYINT      NOT NULL DEFAULT 1 COMMENT '1 生效 0 失效，规则不物理删除',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_col_type_sys_ver` (`column_name`, `rule_type`, `scoring_system`, `version`),
    KEY `idx_lookup` (`column_name`, `rule_type`, `effective_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识库规则条目';


-- ════════════════════════════════════════════════════════════
-- M1：评测体系
-- ════════════════════════════════════════════════════════════

-- 评测用例登记。
-- 用例的权威定义在 Python 侧 data_tools/eval_cases.py（因为要引用缺陷码），
-- 这张表是同步过来的快照，用于把评测结果关联到具体用例。
CREATE TABLE IF NOT EXISTS `data_agent_eval_case`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `case_id`       VARCHAR(32) NOT NULL COMMENT '如 L4-01',
    `level`         VARCHAR(24) NOT NULL COMMENT 'L1_RULE/L2_STRUCTURE/L3_JUDGMENT/L4_DISCOVERY',
    `requirement`   TEXT        NOT NULL COMMENT '医生的自然语言需求原文',
    `focus`         VARCHAR(512)         DEFAULT NULL COMMENT '考察点，归因时按它聚类',
    `defects_json`  JSON                 DEFAULT NULL COMMENT '该用例注入的缺陷配置',
    `expect_clarify` TINYINT    NOT NULL DEFAULT 0 COMMENT '是否应触发澄清；0 的用例检验过度澄清',
    `expect_proactive_report` JSON DEFAULT NULL COMMENT 'L4：用户没问但应主动报告的缺陷码',
    `sync_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_case_id` (`case_id`),
    KEY `idx_level` (`level`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评测用例目录快照';


-- 评测轮次。
-- ★ change_note 是 M4 收敛闭环的核心字段：
--   每一轮必须记录"改了什么"，否则指标变化无法归因。
CREATE TABLE IF NOT EXISTS `data_agent_eval_run`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `run_code`        VARCHAR(64) NOT NULL COMMENT '轮次编号',
    `dataset_seed`    INT         NOT NULL COMMENT '数据集种子，同 seed 保证可复现',
    `dataset_path`    VARCHAR(512)         DEFAULT NULL,
    `golden_path`     VARCHAR(512)         DEFAULT NULL,
    `model_name`      VARCHAR(64)          DEFAULT NULL COMMENT '本轮使用的模型',
    `change_note`     TEXT                 DEFAULT NULL COMMENT '★ 本轮改了什么，M4 归因的依据',
    `case_total`      INT         NOT NULL DEFAULT 0,
    `case_passed`     INT         NOT NULL DEFAULT 0,
    -- 指标快照
    `exec_success_rate`     DECIMAL(6, 4) DEFAULT NULL COMMENT '执行成功率',
    `result_correct_rate`   DECIMAL(6, 4) DEFAULT NULL COMMENT '结果正确率',
    `detect_rate_row`       DECIMAL(6, 4) DEFAULT NULL COMMENT '行级检出率',
    `detect_rate_dist`      DECIMAL(6, 4) DEFAULT NULL COMMENT '分布级检出率',
    -- 缺陷分类学实际是五层，不是三层。STRUCTURE / CLARIFY 各自独立：
    -- 前者是确定性统计推断，后者根本不该由系统下结论。塞进别的层会污染口径。
    -- 已建库的机器请跑 alter_m3_eval_run_levels.sql（本文件是 IF NOT EXISTS，对已有表不生效）。
    `detect_rate_structure` DECIMAL(6, 4) DEFAULT NULL COMMENT '结构级检出率',
    `detect_rate_sense`     DECIMAL(6, 4) DEFAULT NULL COMMENT '常识级检出率',
    `detect_rate_clarify`   DECIMAL(6, 4) DEFAULT NULL COMMENT '待澄清级检出率：检出=触发了对应澄清',
    `spec_compliance_rate`  DECIMAL(6, 4) DEFAULT NULL COMMENT '规范遵从率：阈值来自知识库而非编造',
    `clarify_precision`     DECIMAL(6, 4) DEFAULT NULL COMMENT '澄清恰当率（双向计分）',
    `discovery_rate`        DECIMAL(6, 4) DEFAULT NULL COMMENT '问题发现率：L4 主动检出',
    `self_repair_rate`      DECIMAL(6, 4) DEFAULT NULL COMMENT '自修复成功率',
    `token_total`           BIGINT        DEFAULT NULL,
    `latency_p50_millis`    BIGINT        DEFAULT NULL,
    `latency_p95_millis`    BIGINT        DEFAULT NULL,
    `start_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `end_time`        DATETIME             DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_code` (`run_code`),
    KEY `idx_start_time` (`start_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评测轮次与指标快照';


-- 单用例结果。失败 case 的归因分析基于这张表。
CREATE TABLE IF NOT EXISTS `data_agent_eval_result`
(
    `id`               BIGINT      NOT NULL AUTO_INCREMENT,
    `run_code`         VARCHAR(64) NOT NULL,
    `case_id`          VARCHAR(32) NOT NULL,
    `task_code`        VARCHAR(64)          DEFAULT NULL COMMENT '关联 data_agent_task，可回放完整链路',
    `exec_success`     TINYINT     NOT NULL DEFAULT 0 COMMENT '代码是否跑通',
    `result_correct`   TINYINT     NOT NULL DEFAULT 0 COMMENT '输出是否与 golden 一致',
    `clarify_expected` TINYINT     NOT NULL DEFAULT 0,
    `clarify_actual`   TINYINT     NOT NULL DEFAULT 0,
    `detected_json`    JSON                 DEFAULT NULL COMMENT '系统实际检出的缺陷码',
    `missed_json`      JSON                 DEFAULT NULL COMMENT '漏检的缺陷码',
    `false_alarm_json` JSON                 DEFAULT NULL COMMENT '误报的缺陷码',
    `repair_attempts`  INT         NOT NULL DEFAULT 0 COMMENT '自修复重试次数',
    `token_used`       INT                  DEFAULT NULL,
    `cost_millis`      BIGINT               DEFAULT NULL,
    `failure_category` VARCHAR(64)          DEFAULT NULL COMMENT '★ 失败归因分类，M4 按它聚类',
    `failure_detail`   TEXT                 DEFAULT NULL,
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_case` (`run_code`, `case_id`),
    KEY `idx_failure_category` (`failure_category`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='单用例评测结果';


-- ════════════════════════════════════════════════════
-- M2：最小链路 Profiler → Planner → Executor → Validator
--
-- 七张表按「谁产生它」划分：
--   dataset / column_profile          ← ProfilerAgent
--   plan / plan_step / clarification  ← PlannerAgent
--   execution                         ← ExecutorAgent
--   validation_finding                ← ValidatorAgent
-- 这样任意一条记录都能直接回答「它是哪个 Agent 的产出」，
-- M4 归因时不需要靠 task_code 反推。
-- ════════════════════════════════════════════════════

-- 数据集登记。同一份 parquet 可被多个任务引用，所以单独成表。
CREATE TABLE IF NOT EXISTS `data_agent_dataset`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `dataset_code`   VARCHAR(64)  NOT NULL COMMENT '数据集编号',
    `dataset_path`   VARCHAR(512) NOT NULL COMMENT 'parquet 路径',
    `golden_path`    VARCHAR(512)          DEFAULT NULL COMMENT '评测数据才有',
    `row_count`      BIGINT       NOT NULL DEFAULT 0,
    `column_count`   INT          NOT NULL DEFAULT 0,
    `source`         VARCHAR(32)  NOT NULL DEFAULT 'SYNTHETIC' COMMENT 'SYNTHETIC/UPLOAD',
    `profile_millis` BIGINT                DEFAULT NULL COMMENT '画像耗时，M3 延迟指标的组成部分',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dataset_code` (`dataset_code`),
    KEY `idx_dataset_path` (`dataset_path`(191))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='数据集登记';


-- 列画像快照。
--
-- 为什么要落库而不是只放内存：常识级校验要比对「处理前 vs 处理后」，
-- 而处理可能跨越一次澄清中断（医生第二天才回来回答）。
-- 不落库，中断恢复后就没有对照基准了。
CREATE TABLE IF NOT EXISTS `data_agent_column_profile`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `task_code`        VARCHAR(64)   NOT NULL,
    `dataset_code`     VARCHAR(64)   NOT NULL,
    `phase`            VARCHAR(16)   NOT NULL COMMENT 'BEFORE / AFTER，处理前后各存一份',
    `column_name`      VARCHAR(128)  NOT NULL,
    `dtype`            VARCHAR(32)   NOT NULL,
    `missing_rate`     DECIMAL(9, 6) NOT NULL DEFAULT 0,
    `distinct_count`   BIGINT        NOT NULL DEFAULT 0,
    `numeric_json`     JSON                   DEFAULT NULL COMMENT 'min/max/mean/p50/p95',
    `text_values_json` JSON                   DEFAULT NULL COMMENT '低基数列的取值枚举；高基数列为 NULL',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_phase_column` (`task_code`, `phase`, `column_name`),
    KEY `idx_dataset` (`dataset_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='列画像快照';


-- 处理方案（PlannerAgent 产出）
CREATE TABLE IF NOT EXISTS `data_agent_plan`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `plan_code`     VARCHAR(64) NOT NULL,
    `task_code`     VARCHAR(64) NOT NULL,
    `summary`       VARCHAR(1024)        DEFAULT NULL COMMENT '给医生看的一句话方案说明',
    `status`        VARCHAR(24) NOT NULL COMMENT 'DRAFT/CLARIFYING/CONFIRMED/EXECUTED/FAILED',
    `step_count`    INT         NOT NULL DEFAULT 0,
    `clarify_count` INT         NOT NULL DEFAULT 0,
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plan_code` (`plan_code`),
    KEY `idx_task_code` (`task_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='处理方案';


-- 方案步骤。
-- depends_on 在 M2 顺序执行下用不上，但字段先留着：
-- 加字段容易，改已有数据的语义难。
CREATE TABLE IF NOT EXISTS `data_agent_plan_step`
(
    `id`             BIGINT      NOT NULL AUTO_INCREMENT,
    `plan_code`      VARCHAR(64) NOT NULL,
    `step_no`        INT         NOT NULL COMMENT '执行序号，从 1 开始',
    `action`         VARCHAR(64) NOT NULL COMMENT 'DEDUPLICATE / NULLIFY_OUT_OF_RANGE / NORMALIZE_TEXT / ...',
    `target_columns` JSON                 DEFAULT NULL,
    `description`    VARCHAR(1024)        DEFAULT NULL COMMENT '给医生看的步骤说明',
    `rule_ids`       JSON                 DEFAULT NULL COMMENT '该步骤引用的知识库规则 ID；空数组表示无依据',
    `depends_on`     JSON                 DEFAULT NULL COMMENT '依赖的前序 step_no，为 DAG 化预留',
    `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plan_step` (`plan_code`, `step_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='方案步骤';


-- 澄清项。
--
-- ★ coverage_ratio 是本表最关键的字段：提问按它降序排。
-- 依据是 v1「即使医生中途停止回答，也已覆盖绝大部分数据」——
-- 先问覆盖 33% 数据的那个取值、再问覆盖 0.4% 的，
-- 问到第三个就走开的医生，拿到的结果依然可用。
CREATE TABLE IF NOT EXISTS `data_agent_clarification`
(
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `clarify_code`    VARCHAR(64)   NOT NULL,
    `task_code`       VARCHAR(64)   NOT NULL,
    `plan_code`       VARCHAR(64)            DEFAULT NULL,
    `level`           VARCHAR(16)   NOT NULL COMMENT 'HIGH 自动决定 / MEDIUM 合并提问 / LOW 必须逐个追问',
    `topic`           VARCHAR(128)  NOT NULL COMMENT '澄清主题，用于与 golden 的 expectClarifications 比对',
    `question`        VARCHAR(1024) NOT NULL COMMENT '给医生看的问题措辞',
    `options_json`    JSON                   DEFAULT NULL COMMENT '候选答案，尽量让医生做选择题而不是问答题',
    `column_name`     VARCHAR(128)           DEFAULT NULL,
    `coverage_ratio`  DECIMAL(9, 6) NOT NULL DEFAULT 0 COMMENT '该决策点覆盖的数据占比，提问排序依据',
    `evidence`        VARCHAR(1024)          DEFAULT NULL COMMENT '为什么要问，来自画像的确定性证据',
    `source_rule_ids` JSON                   DEFAULT NULL COMMENT '命中多条规则触发的歧义澄清，记录冲突规则',
    `answer`          VARCHAR(1024)          DEFAULT NULL,
    `answered_at`     DATETIME               DEFAULT NULL,
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_clarify_code` (`clarify_code`),
    KEY `idx_task_coverage` (`task_code`, `coverage_ratio`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='澄清项';


-- 执行记录。
-- retry_no > 0 且 success = 1 就是一次成功的自修复，
-- M3 的自修复成功率直接从这两个字段统计。
CREATE TABLE IF NOT EXISTS `data_agent_execution`
(
    `id`               BIGINT      NOT NULL AUTO_INCREMENT,
    `task_code`        VARCHAR(64) NOT NULL,
    `plan_code`        VARCHAR(64)          DEFAULT NULL,
    `step_no`          INT         NOT NULL DEFAULT 1,
    `retry_no`         INT         NOT NULL DEFAULT 0 COMMENT '0 首次生成，>0 自修复',
    `code`             MEDIUMTEXT  NOT NULL COMMENT '本次执行的完整代码，保证可复现',
    `input_path`       VARCHAR(512)         DEFAULT NULL,
    `output_path`      VARCHAR(512)         DEFAULT NULL,
    `success`          TINYINT     NOT NULL DEFAULT 0,
    `blocked_reason`   VARCHAR(512)         DEFAULT NULL COMMENT '非空表示被静态检查拦截，代码根本没执行',
    `blocked_detail`   JSON                 DEFAULT NULL,
    `exit_code`        INT                  DEFAULT NULL,
    `stdout`           MEDIUMTEXT           DEFAULT NULL,
    `stderr`           MEDIUMTEXT           DEFAULT NULL,
    `timed_out`        TINYINT     NOT NULL DEFAULT 0,
    `output_row_count` BIGINT               DEFAULT NULL,
    `cost_millis`      BIGINT               DEFAULT NULL,
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_task_step_retry` (`task_code`, `step_no`, `retry_no`),
    KEY `idx_success` (`success`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='沙箱执行记录';


-- 校验发现。
--
-- ★ level 是分层检出率的直接来源，source_rule_id 是规范遵从率的直接来源。
-- 这两个指标在 M3 各是一条 SQL 就能算出来，全靠这两个字段——
-- 「每个指标都有确定的表和字段来源」正是 M3 能在 2h 内跑完基线的前提。
CREATE TABLE IF NOT EXISTS `data_agent_validation_finding`
(
    `id`             BIGINT      NOT NULL AUTO_INCREMENT,
    `task_code`      VARCHAR(64) NOT NULL,
    `phase`          VARCHAR(16) NOT NULL DEFAULT 'AFTER' COMMENT 'BEFORE 画像阶段发现 / AFTER 处理后校验发现',
    `level`          VARCHAR(16) NOT NULL COMMENT 'ROW / DISTRIBUTION / STRUCTURE / COMMON_SENSE / CLARIFY',
    `code`           VARCHAR(64) NOT NULL COMMENT '与 defects.py 缺陷码同命名空间，M3 可直接比对 golden',
    `column_name`    VARCHAR(128)         DEFAULT NULL,
    `severity`       VARCHAR(16) NOT NULL DEFAULT 'WARN' COMMENT 'INFO / WARN / ERROR',
    `evidence`       VARCHAR(1024)        DEFAULT NULL COMMENT '人类可读的证据',
    `affected_rows`  BIGINT      NOT NULL DEFAULT 0,
    `metric`         DECIMAL(18, 6)       DEFAULT NULL COMMENT '触发判定的统计量',
    `source_rule_id` VARCHAR(64)          DEFAULT NULL COMMENT '依据的知识库规则；空表示无规范依据',
    `source_doc`     VARCHAR(128)         DEFAULT NULL,
    `source_locator` VARCHAR(128)         DEFAULT NULL,
    `sample_json`    JSON                 DEFAULT NULL COMMENT '违规样例（行号+值）。仅入库与报告，不进 Prompt',
    `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_task_phase_level` (`task_code`, `phase`, `level`),
    KEY `idx_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='三层校验发现';
