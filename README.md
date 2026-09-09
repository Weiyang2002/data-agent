# dataAgent-临床数据处理agent

面向临床研究者的数据处理 Agent：医生用自然语言描述需求，系统理解数据结构、检索
院内规范、生成并沙箱执行 Python 脚本、三层校验结果，并对需要临床判断的决策点主动
澄清。


---

## 架构

三个部分，边界清晰：

| 部分 | 职责 | 技术栈 |
|---|---|---|
| **Java 主服务**（`data-agent-business` + `data-agent-common`） | 编排主链路：会话状态、Agent 调度、知识库检索、澄清判定、可观测埋点、结果归档 | Spring Boot 3.5.6 · Spring AI 1.1.0 · spring-ai-alibaba-agent-framework 1.1.2.0 · MyBatis-Plus · MySQL 8 · JDK 17 |
| **Python 工具服务**（`data-agent-tools`） | 确定性工具箱：数据画像、沙箱执行、规则校验、缺陷注入。不做任何业务决策 | FastAPI · pandas · pyarrow· Python 3.11+ |
| **演示前端**（`data-agent-web`） | 可视化界面 | Vue 3 · Vite（无 UI 组件库 / 图表库 / 状态管理库） |

链路：`Profiler → Planner → Executor → Validator`，四个 Agent 顺序调用。需澄清时
无歧义步骤照跑，某步失败停下如实报告，任何降级都在日志或自检里显式喊出。

---

## 目录结构

```
data-agent/
├── data-agent-common/     统一返回体、业务异常、状态码
├── data-agent-business/    主服务
│   └── src/main/java/org/dataagent/clean/
│       ├── pipeline/agent/         四个 Agent + 零框架依赖的接口
│       ├── pipeline/config/        Agent 装配（允许框架依赖）
│       ├── pipeline/executor/      链路执行器 + 有界自修复引擎（允许框架依赖）
│       ├── pipeline/knowledge/     知识库检索（SQL 精确查询）
│       ├── pipeline/validate/      三层校验编排
│       ├── pipeline/eval/          评测 Runner、评分器、指标聚合
│       ├── pipeline/observability/ @TraceStage 注解 + AOP 埋点
│       └── prompt/                 Prompt 模板服务（模板在 resources/prompt/*.st）
├── data-agent-tools/      Python 工具服务
│   └── data_tools/
│       ├── profiling.py       数据画像 + 确定性异常检出
│       ├── sandbox.py         沙箱宿主：静态检查 → 子进程 → 超时兜底
│       ├── static_check.py    AST 静态检查（沙箱第一道防线）
│       ├── defects.py         缺陷注入器
│       ├── dataset_generate.py 合成数据 + 缺陷注入 + golden 产出
│       └── validation.py      行级 / 分布级确定性校验
├── data-agent-web/        前端
├── sql/                   建库脚本 + 知识库种子 + 增量迁移
├── scripts/               check-framework-leak.sh
└── doc/                   设计文档、学习文档、进度、评测轮次记录
```

---

## 关于本仓库未包含的文件

因为项目仍在持续开发，以下内容被 `.gitignore` 排除，**目前不在本仓库中**，但完整跑通这个项目
需要它们：

| 路径 | 内容 | 不包含的影响 |
|---|---|---|
| `data-agent-business/src/main/resources/prompt/*.st` | 9 个 Prompt 模板（列名抽取、代码生成、常识校验等） | `PromptTemplateService` 在启动时从 classpath 加载这些文件，**缺失会导致服务无法正常处理任务**——需要自行编写对应模板 |

真实密钥（`application-local.yaml`）同样被排除。

---

## 快速开始

### 前置

- JDK 17、MySQL 8（`data_agent` 库）、Python 3.11+
- 一个 DeepSeek 或智谱兼容的模型 API Key

### 1. 建库 + 导入知识库种子

```bash
mysql -uroot -p < sql/schema/mysql/create_database.sql
mysql -uroot -p data_agent < sql/schema/mysql/create_table.sql
mysql -uroot -p data_agent < sql/data/knowledge_rule_seed.sql
# 已建库的机器补两份增量（create_table.sql 对已有表不生效）
mysql -uroot -p data_agent < sql/schema/mysql/alter_m3_eval_run_levels.sql
mysql -uroot -p data_agent < sql/schema/mysql/alter_m5_observability.sql
```

### 2. 本地配置

复制模板并填入真实值：

```bash
cp data-agent-business/src/main/resources/application-local.yaml.example \
   data-agent-business/src/main/resources/application-local.yaml
```

需要配置：MySQL 密码、模型 API Key 与 base-url。主 `application.yaml` 只有占位符。

### 3. 启动 Python 工具服务（端口 18081）

```bash
cd data-agent-tools
pip install -e .
python -m uvicorn data_tools.main:app --host 127.0.0.1 --port 18081
```

### 4. 启动 Java 主服务（端口 9090）

```bash
mvn -pl data-agent-common,data-agent-business install -DskipTests
java -jar data-agent-business/target/data-agent-business-0.0.1-SNAPSHOT.jar
```

> 重新打包前先停掉旧的 java 进程，否则 `spring-boot:repackage` 会报
> `Unable to rename ... to ....jar.original`。

### 5. 验收

```bash
curl http://localhost:9090/api/smoke/all              # 容器 / 模板 / Python / 模型四项
curl http://localhost:9090/api/task/knowledge/count   # 知识库规模，应返回非 0
bash scripts/check-framework-leak.sh                   # 框架隔离校验
cd data-agent-tools && python -m pytest tests -q       # Python 单测
```

> 本机若有全局代理，`curl` 需加 `--noproxy '*'`，否则 localhost 请求会被代理成空
> 503。

### 6. 前端

```bash
cd data-agent-web
npm install
npm run dev        # http://localhost:5173，需后端先起
```

---

## 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/task/run` | 发起一次完整处理：画像 → 规划 → 执行 → 校验 |
| `GET` | `/api/task/{taskCode}/trace` | 完整阶段链路 + Token 按阶段分布 |
| `GET` | `/api/task/knowledge/probe` | 知识库检索探针，一次看到 RESOLVED / AMBIGUOUS / NO_EVIDENCE 三态 |
| `POST` | `/api/eval/run` | 发起一轮评测（长请求，`changeNote` 必填） |
| `GET` | `/api/eval/runs` | 全部评测轮次，用于查看指标序列 |
| `GET` | `/api/eval/run/{runCode}` | 单轮指标快照 + 全部用例结果 |
| `GET` | `/api/smoke/all` | 启动自检 |

---



