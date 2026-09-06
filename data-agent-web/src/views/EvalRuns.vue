<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import LineChart from '../components/LineChart.vue'
import MetricValue from '../components/MetricValue.vue'
import { METRIC_NOTES } from '../constants'

/**
 * 指标序列。两张图不双轴：比率（0–1）一张，延迟（ms）一张。
 */

const state = ref({ data: null, error: null, loading: true })

async function load() {
  state.value = { data: null, error: null, loading: true }
  try { state.value.data = await api.evalRuns() }
  catch (error) { state.value.error = error.message }
  finally { state.value.loading = false }
}
onMounted(load)

/** 接口给的是 start_time 倒序，表格按原序渲染，画图前反过来 */
const runs = computed(() => state.value.data ?? [])
const chronological = computed(() => [...runs.value].reverse())

function shortLabel(run) {
  // runCode 形如 R20260831155922，前 8 位是日期，同一天多轮只有时间有区分度
  return (run.runCode || '').replace(/^R\d{8}/, '') || run.runCode
}

function passRate(run) {
  if (!run.caseTotal) return null
  return run.casePassed / run.caseTotal
}

const xLabels = computed(() => chronological.value.map(shortLabel))
const xNotes = computed(() => chronological.value.map(run => run.changeNote || ''))

/**
 * 色绑在指标 key 上，按固定槽位顺序分配，不循环、不随排名变。
 */
const rateSeries = computed(() => [
  { key: 'passRate', label: '通过率', color: 'var(--series-1)',
    points: chronological.value.map(passRate) },
  { key: 'execSuccessRate', label: '执行成功率', color: 'var(--series-2)',
    points: chronological.value.map(run => run.execSuccessRate) },
  { key: 'resultCorrectRate', label: '结果正确率', color: 'var(--series-3)',
    points: chronological.value.map(run => run.resultCorrectRate) },
  { key: 'clarifyPrecision', label: '澄清恰当率', color: 'var(--series-4)',
    points: chronological.value.map(run => run.clarifyPrecision) },
  { key: 'discoveryRate', label: '问题发现率', color: 'var(--series-5)',
    points: chronological.value.map(run => run.discoveryRate) }
])

const latencySeries = computed(() => [
  { key: 'p50', label: '延迟 P50', color: 'var(--series-1)',
    points: chronological.value.map(run => run.latencyP50Millis) },
  { key: 'p95', label: '延迟 P95', color: 'var(--series-2)',
    points: chronological.value.map(run => run.latencyP95Millis) }
])

const latencyMax = computed(() => {
  const values = latencySeries.value
    .flatMap(line => line.points)
    .filter(value => value !== null && value !== undefined)
  if (!values.length) return 1000
  return Math.ceil(Math.max(...values) / 5000) * 5000
})

function msFormat(value) {
  return (value / 1000).toFixed(0) + 's'
}

const RUN_CURL = `curl --noproxy '*' -X POST http://localhost:9090/api/eval/run \\
     -H 'Content-Type: application/json; charset=utf-8' \\
     --data-binary @doc/进度/eval-runs/m5-r5-token.json`
</script>

