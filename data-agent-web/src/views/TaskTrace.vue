<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import BarMeter from '../components/BarMeter.vue'
import Caveats from '../components/Caveats.vue'
import StatusBadge from '../components/StatusBadge.vue'

/**
 * 链路与 Token 页。stages（流水，排查用）和 tokenByStage（账，成本分析用）分成两段
 * 渲染。
 */
const props = defineProps({ taskCode: { type: String, required: true } })

const state = ref({ data: null, error: null, loading: true })

async function load() {
  state.value = { data: null, error: null, loading: true }
  try { state.value.data = await api.taskTrace(props.taskCode) }
  catch (error) { state.value.error = error.message }
  finally { state.value.loading = false }
}

onMounted(load)
watch(() => props.taskCode, load)

const trace = computed(() => state.value.data)

/**
 * 后端 caveats 文案里带 <b> 标签，这里不上 v-html，只把这一对标签摘掉。
 */
function plain(text) {
  return String(text).replace(/<\/?b>/g, '')
}

const caveats = computed(() => (trace.value?.caveats ?? []).map(plain))

// ── 阶段瀑布 ──
// 不做「按时间轴对齐」的瀑布图（start_time 只到秒，精度不够）。画时长条：
// 宽度 = 该阶段耗时，depth=1 缩进。
const maxStageCost = computed(() =>
  Math.max(1, ...(trace.value?.stages ?? []).map(row => row.costMillis || 0)))

function stageWidth(row) {
  return ((row.costMillis || 0) / maxStageCost.value) * 100
}

// ── Token 账 ──
const tokenRows = computed(() => (trace.value?.tokenByStage ?? []).map(row => ({
  label: (row.depth === 1 ? '└ ' : '') + row.label,
  sub: row.stageCode,
  value: row.totalTokens || 0,
  display: (row.totalTokens || 0).toLocaleString('zh-CN')
    + (row.tokenShare === null || row.tokenShare === undefined
      ? '' : '  ' + (row.tokenShare * 100).toFixed(1) + '%'),
  color: row.depth === 1 ? 'var(--seq-250)' : 'var(--seq-450)'
})))

// ── 三条闭合性质 ──
// 把「各阶段之和 = 总量」这类加法算出来摆在页面上，是延迟/Token 口径的探针
// （埋点加密后延迟翻倍这类问题不会报错，只能靠对账发现）。
const closures = computed(() => {
  const data = trace.value
  if (!data) return []
  const out = []

  // 1) 各阶段 Token 求和 = 任务总量
  const stageTokenSum = (data.tokenByStage ?? [])
    .reduce((sum, row) => sum + (row.totalTokens || 0), 0)
  out.push({
    title: '各阶段 Token 求和 = 任务总量',
    left: stageTokenSum,
    right: data.totalTokens || 0,
    unit: 'Token',
    why: '按阶段拆的账不重复计算：每一行只记自身，父阶段不含子阶段'
  })

  // 2) 父阶段自身耗时 + 子阶段耗时 = 父阶段总耗时
  //    父子关系必须从 stages（流水，按 start_time 再按 depth 排，父在子前）推，
  //    不能从 tokenByStage（账本按 stageOrder 排，子阶段号可能比父阶段小）推。
  const ledger = new Map((data.tokenByStage ?? []).map(row => [row.stageCode, row]))
  let parent = null
  for (const row of data.stages ?? []) {
    if (row.depth === 0) {
      const parentCost = ledger.get(row.stageCode)
      parent = { row: parentCost, children: [], seen: new Set() }
      // 账本里没有这个父阶段就不参与这条性质（拿不到 selfCostMillis）
      if (parentCost) out.push({ group: parent })
    }
    else if (parent && !parent.seen.has(row.stageCode)) {
      // 一个子阶段可能被进入多次（CODEGEN 重试），按 stageCode 去重
      parent.seen.add(row.stageCode)
      const cost = ledger.get(row.stageCode)
      if (cost) parent.children.push(cost)
    }
  }

  // 3) depth=0 各阶段耗时求和 = 端到端
  const topSum = (data.stages ?? [])
    .filter(row => row.depth === 0)
    .reduce((sum, row) => sum + (row.costMillis || 0), 0)
  const allSum = (data.stages ?? [])
    .reduce((sum, row) => sum + (row.costMillis || 0), 0)
  out.push({
    title: 'depth = 0 各阶段耗时求和 = 端到端',
    left: topSum,
    right: data.totalCostMillis || 0,
    unit: 'ms',
    why: '不加区分地把 ' + (data.stages?.length || 0) + ' 个阶段全部求和会得到 '
      + allSum.toLocaleString('zh-CN') + ' ms（子阶段耗时已含在父阶段里）'
  })
  return out
})

