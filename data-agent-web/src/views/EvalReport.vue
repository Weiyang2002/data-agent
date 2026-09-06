<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import BarMeter from '../components/BarMeter.vue'
import MetricValue from '../components/MetricValue.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { FINDING_LEVELS, LEVEL_DETECT_NOTE, METRIC_NOTES } from '../constants'

/**
 * 单轮评测报告。这一页拿到的是 {run, results[]} 两张表的原始行，不是完整的
 * EvalRunReportVO（含 metrics[].note、caveats、failureHistogram 的完整报告只有
 * POST /api/eval/run 才返回，且不落库）。页面上把每一处差额显式标注，缺的东西不
 * 渲染成空列表。
 */
const props = defineProps({ runCode: { type: String, required: true } })

const state = ref({ data: null, error: null, loading: true })
const categories = ref([])

async function load() {
  state.value = { data: null, error: null, loading: true }
  try {
    const [report, dictionary] = await Promise.all([
      api.evalReport(props.runCode),
      api.failureCategories().catch(() => [])
    ])
    state.value.data = report
    categories.value = dictionary
  }
  catch (error) { state.value.error = error.message }
  finally { state.value.loading = false }
}
onMounted(load)
watch(() => props.runCode, load)

const run = computed(() => state.value.data?.run ?? null)
const results = computed(() => state.value.data?.results ?? [])

// ── 指标表 ──
const metrics = computed(() => {
  const data = run.value
  if (!data) return []
  return [
    { label: '通过用例', value: data.caseTotal ? data.casePassed / data.caseTotal : null,
      numerator: data.casePassed, denominator: data.caseTotal, note: METRIC_NOTES.casePassed },
    { label: '执行成功率', value: data.execSuccessRate, note: METRIC_NOTES.execSuccessRate },
    { label: '结果正确率', value: data.resultCorrectRate, note: METRIC_NOTES.resultCorrectRate },
    { label: '澄清恰当率', value: data.clarifyPrecision, note: METRIC_NOTES.clarifyPrecision },
    { label: '问题发现率', value: data.discoveryRate, note: METRIC_NOTES.discoveryRate },
    { label: '自修复成功率', value: data.selfRepairRate, note: METRIC_NOTES.selfRepairRate },
    { label: '规范遵从率（行级）', value: data.specComplianceRate, note: METRIC_NOTES.specComplianceRate }
  ]
})

// ── 分层检出率 ──
// 四层分列，不合并成一个总检出率。
const LEVEL_FIELD = {
  ROW: 'detectRateRow',
  DISTRIBUTION: 'detectRateDist',
  STRUCTURE: 'detectRateStructure',
  COMMON_SENSE: 'detectRateSense',
  CLARIFY: 'detectRateClarify'
}

const detectionRows = computed(() => FINDING_LEVELS.map(level => {
  const value = run.value?.[LEVEL_FIELD[level.code]]
  const measured = value !== null && value !== undefined
  return {
    label: level.label,
    sub: level.code,
    value: measured ? value : 0,
    display: measured ? (value * 100).toFixed(2) + '%' : '未测',
    color: measured ? 'var(--seq-450)' : 'var(--surface-2)',
    note: LEVEL_DETECT_NOTE[level.code],
    measured
  }
}))

// ── 归因直方图（前端聚合）──
// failureHistogram 不落库，这里从 results[].failureCategory 聚合，页面上注明来源。
const histogram = computed(() => {
  const counted = new Map()
  for (const row of results.value) {
    const code = row.failureCategory || 'UNKNOWN'
    counted.set(code, (counted.get(code) || 0) + 1)
  }
  // 按归因字典的顺序渲染，字典缺失时兜底追加
  const ordered = categories.value
    .filter(item => counted.has(item.code))
    .map(item => ({ ...item, count: counted.get(item.code) }))
  for (const [code, count] of counted) {
    if (!ordered.some(item => item.code === code)) {
      ordered.push({ code, label: code, meaning: '（归因字典里没有这一项）', countedAsPass: false, count })
    }
  }
  return ordered
})