<template>
  <div class="stack">
    <div class="card">
      <div class="card-head">
        <div>
          <h1>评测序列</h1>
          <p class="secondary" style="margin: 6px 0 0; max-width: 80ch;">
            同一份数据、同一个 seed、同一批 32 个用例，改一处 → 重跑 → 看指标怎么动。
            <strong>每一轮都必须写 changeNote</strong> —— 没有它，这串数字涨了不知道是谁的功劳，
            跌了不知道该回滚哪一处。悬停曲线上的点可以看到那一轮改了什么。
          </p>
        </div>
        <button class="ghost" @click="load">刷新</button>
      </div>

      <div v-if="state.loading" class="muted">读取中…</div>
      <div v-else-if="state.error" class="error">{{ state.error }}</div>
      <div v-else-if="!runs.length" class="muted">
        一轮评测都还没跑过。用下面那条 curl 发起第一轮。
      </div>

      <template v-else>
        <h2 style="margin-bottom: 8px;">比率类指标</h2>
        <LineChart :series="rateSeries" :x-labels="xLabels" :x-notes="xNotes"
                   :y-min="0" :y-max="1" :height="280" />

        <!--
          图正下方就是同一份数据的表格。这不是冗余：浅色模式下有三个色槽
          对底色的对比度低于 3:1，dataviz 规范要求配「可见的直接标签或表格视图」，
          relief 由这张表承担。
        -->
        <div class="scroll-x" style="margin-top: 16px;">
          <table class="data">
            <thead>
              <tr>
                <th>轮次</th><th>改了什么</th>
                <th class="num">通过</th><th class="num">执行成功率</th><th class="num">结果正确率</th>
                <th class="num">澄清恰当率</th><th class="num">问题发现率</th>
                <th class="num">常识级检出</th><th class="num">延迟 P50</th><th class="num">Token</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="run in runs" :key="run.runCode">
                <td>
                  <RouterLink :to="'/eval/' + run.runCode"><code>{{ shortLabel(run) }}</code></RouterLink>
                  <div class="small muted">{{ run.startTime }}</div>
                </td>
                <td class="small secondary note-cell">{{ run.changeNote }}</td>
                <td class="num tnum">
                  {{ run.casePassed }}<span class="muted">/{{ run.caseTotal }}</span>
                </td>
                <td class="num"><MetricValue :value="run.execSuccessRate" /></td>
                <td class="num"><MetricValue :value="run.resultCorrectRate" /></td>
                <td class="num"><MetricValue :value="run.clarifyPrecision" /></td>
                <td class="num"><MetricValue :value="run.discoveryRate" /></td>
                <td class="num"><MetricValue :value="run.detectRateSense" /></td>
                <td class="num"><MetricValue :value="run.latencyP50Millis" format="ms" /></td>
                <td class="num"><MetricValue :value="run.tokenTotal" format="int"
                                             :unmeasured-hint="METRIC_NOTES.tokenTotal" /></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="small muted" style="margin-top: 8px;">
          「未测」不是 0：Token 在 M5 才开始采集，M1–M4 各轮那一列是<strong>没有这个数据</strong>，
          不是「这轮没花 Token」。
        </p>

        <h2 style="margin: 26px 0 8px;">延迟（单独一张图）</h2>
        <p class="small muted" style="margin: 0 0 8px;">
          绝不与比率共轴。{{ METRIC_NOTES.latency }}
        </p>
        <LineChart :series="latencySeries" :x-labels="xLabels" :x-notes="xNotes"
                   :y-min="0" :y-max="latencyMax" :format="msFormat" :height="220" />
        <div class="scroll-x" style="margin-top: 12px;">
          <table class="data">
            <thead><tr><th>轮次</th><th class="num">P50</th><th class="num">P95</th><th>模型</th><th>seed</th></tr></thead>
            <tbody>
              <tr v-for="run in runs" :key="run.runCode">
                <td><code>{{ shortLabel(run) }}</code></td>
                <td class="num"><MetricValue :value="run.latencyP50Millis" format="ms" /></td>
                <td class="num"><MetricValue :value="run.latencyP95Millis" format="ms" /></td>
                <td class="small secondary">{{ run.modelName }}</td>
                <td class="small tnum">{{ run.datasetSeed }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>怎么发起新的一轮</h2>
        <span class="hint">这里刻意没有按钮</span>
      </div>
      <p class="secondary" style="max-width: 80ch;">
        <code>POST /api/eval/run</code> 是十几分钟的阻塞请求、约 28 万 Token，且 <code>changeNote</code> 必填。
        把它做成一个按钮，等于<strong>把一次严肃的实验降格成一次点击</strong> ——
        而 changeNote 这条约束的存在恰恰是为了防止这件事。
      </p>
      <pre class="json">{{ RUN_CURL }}</pre>
      <p class="small muted">
        请求体一定要用文件 <code>--data-binary @file</code>：Windows 控制台按 GBK 发中文，
        直接 <code>-d</code> 带中文会得到 <code>Invalid UTF-8 middle byte</code>。
        <code>--noproxy '*'</code> 也不能省，本机全局代理会把 localhost 变成空 503。
      </p>
    </div>
  </div>
</template>

<style scoped>
.note-cell { max-width: 360px; min-width: 220px; white-space: normal; }
.error { color: var(--status-critical); }
code {
  background: var(--surface-2);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.92em;
}
</style>
