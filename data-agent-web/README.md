# data-agent-web

临床研究数据处理 Agent 的演示前端。**只读后端已有接口，不改一行 Java。**

- 设计与「为什么这么做」：`../doc/前端-设计文档.md`
- 状态与下一步（唯一权威）：`../doc/进度/前端开发进度.md`

---

## 跑起来

前端只读后端，**后端必须先起着**（Java 9090 + Python 18081 + MySQL）。
起后端的命令在仓库根的 `CLAUDE.md` 第 7 节。

```bash
cd data-agent-web
npm install        # 首次
npm run dev        # → http://localhost:5173
npm run build      # 产物在 dist/，可直接丢进 Spring 的 static/（用的是 hash 路由）
```

`/api` 由 vite dev-server 反向代理到 `127.0.0.1:9090`，超时设成 30 分钟。
**不给后端加 CORS**：为一个演示页面去动生产服务的安全边界，代价不对等；
代理只活在开发机上，跟 vite 一起消失。

> 本机有全局代理（`HTTP_PROXY=http://127.0.0.1:15236`）会把 localhost 请求变成空 503。
> vite proxy 基于 node http、默认不读环境变量，所以不受影响 —— 但哪天换了实现，先怀疑这一条。
> 用 curl 验证后端时必须加 `--noproxy '*'`。

## 六个页面

| 路由 | 页面 | 看什么 |
|---|---|---|
| `/` | 总览 | 系统自检（点击才跑）/ 知识库规模 / 最近一轮指标 / 项目边界 |
| `/knowledge` | 知识库检索 | ★ 招牌：RESOLVED / AMBIGUOUS / NO_EVIDENCE 三态一屏 |
| `/task` | 处理任务 | 造数据 → 提需求 → 跑链路 → 画像·方案·澄清·校验·执行 |
| `/trace/:taskCode` | 链路与 Token | 阶段流水 + Token 按阶段 + ★ 三条闭合性质 |
| `/eval` | 评测序列 | M3 基线 → M4 四轮 → M5 复现的收敛曲线 + 轮次表 |
| `/eval/:runCode` | 评测报告 | 单轮指标 + 分层检出率 + 归因 + 32 个用例（可钻进 trace） |

钻取链路：`/eval/:runCode` 的用例表每行 `taskCode` → `/trace/:taskCode`。
这条链路替代了「任务列表」接口 —— 评测轮次本来就是任务的最大来源。

## 目录

```
src/
  main.js           hash 路由 + 6 条路由
  App.vue           顶栏（导航 / 后端心跳 / 主题三态）
  api/client.js     ApiResponse 解包，code !== "0" 抛 ApiError
  constants.js      口径常量（层级 / 动作词表 / 指标口径文案）
  styles/theme.css  设计令牌，浅深两套完整定义
  components/       MetricValue · StatusBadge · Caveats · BarMeter · LineChart
  views/            Overview · Knowledge · TaskRun · TaskTrace · EvalRuns · EvalReport
```

无 UI 组件库、无图表库、无状态管理库、无 axios。
运行时依赖只有 `vue` 和 `vue-router`。

## 贯穿全站的四条渲染原则

这四条不是 UI 偏好，是把后端已经守住的口径在展示层继续守住。
每一条都对应进度文档里已经付过代价的一个坑。

1. **`null` ≠ `0` ≠ 不存在。** 指标数值一律走 `MetricValue.vue`：
   `null` → 灰色斜体「未测」+ 悬停说明分母为 0；`0` → 正常渲染 `0.00%`。
   折线图 `null` 处断线，不连 0。后端为此在三个 VO 上局部覆盖了 `@JsonInclude(ALWAYS)`，
   前端把 `null` 画成 0，那三处覆盖就白做了。
2. **caveats 置顶，不可折叠。** 降级要出现在结果里，不是日志里。
   折叠、放页尾、做成小灰字，都是把它变回日志。
   **历史轮次拿不到 caveats（不落库）→ 显示「未落库」，绝不显示成空列表。**
3. **「查了没问题」与「压根没查」分开。** 处理任务页把三层校验画成执行矩阵，
   放在 findings 列表**上面**；findings 为空时，矩阵是唯一能区分两种情况的东西。
4. **排序是业务判断，前端不重排。** 澄清项的 `coverageRatio` 降序、阶段的 `stageOrder`、
   轮次的时间倒序，都是后端定的。需要换序时给的是**筛选**不是排序 ——
   一旦允许点表头排序，「医生从上往下答」的产品语义就没了。

## 页面上如实标注的几处「做不到」

不藏着，因为藏起来的代价是别人以为它能用：

- **澄清答复不能回传** —— 后端 `CLARIFY_RESUME` 枚举值留好了但接口没写。
  页面上标注，**不做假输入框**。
- **`metrics[].note` 口径文案与 `caveats` 不落库** —— 完整报告 VO 只有
  `POST /api/eval/run` 返回。口径文案由前端内置一份并标注来源；caveats 显示「未落库」。
- **失败归因直方图由前端从 `results[].failureCategory` 聚合**，不是后端快照，页面上注明。
- **没有文件上传** —— `datasetPath` 是服务端本地路径。
- **没有「发起评测」按钮** —— 那是十几分钟、约 28 万 Token 的实验且 `changeNote` 必填，
  做成按钮等于把一次严肃实验降格成一次点击。页面上放 curl 命令。

## 收工检查

```bash
npm run build      # 编译
npm run dev        # 六个页面逐个点开，对着真后端
```

- [ ] 断开后端，页面给的是人话（「连不上后端，确认 java -jar 已启动在 9090」），不是 `Failed to fetch`
- [ ] 深色模式逐页看一遍（顶栏第三个按钮切三态）
- [ ] 窗口拉窄到 900px，表格横向滚动而不是撑破页面
- [ ] 找一个 `null` 指标，确认显示「未测」不是 0
- [ ] 找一个 `caveats` 非空的响应，确认它在页面顶部
- [ ] 折线图有 `null` 的那一段，确认是断的不是连到 0