const histogramRows = computed(() => histogram.value.map(item => ({
  label: item.label,
  sub: item.code,
  value: item.count,
  display: item.count + ' 个',
  color: item.countedAsPass ? 'var(--seq-250)' : 'var(--seq-550)'
})))

// ── 用例表 ──
// 只提供筛选，不提供点表头排序（保持后端定的排序口径）。
const levelFilter = ref('')
const onlyFailed = ref(false)

function levelOf(caseId) {
  return String(caseId || '').split('-')[0] || '?'
}

const caseLevels = computed(() =>
  [...new Set(results.value.map(row => levelOf(row.caseId)))].sort())

const visibleResults = computed(() => results.value.filter(row => {
  if (levelFilter.value && levelOf(row.caseId) !== levelFilter.value) return false
  if (onlyFailed.value) {
    const category = categories.value.find(item => item.code === row.failureCategory)
    // 字典没查到时保守当作未通过
    if (category?.countedAsPass) return false
  }
  return true
}))

/**
 * detectedJson / missedJson / falseAlarmJson 是 JSON 字符串。解析失败时原样显示那段
 * 字符串，不吞异常。
 */
function parseList(text) {
  if (text === null || text === undefined || text === '') return { items: [], raw: null }
  try {
    const parsed = JSON.parse(text)
    return Array.isArray(parsed) ? { items: parsed, raw: null } : { items: [], raw: String(text) }
  }
  catch { return { items: [], raw: String(text) } }
}

function flagKind(value) {
  if (value === null || value === undefined) return null
  return value === 1 ? 'YES' : 'NO'
}
</script>

