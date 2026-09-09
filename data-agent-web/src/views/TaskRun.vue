<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import StatusBadge from '../components/StatusBadge.vue'
import { CASE_LEVELS, FINDING_LEVELS, LEVEL_LABEL, PLAN_ACTIONS } from '../constants'

/**
 * 处理任务页：造数据 → 提需求 → 跑链路 → 看结果。页面按 CleanTaskResultVO 的四段式
 * 排（画像 / 方案 / 澄清 / 校验），不做成「成功 / 失败」的结论。
 */

// ── [1] 数据集 ──
// datasetPath 是服务端本地路径，不是上传，页面上需说清楚。
const DATASET_KEY = 'da-dataset-path'
const datasetForm = ref({ rows: 20000, patients: 1200, seed: 42 })
const dataset = ref({ data: null, error: null, loading: false })
const datasetPath = ref(localStorage.getItem(DATASET_KEY) || '')

function rememberPath(path) {
  datasetPath.value = path
  localStorage.setItem(DATASET_KEY, path)
}

async function generateDataset() {
  dataset.value = { data: null, error: null, loading: true }
  try {
    const data = await api.fullDataset(datasetForm.value)
    dataset.value.data = data
    rememberPath(data.datasetPath)
  }
  catch (error) { dataset.value.error = error.message }
  finally { dataset.value.loading = false }
}

// ── [2] 需求 ──
const requirement = ref('')
const cases = ref({ data: null, error: null })
const selectedCase = ref('')

const groupedCases = computed(() => {
  const all = cases.value.data?.cases ?? []
  return CASE_LEVELS
    .map(level => ({ ...level, items: all.filter(item => item.level === level.code) }))
    .filter(group => group.items.length > 0)
})

const currentCase = computed(() =>
  (cases.value.data?.cases ?? []).find(item => item.caseId === selectedCase.value) || null)

function applyCase() {
  if (currentCase.value) requirement.value = currentCase.value.requirement
}

// ── [3] 跑链路 ──
// 前端不设超时（避免慢用例假失败），给一个可以主动取消的按钮。
const task = ref({ data: null, error: null, loading: false })
const elapsed = ref(0)
let controller = null
let timer = null

async function runTask() {
  controller = new AbortController()
  task.value = { data: null, error: null, loading: true }
  elapsed.value = 0
  timer = setInterval(() => { elapsed.value += 1 }, 1000)
  try {
    const data = await api.runTask(requirement.value.trim(), datasetPath.value.trim(), controller.signal)
    task.value.data = data
    rememberTask(data)
  }
  catch (error) {
    task.value.error = error.name === 'AbortError'
      ? '已取消。注意后端那一侧仍会把这次链路跑完，taskCode 之后可在评测报告里找到'
      : error.message
  }
  finally {
    task.value.loading = false
    clearInterval(timer)
    controller = null
  }
}

function cancelTask() { controller?.abort() }

// ── 最近跑过的任务 ──
// 后端没有「任务列表」接口，localStorage 存最近 8 个 taskCode，可顺着
// /trace/:taskCode 找回刚跑完的任务。全量任务走评测报告页钻取。
const TASKS_KEY = 'da-recent-tasks'
const recentTasks = ref([])

function loadRecent() {
  try { recentTasks.value = JSON.parse(localStorage.getItem(TASKS_KEY) || '[]') }
  catch { recentTasks.value = [] }
}

function rememberTask(data) {
  if (!data?.taskCode) return
  const item = {
    taskCode: data.taskCode,
    status: data.status,
    requirement: requirement.value.trim().slice(0, 40),
    at: new Date().toLocaleString('zh-CN')
  }
  const list = [item, ...recentTasks.value.filter(row => row.taskCode !== item.taskCode)].slice(0, 8)
  recentTasks.value = list
  localStorage.setItem(TASKS_KEY, JSON.stringify(list))
}

// ── 结果的几处口径 ──