/** 挑出有子阶段的父子分组单独渲染 */
const nestedGroups = computed(() =>
  closures.value.filter(item => item.group).map(item => item.group)
    .filter(group => group.children.length > 0))
const sumChecks = computed(() => closures.value.filter(item => !item.group))

/** 一个都没有时是 null，不是「全部闭合」 */
const nestedAllClosed = computed(() => {
  if (!nestedGroups.value.length) return null
  return nestedGroups.value.every(group => {
    const closure = groupClosure(group)
    return closure.left === closure.right
  })
})

function groupClosure(group) {
  const childSum = group.children.reduce((sum, row) => sum + (row.totalCostMillis || 0), 0)
  return {
    self: group.row.selfCostMillis || 0,
    childSum,
    left: (group.row.selfCostMillis || 0) + childSum,
    right: group.row.totalCostMillis || 0
  }
}

function intText(value) {
  return value === null || value === undefined ? '—' : value.toLocaleString('zh-CN')
}
</script>

<template>
  <div class="stack">
    <div v-if="state.loading" class="card muted">正在读取链路…</div>
    <div v-else-if="state.error" class="card">
      <h1>链路 {{ taskCode }}</h1>
      <p class="error">{{ state.error }}</p>
      <p class="small muted">
        任务只有跑过才有链路。刚跑完的任务可以从<RouterLink to="/task">处理任务</RouterLink>页回到这里，
        历史任务从<RouterLink to="/eval">评测报告</RouterLink>的用例表钻取。
      </p>
    </div>

    <template v-else-if="trace">
      <!-- caveats 永远在最上面 -->
      <Caveats :items="caveats" title="这条链路里测不准的东西" />

      <div class="card">
        <div class="card-head">
          <div>
            <h1>链路与 Token</h1>
            <div class="row small" style="margin-top: 6px; gap: 12px;">
              <code>{{ trace.taskCode }}</code>
              <StatusBadge :kind="trace.status" />
              <span class="muted">traceId {{ trace.traceId }}</span>
              <span class="muted">{{ trace.createTime }}</span>
            </div>
          </div>
          <button class="ghost" @click="load">刷新</button>
        </div>

        <p v-if="trace.requirement" class="requirement">{{ trace.requirement }}</p>
        <p v-if="trace.failReason" class="error">{{ trace.failReason }}</p>

        <div class="tiles">
          <div class="tile">
            <div class="tile-label">端到端耗时</div>
            <div class="tile-value tnum">{{ ((trace.totalCostMillis || 0) / 1000).toFixed(2) }}<span class="unit">s</span></div>
            <div class="tile-sub muted">只求和 depth = 0 的阶段</div>
          </div>
          <div class="tile">
            <div class="tile-label">Token 总量</div>
            <div class="tile-value tnum">{{ intText(trace.totalTokens) }}</div>
            <div class="tile-sub muted">
              入 {{ intText(trace.promptTokens) }} / 出 {{ intText(trace.completionTokens) }}
              <strong v-if="trace.usageMissingCalls > 0" class="lower-bound">·是下界不是真值</strong>
            </div>
          </div>
          <div class="tile">
            <div class="tile-label">模型调用</div>
            <div class="tile-value tnum">{{ intText(trace.modelCalls) }}<span class="unit">次</span></div>
            <div class="tile-sub muted">不是沙箱执行次数，两者是两个口径</div>
          </div>
          <div class="tile">
            <div class="tile-label">usage 缺失</div>
            <div class="tile-value tnum" :class="{ bad: trace.usageMissingCalls > 0 }">
              {{ intText(trace.usageMissingCalls) }}<span class="unit">次</span>
            </div>
            <div class="tile-sub muted">大于 0 时上面的 Token 是下界</div>
          </div>
        </div>
      </div>

      <!-- 阶段流水 -->
      <div class="card">
        <div class="card-head">
          <h2>阶段流水</h2>
          <span class="hint">按发生顺序，depth = 1 是嵌在上一个顶层阶段里的子阶段</span>
        </div>

        <div class="waterfall">
          <div v-for="(row, index) in trace.stages" :key="index"
               class="wf-row" :class="{ nested: row.depth === 1 }">
            <div class="wf-name">
              <span v-if="row.depth === 1" class="tree" aria-hidden="true">└</span>
              {{ row.label }}
              <code class="small">{{ row.stageCode }}</code>
            </div>
            <div class="wf-track">
              <div class="wf-bar" :class="'state-' + (row.state || '').toLowerCase()"
                   :style="{ width: stageWidth(row) + '%' }"></div>
            </div>
            <div class="wf-cost tnum">{{ intText(row.costMillis) }} ms</div>
            <div class="wf-state"><StatusBadge :kind="row.state" /></div>
          </div>
        </div>

        <p class="small muted" style="margin-top: 10px;">
          条的宽度是<strong>时长</strong>，不是时间轴上的位置。
        </p>

        <div v-if="trace.stages.some(row => row.summary || row.errorMsg)"
             class="scroll-x" style="margin-top: 12px;">
          <table class="data">
            <thead><tr><th>阶段</th><th>摘要</th><th>错误</th></tr></thead>
            <tbody>
              <tr v-for="(row, index) in trace.stages.filter(item => item.summary || item.errorMsg)" :key="index">
                <td class="small"><code>{{ row.stageCode }}</code></td>
                <td class="small secondary">{{ row.summary }}</td>
                <td class="small error">{{ row.errorMsg }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Token 账 -->
      <div class="card">
        <div class="card-head">
          <h2>Token 按阶段</h2>
          <span class="hint">每行只记自身，不含子阶段 —— 所以这一列求和 = 任务总量</span>
        </div>

        <template v-if="tokenRows.length">
          <BarMeter :rows="tokenRows" label-width="190px" />

          <div class="scroll-x" style="margin-top: 14px;">
            <table class="data">
              <thead>
                <tr>
                  <th>阶段</th><th class="num">进入</th><th class="num">模型调用</th>
                  <th class="num">入</th><th class="num">出</th><th class="num">合计</th>
                  <th class="num">占比</th><th class="num">自身耗时</th><th class="num">含子阶段</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in trace.tokenByStage" :key="row.stageCode">
                  <td :style="{ paddingLeft: row.depth === 1 ? '26px' : '' }">
                    {{ row.label }} <code class="small">{{ row.stageCode }}</code>
                    <div v-if="row.usageMissingCalls > 0" class="small lower-bound">
                      {{ row.usageMissingCalls }} 次未回 usage，该行是下界
                    </div>
                  </td>
                  <td class="num tnum">{{ intText(row.enterCount) }}</td>
                  <td class="num tnum">{{ intText(row.modelCalls) }}</td>
                  <td class="num tnum">{{ intText(row.promptTokens) }}</td>
                  <td class="num tnum">{{ intText(row.completionTokens) }}</td>
                  <td class="num tnum"><strong>{{ intText(row.totalTokens) }}</strong></td>
                  <td class="num tnum">
                    {{ row.tokenShare === null || row.tokenShare === undefined
                       ? '—' : (row.tokenShare * 100).toFixed(1) + '%' }}
                  </td>
                  <td class="num tnum">{{ intText(row.selfCostMillis) }}</td>
                  <td class="num tnum">{{ intText(row.totalCostMillis) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
        <p v-else class="muted">
          这个任务没有 Token 账本。<strong>这里显示的是「没有这个数据」，不是 0</strong>。
        </p>
      </div>

      <!-- 闭合性质 -->
      <div class="card">
        <div class="card-head">
          <h2>三条闭合性质</h2>
          <span class="hint">算不闭合就把差值摆出来</span>
        </div>

        <div v-for="(check, index) in sumChecks" :key="index" class="closure">
          <div class="row" style="gap: 10px;">
            <StatusBadge :kind="check.left === check.right ? 'PASS' : 'FAIL'"
                         :text="check.left === check.right ? '闭合' : '差 ' + (check.left - check.right)" />
            <strong>{{ check.title }}</strong>
          </div>
          <div class="small secondary" style="margin-top: 4px;">
            <span class="tnum">{{ check.left.toLocaleString('zh-CN') }}</span>
            {{ check.left === check.right ? '=' : '≠' }}
            <span class="tnum">{{ check.right.toLocaleString('zh-CN') }}</span>
            {{ check.unit }}
          </div>
          <div class="small muted">{{ check.why }}</div>
        </div>

        <div class="closure">
          <div class="row" style="gap: 10px;">
            <StatusBadge v-if="nestedAllClosed === null" kind="SKIPPED" text="无从验证" />
            <StatusBadge v-else :kind="nestedAllClosed ? 'PASS' : 'FAIL'" />
            <strong>父阶段自身耗时 + 子阶段耗时 = 父阶段含子阶段耗时</strong>
          </div>

          <table v-if="nestedGroups.length" class="data" style="margin-top: 8px;">
            <thead>
              <tr><th>父阶段</th><th class="num">自身</th><th class="num">子阶段合计</th>
                  <th class="num">= 相加</th><th class="num">含子阶段（后端给的）</th><th>闭合</th></tr>
            </thead>
            <tbody>
              <tr v-for="group in nestedGroups" :key="group.row.stageCode">
                <td>
                  {{ group.row.label }}
                  <div class="small muted">{{ group.children.map(child => child.stageCode).join(' + ') }}</div>
                </td>
                <td class="num tnum">{{ groupClosure(group).self }}</td>
                <td class="num tnum">{{ groupClosure(group).childSum }}</td>
                <td class="num tnum">{{ groupClosure(group).left }}</td>
                <td class="num tnum">{{ groupClosure(group).right }}</td>
                <td>
                  <StatusBadge :kind="groupClosure(group).left === groupClosure(group).right ? 'PASS' : 'FAIL'"
                               :text="groupClosure(group).left === groupClosure(group).right
                                      ? '闭合' : '差 ' + (groupClosure(group).left - groupClosure(group).right)" />
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="small muted" style="margin-top: 6px;">
            这个任务没有嵌套阶段（账本里没有 depth = 1 的行），所以这条<strong>没验过</strong> ——
            不是验过且通过。
          </p>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.requirement {
  margin: 4px 0 14px;
  padding: 10px 12px;
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  max-width: 90ch;
}

.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px;
}
.tile {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
}
.tile-label { font-size: 12px; color: var(--ink-2); }
.tile-value { font-size: 22px; font-weight: 600; line-height: 1.3; }
.tile-value.bad { color: var(--status-critical); }
.tile-value .unit { font-size: 13px; font-weight: 400; margin-left: 3px; color: var(--ink-2); }
.tile-sub { font-size: 11px; }
.lower-bound { color: var(--status-warning); }

.waterfall { display: flex; flex-direction: column; gap: 2px; }
.wf-row { display: flex; align-items: center; gap: 10px; padding: 3px 0; }
.wf-name {
  flex: 0 0 240px;
  font-size: 12px;
  color: var(--ink-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf-row.nested .wf-name { padding-left: 18px; color: var(--ink-2); }
.tree { color: var(--ink-muted); margin-right: 2px; }
.wf-track { flex: 1 1 auto; min-width: 60px; height: 12px; background: var(--surface-2); border-radius: 2px 4px 4px 2px; }
.wf-bar {
  height: 100%;
  border-radius: 2px 4px 4px 2px;
  min-width: 2px;
  background: var(--seq-450);
}
.wf-row.nested .wf-bar { background: var(--seq-250); }
.wf-bar.state-failed { background: var(--status-critical); }
.wf-bar.state-skipped { background: var(--axis); }
.wf-cost { flex: 0 0 90px; text-align: right; font-size: 12px; font-weight: 600; }
.wf-state { flex: 0 0 96px; }

.closure {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  margin-bottom: 10px;
}
.error { color: var(--status-critical); }
code {
  background: var(--surface-2);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.92em;
}
</style>