<template>
  <div class="stack">
    <div v-if="state.loading" class="card muted">读取中…</div>
    <div v-else-if="state.error" class="card"><p class="error">{{ state.error }}</p></div>
    <div v-else-if="!run" class="card">
      <h1>{{ runCode }}</h1>
      <p class="muted">这一轮在 <code>data_agent_eval_run</code> 里没有记录。</p>
    </div>

    <template v-else>
      <!-- caveats 不落库：显示「未落库」，不显示成空列表 -->
      <div class="not-stored" role="note">
        <div class="head">
          <span aria-hidden="true">⚑</span>
          <strong>本轮 caveats 未落库</strong>
        </div>
        <p class="small secondary" style="margin: 6px 0 0;">
          「本轮测不准的东西」这一段只在 <code>POST /api/eval/run</code> 的返回体里，
          <strong>不写数据库</strong>，所以这一页拿不到它。
          这里显示的是<strong>没有这个数据</strong>，不是「本轮没有 caveats」——
          后者会让人把「没测」读成「测了且通过」，而那正是指标表最危险的读法。
          要看它，翻那一轮长请求的输出，或重跑一轮。
        </p>
      </div>

      <div class="card">
        <div class="card-head">
          <div>
            <h1>评测报告 <code>{{ run.runCode }}</code></h1>
            <div class="small muted" style="margin-top: 6px;">
              {{ run.startTime }} → {{ run.endTime }}
              <span class="sep">·</span> 模型 {{ run.modelName }}
              <span class="sep">·</span> seed {{ run.datasetSeed }}
            </div>
          </div>
          <RouterLink to="/eval">← 回到指标序列</RouterLink>
        </div>

        <div class="change-note">
          <span class="muted small">这一轮改了什么</span>
          <p style="margin: 4px 0 0;">{{ run.changeNote }}</p>
        </div>

        <p class="small muted" style="margin-top: 10px;">
          数据集 <code>{{ run.datasetPath }}</code>
        </p>
      </div>

      <!-- 指标 -->
      <div class="card">
        <div class="card-head">
          <h2>指标</h2>
          <span class="hint">
            口径文案由前端内置（后端不落库），权威在 <code>doc/学习文档/M3-基线指标.md</code>
          </span>
        </div>
        <div class="metric-grid">
          <div v-for="item in metrics" :key="item.label" class="metric">
            <div class="metric-label">{{ item.label }}</div>
            <div class="metric-value">
              <MetricValue :value="item.value" :numerator="item.numerator ?? null"
                           :denominator="item.denominator ?? null" />
            </div>
            <div class="metric-note small muted">{{ item.note }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">延迟 P50 / P95</div>
            <div class="metric-value">
              <MetricValue :value="run.latencyP50Millis" format="ms" />
              <span class="muted"> / </span>
              <MetricValue :value="run.latencyP95Millis" format="ms" />
            </div>
            <div class="metric-note small muted">{{ METRIC_NOTES.latency }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">Token 总量</div>
            <div class="metric-value">
              <MetricValue :value="run.tokenTotal" format="int"
                           :unmeasured-hint="METRIC_NOTES.tokenTotal" />
            </div>
            <div class="metric-note small muted">{{ METRIC_NOTES.tokenTotal }}</div>
          </div>
        </div>
      </div>

      <!-- 分层检出率 -->
      <div class="card">
        <div class="card-head">
          <h2>分层检出率</h2>
          <span class="hint">四层分列，不合并成一个总检出率</span>
        </div>
        <BarMeter :rows="detectionRows" :max="1" label-width="150px" />
        <dl class="level-notes small">
          <template v-for="row in detectionRows" :key="row.sub">
            <dt>{{ row.label }}</dt>
            <dd class="secondary">{{ row.note }}</dd>
          </template>
        </dl>
        <p class="small muted">
          逐层下降的曲线<strong>是发现，不是失败</strong>：它量的是确定性代码能覆盖到哪里、
          从哪里开始需要 LLM、以及需要 LLM 的地方它到底行不行。
          一个如实报出来的常识级低值，比一个合并出来的 95% 总检出率有信息量得多。
        </p>
      </div>

      <!-- 归因直方图 -->
      <div class="card">
        <div class="card-head">
          <h2>失败归因</h2>
          <span class="hint">由 {{ results.length }} 条单用例结果前端聚合，不是后端快照</span>
        </div>
        <BarMeter :rows="histogramRows" label-width="190px" />
        <div class="scroll-x" style="margin-top: 14px;">
          <table class="data">
            <thead><tr><th>归因</th><th class="num">用例数</th><th>计通过</th><th>含义</th></tr></thead>
            <tbody>
              <tr v-for="item in histogram" :key="item.code">
                <td><code>{{ item.code }}</code> {{ item.label }}</td>
                <td class="num tnum">{{ item.count }}</td>
                <td><StatusBadge :kind="item.countedAsPass ? 'YES' : 'NO'" /></td>
                <td class="small secondary">{{ item.meaning }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 用例表 -->
      <div class="card">
        <div class="card-head">
          <h2>单用例结果</h2>
          <span class="hint">每行的 taskCode 可以钻进那一次的完整链路</span>
        </div>

        <div class="row" style="margin-bottom: 12px;">
          <label class="field" style="flex: 0 0 180px;">
            <span>按层筛选</span>
            <select v-model="levelFilter">
              <option value="">全部（{{ results.length }}）</option>
              <option v-for="level in caseLevels" :key="level" :value="level">{{ level }}</option>
            </select>
          </label>
          <label class="row small" style="gap: 6px; align-self: flex-end; padding-bottom: 8px;">
            <input type="checkbox" v-model="onlyFailed" style="width: auto;" />
            只看未通过
          </label>
          <span class="small muted" style="align-self: flex-end; padding-bottom: 8px;">
            只给筛选、不给点表头排序 —— 一旦能任意重排，「按 caseId 顺序」这个口径就没了
          </span>
        </div>

        <div class="scroll-x">
          <table class="data">
            <thead>
              <tr>
                <th>用例</th><th>链路</th><th>执行</th><th>结果</th><th>澄清 期望/实际</th>
                <th>检出 / 漏报 / 误报</th><th class="num">自修复</th>
                <th class="num">耗时</th><th>归因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in visibleResults" :key="row.caseId">
                <td><strong>{{ row.caseId }}</strong></td>
                <td>
                  <RouterLink v-if="row.taskCode" :to="'/trace/' + row.taskCode">
                    <code class="small">{{ row.taskCode }}</code>
                  </RouterLink>
                  <span v-else class="muted small">无</span>
                </td>
                <td>
                  <StatusBadge v-if="flagKind(row.execSuccess)" :kind="flagKind(row.execSuccess)" />
                  <span v-else class="unmeasured" title="没有发起过沙箱执行，不是执行失败">未测</span>
                </td>
                <td>
                  <StatusBadge v-if="flagKind(row.resultCorrect)" :kind="flagKind(row.resultCorrect)" />
                  <span v-else class="unmeasured"
                        title="该用例不计入结果正确率（多为 expectClarify=true：澄清答复回传接口未实现）">未测</span>
                </td>
                <td class="small">
                  <!-- 全局 non_null 会让 null 字段消失，判据用 flagKind 而非 ===1 -->
                  <StatusBadge v-if="flagKind(row.clarifyExpected)" :kind="flagKind(row.clarifyExpected)" />
                  <span v-else class="unmeasured" title="这一轮没有记录该用例的澄清期望">未记录</span>
                  <span class="muted"> / </span>
                  <StatusBadge v-if="flagKind(row.clarifyActual)" :kind="flagKind(row.clarifyActual)" />
                  <span v-else class="unmeasured" title="这一轮没有记录该用例的实际澄清">未记录</span>
                </td>
                <td class="small sets">
                  <div v-for="field in [
                        { key: 'detectedJson', tag: '检出', tone: 'ok' },
                        { key: 'missedJson', tag: '漏报', tone: 'miss' },
                        { key: 'falseAlarmJson', tag: '误报', tone: 'false' }]" :key="field.key">
                    <template v-if="parseList(row[field.key]).raw">
                      <span class="tag bad">{{ field.tag }} JSON 解析失败</span>
                      <code class="small">{{ parseList(row[field.key]).raw }}</code>
                    </template>
                    <template v-else-if="parseList(row[field.key]).items.length">
                      <span class="tag" :class="field.tone">{{ field.tag }}</span>
                      {{ parseList(row[field.key]).items.join('、') }}
                    </template>
                  </div>
                </td>
                <td class="num tnum">{{ row.repairAttempts ?? 0 }}</td>
                <td class="num tnum">{{ row.costMillis === null || row.costMillis === undefined
                  ? '—' : (row.costMillis / 1000).toFixed(1) + 's' }}</td>
                <td class="small">
                  <code>{{ row.failureCategory }}</code>
                  <div v-if="row.failureDetail" class="muted detail">{{ row.failureDetail }}</div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="!visibleResults.length" class="muted small" style="margin-top: 10px;">
          当前筛选下没有用例。
        </p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.not-stored {
  border: 1px solid var(--axis);
  border-left: 4px solid var(--ink-muted);
  border-radius: var(--radius-sm);
  background: var(--surface-1);
  padding: 12px 16px;
}
.not-stored .head { display: flex; align-items: baseline; gap: 8px; }
.not-stored .head > span[aria-hidden] { color: var(--ink-muted); }

.change-note {
  border-left: 3px solid var(--series-1);
  padding: 8px 12px;
  background: var(--surface-2);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  max-width: 90ch;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px;
}
.metric {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
}
.metric-label { font-size: 12px; color: var(--ink-2); }
.metric-value { font-size: 20px; margin: 2px 0 6px; }
.metric-note { line-height: 1.5; }

.level-notes {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: 2px 12px;
  margin: 14px 0 10px;
}
.level-notes dt { color: var(--ink-2); }
.level-notes dd { margin: 0; }

.sets { min-width: 260px; white-space: normal; }
.tag {
  display: inline-block;
  font-size: 11px;
  padding: 0 6px;
  border-radius: 4px;
  margin-right: 4px;
  background: var(--surface-2);
  color: var(--ink-2);
}
.tag.miss { color: var(--status-serious); }
.tag.false { color: var(--status-warning); }
.tag.bad { color: var(--status-critical); font-weight: 600; }
.detail { max-width: 320px; white-space: normal; }

.unmeasured {
  color: var(--ink-muted);
  font-style: italic;
  border-bottom: 1px dotted var(--ink-muted);
  cursor: help;
}
.sep { color: var(--ink-muted); }
.error { color: var(--status-critical); }
code {
  background: var(--surface-2);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.92em;
}
</style>