/**
 * 需要临床依据的动作却拿不出 ruleIds 是异常，要标出来。纯工程动作空 ruleIds 正常。
 */
function evidenceState(step) {
  const spec = PLAN_ACTIONS[step.action]
  if ((step.ruleIds ?? []).length > 0) return { kind: 'ok', text: step.ruleIds.join(', ') }
  if (spec?.needsEvidence && step.executed) {
    return { kind: 'bad', text: '缺依据（本动作需要 ' + spec.ruleType + '）' }
  }
  if (spec?.needsEvidence) return { kind: 'pending', text: '无依据，未执行' }
  return { kind: 'none', text: '纯工程动作，无需依据' }
}

const validationMatrix = computed(() => {
  const executed = task.value.data?.validationExecuted ?? {}
  const skip = task.value.data?.validationSkipReason ?? {}
  return FINDING_LEVELS.map(level => {
    const ran = executed[level.code] === true
    const count = findingsOf(level.code).length
    return {
      ...level,
      executed: ran,
      skipReason: skip[level.code] || '',
      count,
      // 「没查」却有该层的发现 —— 两个字段自相矛盾（会在 CLARIFY 层出现）。
      //   这里把矛盾标出来，而不是把「没查」渲染成「查过」。
      contradiction: !ran && count > 0
    }
  })
})

function findingsOf(level) {
  return (task.value.data?.findings ?? []).filter(item => item.level === level)
}

function ratioText(value) {
  return value === null || value === undefined ? '—' : (value * 100).toFixed(2) + '%'
}

function intText(value) {
  return value === null || value === undefined ? '—' : value.toLocaleString('zh-CN')
}

onMounted(async () => {
  loadRecent()
  try { cases.value.data = await api.evalCases() }
  catch (error) { cases.value.error = error.message }
})
</script>

<template>
  <div class="stack">
    <!-- [1] 数据集 -->
    <div class="card">
      <div class="card-head">
        <div>
          <h1>处理任务</h1>
          <p class="secondary" style="margin: 6px 0 0; max-width: 78ch;">
            医生用自己的话描述需求，系统画像 → 规划 → 沙箱执行 → 三层校验。
            <strong>需要临床判断的地方它会问，而不是替你决定。</strong>
          </p>
        </div>
      </div>

      <h2 class="sec-first">① 数据集</h2>
      <p class="small muted" style="margin: 4px 0 12px;">
        ⚠ <code>datasetPath</code> 是<strong>服务端本地路径</strong>，不是上传。
        先按下面的参数生成一份，或直接手填服务端上已有的 parquet 路径。
      </p>

      <div class="row" style="align-items: flex-end;">
        <label class="field" style="flex: 0 0 130px;">
          <span>行数 rows</span>
          <input type="number" v-model.number="datasetForm.rows" />
        </label>
        <label class="field" style="flex: 0 0 140px;">
          <span>患者数 patients</span>
          <input type="number" v-model.number="datasetForm.patients" />
        </label>
        <label class="field" style="flex: 0 0 110px;">
          <span>seed</span>
          <input type="number" v-model.number="datasetForm.seed" />
        </label>
        <button class="primary" :disabled="dataset.loading" @click="generateDataset">
          {{ dataset.loading ? '生成中…' : '生成全缺陷数据集' }}
        </button>
        <span class="small muted">默认 20000 / 1200 / 42</span>
      </div>

      <div v-if="dataset.error" class="error" style="margin-top: 12px;">{{ dataset.error }}</div>

      <div v-if="dataset.data" style="margin-top: 14px;">
        <div class="kv small">
          <div><span class="muted">行数</span> <strong class="tnum">{{ intText(dataset.data.rowCount) }}</strong></div>
          <div><span class="muted">seed</span> <strong class="tnum">{{ dataset.data.seed }}</strong></div>
          <div v-if="dataset.data.baseline">
            <span class="muted">注入前人均入院</span>
            <strong class="tnum">{{ dataset.data.baseline.admissionsPerPatient?.toFixed(2) }}</strong>
            <span class="muted small">
              （患者 {{ dataset.data.baseline.patientCount }} / 入院 {{ dataset.data.baseline.admissionCount }}；
              列错位缺陷会把它塌成 1.00，那就是常识级要抓的东西）
            </span>
          </div>
        </div>
        <p class="small muted" style="margin: 8px 0 0;">
          golden：<code>{{ dataset.data.goldenPath }}</code>
        </p>

        <details style="margin-top: 10px;">
          <summary class="small">注入的 {{ dataset.data.injected?.length || 0 }} 类缺陷（评分的判据来源）</summary>
          <div class="scroll-x" style="margin-top: 8px;">
            <table class="data">
              <thead>
                <tr>
                  <th>缺陷码</th><th>层级</th><th class="num">影响行数</th>
                  <th>列</th><th>应检出的信号</th><th>用户会提吗</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in dataset.data.injected" :key="item.code">
                  <td><code>{{ item.code }}</code></td>
                  <td>{{ LEVEL_LABEL[item.expectLevel] || item.expectLevel }}</td>
                  <td class="num tnum">{{ intText(item.affectedRows) }}</td>
                  <td class="small">{{ (item.columns || []).join('、') }}</td>
                  <td class="small secondary">{{ item.expectSignal }}</td>
                  <td>
                    <StatusBadge :kind="item.userAsked ? 'YES' : 'NO'"
                                 :text="item.userAsked ? '会提' : '不会提 · L4 发现类'" />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </details>
      </div>

      <label class="field" style="margin-top: 16px;">
        <span>datasetPath（服务端路径，可手填；已记住上次用的）</span>
        <input type="text" :value="datasetPath" @input="rememberPath($event.target.value)"
               placeholder="生成一份，或手填服务端上已有的 parquet 路径" />
      </label>
    </div>

    <!-- [2] 需求 -->
    <div class="card">
      <div class="card-head">
        <h2>② 需求</h2>
        <span class="hint">医生的原话，口语化的，不需要先整理成规格</span>
      </div>

      <div class="row" style="align-items: flex-end;">
        <label class="field grow" style="max-width: 560px;">
          <span>从 32 个评测用例里挑一条（可选）</span>
          <select v-model="selectedCase" @change="applyCase">
            <option value="">—— 自己写 ——</option>
            <optgroup v-for="group in groupedCases" :key="group.code" :label="group.label">
              <option v-for="item in group.items" :key="item.caseId" :value="item.caseId">
                {{ item.caseId }} · {{ item.requirement }}
              </option>
            </optgroup>
          </select>
        </label>
        <span v-if="cases.error" class="error small">{{ cases.error }}</span>
      </div>

      <div v-if="currentCase" class="case-meta small">
        <span class="muted">考察点</span> {{ currentCase.focus }}
        <span class="sep">·</span>
        <span class="muted">应触发澄清</span>
        <StatusBadge :kind="currentCase.expectClarify ? 'YES' : 'NO'" />
        <template v-if="currentCase.expectProactiveReport?.length">
          <span class="sep">·</span>
          <span class="muted">应主动报告</span> <code>{{ currentCase.expectProactiveReport.join(', ') }}</code>
        </template>
      </div>

      <label class="field" style="margin-top: 12px;">
        <span>需求原文</span>
        <textarea v-model="requirement" rows="3"
                  placeholder="例：把完全重复的记录去掉 ／ 体温有明显不合理的值，帮我处理一下"></textarea>
      </label>

      <div class="row" style="margin-top: 12px;">
        <button class="primary" :disabled="task.loading || !requirement.trim() || !datasetPath.trim()"
                @click="runTask">
          {{ task.loading ? '跑链路中… ' + elapsed + 's' : '开始处理' }}
        </button>
        <button v-if="task.loading" class="ghost" @click="cancelTask">取消</button>
        <span class="small muted">
          实测 8–30 秒（画像 / 规划 / 代码生成 / 沙箱执行 / 三层校验，其中 4 次模型调用）
        </span>
      </div>

      <div v-if="task.error" class="error" style="margin-top: 12px;">{{ task.error }}</div>
    </div>

    <!-- [3] 结果 -->
    <div v-if="task.data" class="card">
      <div class="card-head">
        <div class="row" style="gap: 12px;">
          <h2>③ 结果</h2>
          <StatusBadge :kind="task.data.status" />
          <code class="small">{{ task.data.taskCode }}</code>
        </div>
        <RouterLink :to="'/trace/' + task.data.taskCode">查看链路与 Token →</RouterLink>
      </div>

      <div v-if="task.data.failReason" class="error" style="margin-bottom: 12px;">
        {{ task.data.failReason }}
      </div>

      <p v-if="task.data.status === 'CLARIFYING'" class="note-clarify small secondary">
        状态是 <code>CLARIFYING</code>：有需要临床判断的决策点等待答复。
        下面「方案」里未执行的步骤都附了原因，<strong>是正确行为，不是失败</strong>。
      </p>

      <!-- ① 画像 -->
      <h3 class="sec">① 画像</h3>
      <div class="kv small">
        <div><span class="muted">输入行数</span> <strong class="tnum">{{ intText(task.data.inputRowCount) }}</strong></div>
        <div><span class="muted">列数</span> <strong class="tnum">{{ task.data.columnCount ?? '—' }}</strong></div>
      </div>
      <p v-if="task.data.profileNarrative" class="narrative">{{ task.data.profileNarrative }}</p>
      <p v-else class="narrative empty">
        LLM 归纳未产出（返回空串）。下面的确定性检出不受影响。
      </p>

      <div v-if="task.data.profileAnomalies?.length" class="scroll-x">
        <table class="data">
          <thead>
            <tr><th>缺陷码</th><th>列</th><th>层级</th><th class="num">影响行数</th><th>证据</th></tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in task.data.profileAnomalies" :key="index">
              <td><code>{{ item.code }}</code></td>
              <td>{{ item.column }}</td>
              <td class="small">{{ LEVEL_LABEL[item.level] || item.level }}</td>
              <td class="num tnum">{{ intText(item.affectedRows) }}</td>
              <td class="small secondary">{{ item.evidence }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="small muted">画像阶段没有检出确定性异常。</p>

      <!-- ② 方案 -->
      <h3 class="sec">② 方案</h3>
      <p v-if="task.data.planSummary" class="narrative">{{ task.data.planSummary }}</p>
      <div class="scroll-x">
        <table class="data">
          <thead>
            <tr>
              <th class="num">#</th><th>动作</th><th>目标列</th>
              <th>临床依据 ruleIds</th><th>执行</th><th class="num">自修复</th><th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="step in task.data.steps" :key="step.stepNo">
              <td class="num tnum">{{ step.stepNo }}</td>
              <td>
                <code>{{ step.action }}</code>
                <div class="small muted">{{ PLAN_ACTIONS[step.action]?.label || '' }}</div>
              </td>
              <td class="small">{{ (step.targetColumns || []).join('、') }}</td>
              <td class="small" :class="'evidence-' + evidenceState(step).kind">
                {{ evidenceState(step).text }}
              </td>
              <td>
                <StatusBadge v-if="!step.executed" kind="SKIPPED" text="未执行" />
                <StatusBadge v-else :kind="step.success ? 'SUCCESS' : 'FAILED'" />
              </td>
              <td class="num tnum">{{ step.repairCount ?? 0 }}</td>
              <td class="small secondary">{{ step.skipReason || step.description }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="small muted" style="margin-top: 8px;">
        ruleIds 是这一步的<strong>临床依据</strong>：医生据此核对「系统凭什么这么改」。
        空值对去重、格式归一这类纯工程动作是正常的；
        对越界置空、评分计算这类动作，空值会标红 —— 那意味着它在没有依据的情况下动了数据。
      </p>

      <!-- ③ 澄清 -->
      <h3 class="sec">
        ③ 澄清
        <span class="small muted">按影响数据覆盖率降序，可以从上往下答，随时停下</span>
      </h3>
      <template v-if="task.data.clarifications?.length">
        <div v-for="item in task.data.clarifications" :key="item.clarifyCode" class="clarify">
          <div class="row" style="gap: 10px;">
            <strong>{{ item.question }}</strong>
            <span class="badge">{{ item.level }}</span>
          </div>
          <div class="small secondary" style="margin-top: 4px;">
            <span class="muted">列</span> {{ item.columnName || '—' }}
            <span class="sep">·</span>
            <span class="muted">数据覆盖</span>
            <strong class="tnum">{{ ratioText(item.coverageRatio) }}</strong>
            <template v-if="item.conflictingSources?.length">
              <span class="sep">·</span>
              <span class="muted">冲突来源</span> {{ item.conflictingSources.join(' / ') }}
            </template>
          </div>
          <div v-if="item.coverageRatio !== null && item.coverageRatio !== undefined" class="cover-bar">
            <div class="cover-fill" :style="{ width: (item.coverageRatio * 100) + '%' }"></div>
          </div>
          <ul v-if="item.options?.length" class="options small">
            <li v-for="(option, index) in item.options" :key="index">{{ option }}</li>
          </ul>
          <div v-if="item.evidence" class="small muted">{{ item.evidence }}</div>
        </div>
        <p class="small muted resume-note">
          ⚠ 这里没有「回答并继续」的输入框，因为<strong>后端的澄清答复回传接口还没写</strong>
          （<code>PipelineMode.CLARIFY_RESUME</code> 枚举值已留好，实现未做）。
        </p>
      </template>
      <p v-else class="small muted">本次没有澄清项：需求涉及的决策点在知识库里都有唯一依据。</p>

      <!-- ④ 校验 -->
      <h3 class="sec">④ 校验</h3>
      <p class="small muted" style="margin: 0 0 8px;">
        先看这张矩阵：空的发现列表既可能是「查过没问题」，也可能是「压根没查」。
      </p>
      <div class="scroll-x">
        <table class="data">
          <thead>
            <tr><th>层级</th><th>怎么查的</th><th>执行了吗</th><th>没执行的原因</th><th class="num">发现</th></tr>
          </thead>
          <tbody>
            <tr v-for="level in validationMatrix" :key="level.code">
              <td>{{ level.label }} <code class="small">{{ level.code }}</code></td>
              <td class="small secondary">{{ level.how }}</td>
              <td>
                <StatusBadge :kind="level.executed ? 'YES' : 'NO'"
                             :text="level.executed ? '查过' : '没查'" />
              </td>
              <td class="small secondary">
                <span v-if="level.contradiction" class="contradiction">
                  ⚠ 口径矛盾：这一层标着「没查」，却挂着 {{ level.count }} 条该层的发现。
                  后端只把执行标记打在了产出它的那一层上，这一层漏标了。
                </span>
                <template v-else-if="!level.executed">
                  {{ level.skipReason || '（后端未给出原因）' }}
                </template>
              </td>
              <td class="num tnum">{{ level.count }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="task.data.findings?.length" class="scroll-x" style="margin-top: 12px;">
        <table class="data">
          <thead>
            <tr>
              <th>层级</th><th>发现</th><th>列</th><th>严重度</th>
              <th class="num">影响行数</th><th>证据</th><th>引用标注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in task.data.findings" :key="index">
              <td class="small">{{ LEVEL_LABEL[item.level] || item.level }}</td>
              <td><code>{{ item.code }}</code></td>
              <td>{{ item.column }}</td>
              <td><StatusBadge :kind="item.severity" /></td>
              <td class="num tnum">{{ intText(item.affectedRows) }}</td>
              <td class="small secondary">{{ item.evidence }}</td>
              <td class="small">
                <template v-if="item.sourceRuleId">
                  rule <code>{{ item.sourceRuleId }}</code>
                  <div class="muted">{{ item.sourceDoc }} {{ item.sourceLocator }}</div>
                </template>
                <span v-else class="muted">该层无院内规范可引</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- ⑤ 执行 -->
      <h3 class="sec">⑤ 执行</h3>
      <div class="kv small">
        <div><span class="muted">输出行数</span>
          <strong class="tnum">{{ intText(task.data.outputRowCount) }}</strong></div>
        <div><span class="muted">自修复总轮次</span>
          <strong class="tnum">{{ task.data.totalRepairAttempts ?? 0 }}</strong>
          <span class="muted">（沙箱执行次数，不是模型调用次数）</span></div>
        <div title="修复版与本步骤已跑过的任何一版逐字节相同，即判不收敛、立即停。每命中一次就省掉剩余的全部重试">
          <span class="muted">不收敛短路</span>
          <strong class="tnum">{{ task.data.repairShortCircuitCount ?? 0 }}</strong> 次
        </div>
      </div>
      <p v-if="task.data.outputPath" class="small muted" style="margin: 8px 0 0;">
        输出：<code>{{ task.data.outputPath }}</code>
      </p>
    </div>

    <!-- 最近跑过的任务 -->
    <div v-if="recentTasks.length" class="card">
      <div class="card-head">
        <h2>最近跑过的任务</h2>
        <span class="hint">记在本机浏览器里</span>
      </div>
      <div class="scroll-x">
        <table class="data">
          <thead><tr><th>taskCode</th><th>状态</th><th>需求</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="item in recentTasks" :key="item.taskCode">
              <td><RouterLink :to="'/trace/' + item.taskCode"><code>{{ item.taskCode }}</code></RouterLink></td>
              <td><StatusBadge :kind="item.status" /></td>
              <td class="small secondary">{{ item.requirement }}</td>
              <td class="small muted">{{ item.at }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sec-first { margin-top: 4px; }
.sec {
  margin: 22px 0 8px;
  padding-top: 14px;
  border-top: 1px solid var(--grid);
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.kv { display: flex; gap: 22px; flex-wrap: wrap; color: var(--ink-2); }

.narrative {
  margin: 8px 0 12px;
  padding: 10px 12px;
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  color: var(--ink-1);
  max-width: 90ch;
}
.narrative.empty { color: var(--ink-muted); font-style: italic; }

.note-clarify {
  border-left: 3px solid var(--status-warning);
  padding: 8px 12px;
  margin: 0 0 8px;
  background: var(--surface-2);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}
.case-meta { margin-top: 10px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.sep { color: var(--ink-muted); }

.clarify {
  border: 1px solid var(--border);
  border-left: 3px solid var(--status-warning);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  margin-bottom: 10px;
}
.cover-bar {
  height: 6px;
  background: var(--surface-2);
  border-radius: 3px;
  margin: 8px 0;
  max-width: 320px;
}
.cover-fill { height: 100%; background: var(--seq-450); border-radius: 3px; }
.options { margin: 6px 0 4px; padding-left: 22px; color: var(--ink-2); }
.resume-note { border-top: 1px dashed var(--grid); padding-top: 8px; margin-top: 10px; }

.contradiction { color: var(--status-critical); }

.evidence-ok { color: var(--ink-1); }
.evidence-none { color: var(--ink-muted); }
.evidence-pending { color: var(--ink-muted); font-style: italic; }
.evidence-bad { color: var(--status-critical); font-weight: 600; }

.error { color: var(--status-critical); }
code {
  background: var(--surface-2);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.92em;
}
details summary { cursor: pointer; color: var(--ink-2); }
</style>
